package research.jpa;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import javax.management.MBeanServer;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.stat.Statistics;

import com.sun.management.HotSpotDiagnosticMXBean;

public final class JpaHibernateMemoryStudy {
    private static final int TIMING_ITERATIONS = 250;
    private static final int TIMING_WARMUP = 75;
    private static final int PRESSURE_STEP_BYTES = 1_048_576;
    private static final int PRESSURE_MAX_STEPS = 96;

    private JpaHibernateMemoryStudy() {
    }

    public static void main(String[] args) throws Exception {
        StudyOptions options = StudyOptions.parse(args);
        Path outputDir = Path.of("build", "reports", "jpa-memory-study");
        Files.createDirectories(outputDir);

        try (SessionFactory sessionFactory = buildSessionFactory()) {
            Long entityId = seed(sessionFactory);

            RetentionResult detachResult = runRetentionExperiment(sessionFactory, entityId, false, options, outputDir);
            RetentionResult clearResult = runRetentionExperiment(sessionFactory, entityId, true, options, outputDir);
            TimingResult timingResult = runTimingExperiment(sessionFactory, entityId);

            String report = buildReport(detachResult, clearResult, timingResult, options);
            Path reportPath = outputDir.resolve("findings.md");
            Files.writeString(reportPath, report, StandardCharsets.UTF_8);

            System.out.println(report);
            System.out.println();
            System.out.println("Report written to " + reportPath.toAbsolutePath());
        }
    }

    private static SessionFactory buildSessionFactory() {
        Properties properties = new Properties();
        properties.put("hibernate.connection.driver_class", "org.h2.Driver");
        properties.put("hibernate.connection.url", "jdbc:h2:mem:study;DB_CLOSE_DELAY=-1");
        properties.put("hibernate.connection.username", "sa");
        properties.put("hibernate.connection.password", "");
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        properties.put("hibernate.show_sql", "false");
        properties.put("hibernate.format_sql", "false");
        properties.put("hibernate.generate_statistics", "true");
        properties.put("hibernate.cache.use_second_level_cache", "false");
        properties.put("hibernate.cache.use_query_cache", "false");
        properties.put("hibernate.jdbc.batch_size", "0");

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
            .applySettings(properties)
            .build();

        return new MetadataSources(registry)
            .addAnnotatedClass(SecretNote.class)
            .buildMetadata()
            .buildSessionFactory();
    }

    private static Long seed(SessionFactory sessionFactory) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            SecretNote note = new SecretNote(
                "owner-" + UUID.randomUUID(),
                markerString("db-note"),
                secretBytes(markerString("db-secret"))
            );
            session.persist(note);
            session.getTransaction().commit();
            return note.getId();
        }
    }

    private static RetentionResult runRetentionExperiment(
        SessionFactory sessionFactory,
        Long entityId,
        boolean useClear,
        StudyOptions options,
        Path outputDir
    ) throws Exception {
        RetentionProbe probe = createRetentionProbe(sessionFactory, entityId, useClear, options, outputDir);

        ReachabilityState afterSessionClose = snapshotReachability(probe);

        PressureObservation pressureObservation = options.windowProbe()
            ? runAllocationPressureWindow(probe)
            : PressureObservation.notRun();

        forceGc();

        if (options.heapDumps()) {
            dumpHeap(outputDir.resolve(probe.mode() + "-after-gc-live.hprof"), true);
        }
        if (options.nonLiveHeapDumps()) {
            dumpHeap(outputDir.resolve(probe.mode() + "-after-gc-all.hprof"), false);
        }

        ReachabilityState afterForcedGc = snapshotReachability(probe);

        return probe.toResult(afterSessionClose, pressureObservation, afterForcedGc);
    }

    private static RetentionProbe createRetentionProbe(
        SessionFactory sessionFactory,
        Long entityId,
        boolean useClear,
        StudyOptions options,
        Path outputDir
    ) throws Exception {
        String mode = useClear ? "clear" : "detach";
        String replacementMarker = markerString("sanitized-note");
        String originalNoteMarker;
        String originalPayloadMarker;
        String replacementPayloadMarker = markerString("replacement-payload");

        WeakReference<SecretNote> entityRef;
        WeakReference<Object[]> loadedStateRef;
        WeakReference<byte[]> oldPayloadRef;
        WeakReference<String> oldNoteRef;

        int managedEntriesBefore;
        int managedEntriesAfter;
        int payloadIndex;
        int noteIndex;
        ReachabilityState afterOperation;

        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            SecretNote entity = session.find(SecretNote.class, entityId);
            SessionImplementor sessionImplementor = session.unwrap(SessionImplementor.class);
            PersistenceContext persistenceContext = sessionImplementor.getPersistenceContextInternal();
            EntityEntry entityEntry = persistenceContext.getEntry(entity);

            Object[] loadedState = entityEntry.getLoadedState();
            String[] propertyNames = entityEntry.getPersister().getPropertyNames();
            payloadIndex = indexOf(propertyNames, "payload");
            noteIndex = indexOf(propertyNames, "note");

            byte[] oldPayload = (byte[]) loadedState[payloadIndex];
            String oldNote = (String) loadedState[noteIndex];
            originalPayloadMarker = extractMarker(oldPayload);
            originalNoteMarker = oldNote;

            managedEntriesBefore = persistenceContext.getNumberOfManagedEntities();

            entity.setNote(replacementMarker);
            entity.setPayload(secretBytes(replacementPayloadMarker));

            entityRef = new WeakReference<>(entity);
            loadedStateRef = new WeakReference<>(loadedState);
            oldPayloadRef = new WeakReference<>(oldPayload);
            oldNoteRef = new WeakReference<>(oldNote);

            if (options.heapDumps()) {
                dumpHeap(outputDir.resolve(mode + "-before-live.hprof"), true);
            }
            if (options.nonLiveHeapDumps()) {
                dumpHeap(outputDir.resolve(mode + "-before-all.hprof"), false);
            }

            if (useClear) {
                session.clear();
            } else {
                session.detach(entity);
            }

            managedEntriesAfter = persistenceContext.getNumberOfManagedEntities();
            afterOperation = snapshotReachability(entityRef, loadedStateRef, oldNoteRef, oldPayloadRef);

            if (options.heapDumps()) {
                dumpHeap(outputDir.resolve(mode + "-after-op-live.hprof"), true);
            }
            if (options.nonLiveHeapDumps()) {
                dumpHeap(outputDir.resolve(mode + "-after-op-all.hprof"), false);
            }

            session.getTransaction().rollback();

            entity = null;
            loadedState = null;
            oldPayload = null;
            oldNote = null;
            entityEntry = null;
            persistenceContext = null;
            sessionImplementor = null;
        }

        return new RetentionProbe(
            mode,
            managedEntriesBefore,
            managedEntriesAfter,
            noteIndex,
            payloadIndex,
            originalNoteMarker,
            originalPayloadMarker,
            replacementMarker,
            replacementPayloadMarker,
            afterOperation,
            entityRef,
            loadedStateRef,
            oldNoteRef,
            oldPayloadRef
        );
    }

    private static PressureObservation runAllocationPressureWindow(RetentionProbe probe) {
        long start = System.nanoTime();
        List<byte[]> pressure = new ArrayList<>();
        ReachabilityState lastState = snapshotReachability(probe);
        int steps = 0;

        while (steps < PRESSURE_MAX_STEPS) {
            if (!lastState.anyAlive()) {
                break;
            }

            pressure.add(new byte[PRESSURE_STEP_BYTES]);
            steps++;
            lastState = snapshotReachability(probe);
        }

        long elapsedNanos = System.nanoTime() - start;
        pressure.clear();

        return new PressureObservation(
            true,
            steps,
            PRESSURE_STEP_BYTES * steps,
            elapsedNanos / 1_000_000.0,
            lastState
        );
    }

    private static TimingResult runTimingExperiment(SessionFactory sessionFactory, Long entityId) {
        Statistics statistics = sessionFactory.getStatistics();
        statistics.clear();

        try (Session warmupSession = sessionFactory.openSession()) {
            for (int i = 0; i < TIMING_WARMUP; i++) {
                warmupSession.clear();
                warmupSession.find(SecretNote.class, entityId);
            }
        }

        ScenarioMeasurement managedHit = measureScenario(sessionFactory, statistics, entityId, Scenario.MANAGED_HIT);
        ScenarioMeasurement detachMiss = measureScenario(sessionFactory, statistics, entityId, Scenario.DETACH_THEN_FIND);
        ScenarioMeasurement clearMiss = measureScenario(sessionFactory, statistics, entityId, Scenario.CLEAR_THEN_FIND);

        return new TimingResult(managedHit, detachMiss, clearMiss);
    }

    private static ScenarioMeasurement measureScenario(
        SessionFactory sessionFactory,
        Statistics statistics,
        Long entityId,
        Scenario scenario
    ) {
        statistics.clear();
        List<Long> samples = new ArrayList<>(TIMING_ITERATIONS);

        try (Session session = sessionFactory.openSession()) {
            if (scenario == Scenario.MANAGED_HIT) {
                session.find(SecretNote.class, entityId);
            }

            for (int i = 0; i < TIMING_ITERATIONS; i++) {
                if (scenario == Scenario.DETACH_THEN_FIND) {
                    SecretNote entity = session.find(SecretNote.class, entityId);
                    session.detach(entity);
                } else if (scenario == Scenario.CLEAR_THEN_FIND) {
                    session.clear();
                }

                long start = System.nanoTime();
                session.find(SecretNote.class, entityId);
                long elapsed = System.nanoTime() - start;
                samples.add(elapsed);
            }
        }

        return new ScenarioMeasurement(
            scenario.label,
            nanosToMicros(average(samples)),
            nanosToMicros(percentile(samples, 50)),
            nanosToMicros(percentile(samples, 95)),
            statistics.getPrepareStatementCount(),
            statistics.getEntityLoadCount()
        );
    }

    private static String buildReport(
        RetentionResult detachResult,
        RetentionResult clearResult,
        TimingResult timingResult,
        StudyOptions options
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# JPA/Hibernate Persistence-Context Memory Study\n\n");
        builder.append("Generated: ").append(Instant.now()).append("\n\n");

        builder.append("## Scope\n\n");
        builder.append("- Retention question: after `detach()` or `clear()`, do Hibernate dirty-check snapshots or entity values remain reachable long enough to be recovered?\n");
        builder.append("- Timing question: does first-level-cache membership create measurable latency differences?\n");
        builder.append("- Heap dump mode: live=").append(options.heapDumps()).append(", nonLive=").append(options.nonLiveHeapDumps()).append(", windowProbe=").append(options.windowProbe()).append("\n\n");

        builder.append("## Retention Results\n\n");
        appendRetention(builder, detachResult);
        appendRetention(builder, clearResult);

        builder.append("## Timing Results\n\n");
        builder.append("| Scenario | avg us | p50 us | p95 us | prepared statements | entity loads |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (ScenarioMeasurement measurement : List.of(
            timingResult.managedHit(),
            timingResult.detachThenFind(),
            timingResult.clearThenFind()
        )) {
            builder.append("| ")
                .append(measurement.label()).append(" | ")
                .append(format(measurement.averageMicros())).append(" | ")
                .append(format(measurement.p50Micros())).append(" | ")
                .append(format(measurement.p95Micros())).append(" | ")
                .append(measurement.prepareStatementCount()).append(" | ")
                .append(measurement.entityLoadCount()).append(" |\n");
        }

        builder.append("\n## Initial Interpretation\n\n");
        builder.append("- `detach()` and `clear()` do not erase objects. They sever Hibernate's management references. If application references are still held, the object stays alive.\n");
        builder.append("- `afterOperation` and `afterSessionClose` show the pre-GC recovery window. Objects can remain recoverable until the JVM actually collects them.\n");
        builder.append("- `afterForcedGc` answers the narrower question of strong reachability after Hibernate release plus explicit garbage collection.\n");
        builder.append("- `managed-hit` versus detached/cleared timing is a concrete membership oracle inside the same JVM and persistence-context boundary.\n");
        builder.append("- `live=false` heap dumps add a forensic path for checking whether marker strings or payloads still appear in the raw heap, even when they are no longer live.\n");
        return builder.toString();
    }

    private static void appendRetention(StringBuilder builder, RetentionResult result) {
        builder.append("### `").append(result.mode()).append("`\n\n");
        builder.append("| Field | Value |\n");
        builder.append("| --- | --- |\n");
        builder.append("| managedEntriesBefore | ").append(result.managedEntriesBefore()).append(" |\n");
        builder.append("| managedEntriesAfter | ").append(result.managedEntriesAfter()).append(" |\n");
        builder.append("| originalNoteMarker | ").append(result.originalNoteMarker()).append(" |\n");
        builder.append("| originalPayloadMarker | ").append(result.originalPayloadMarker()).append(" |\n");
        builder.append("| replacementNoteMarker | ").append(result.replacementNoteMarker()).append(" |\n");
        builder.append("| replacementPayloadMarker | ").append(result.replacementPayloadMarker()).append(" |\n");
        builder.append("| noteIndex | ").append(result.noteIndex()).append(" |\n");
        builder.append("| payloadIndex | ").append(result.payloadIndex()).append(" |\n");
        appendReachability(builder, "afterOperation", result.afterOperation());
        appendReachability(builder, "afterSessionClose", result.afterSessionClose());
        builder.append("| pressureProbeRan | ").append(result.pressureObservation().ran()).append(" |\n");
        builder.append("| pressureSteps | ").append(result.pressureObservation().steps()).append(" |\n");
        builder.append("| pressureBytesAllocated | ").append(result.pressureObservation().bytesAllocated()).append(" |\n");
        builder.append("| pressureElapsedMs | ").append(format(result.pressureObservation().elapsedMillis())).append(" |\n");
        appendReachability(builder, "afterPressure", result.pressureObservation().finalState());
        appendReachability(builder, "afterForcedGc", result.afterForcedGc());
        builder.append("\n");
    }

    private static void appendReachability(StringBuilder builder, String label, ReachabilityState state) {
        builder.append("| ").append(label).append(".entityAlive | ").append(state.entityAlive()).append(" |\n");
        builder.append("| ").append(label).append(".loadedStateAlive | ").append(state.loadedStateAlive()).append(" |\n");
        builder.append("| ").append(label).append(".oldNoteAlive | ").append(state.oldNoteAlive()).append(" |\n");
        builder.append("| ").append(label).append(".oldPayloadAlive | ").append(state.oldPayloadAlive()).append(" |\n");
    }

    private static ReachabilityState snapshotReachability(RetentionProbe probe) {
        return snapshotReachability(
            probe.entityRef(),
            probe.loadedStateRef(),
            probe.oldNoteRef(),
            probe.oldPayloadRef()
        );
    }

    private static ReachabilityState snapshotReachability(
        WeakReference<SecretNote> entityRef,
        WeakReference<Object[]> loadedStateRef,
        WeakReference<String> oldNoteRef,
        WeakReference<byte[]> oldPayloadRef
    ) {
        return new ReachabilityState(
            entityRef.get() != null,
            loadedStateRef.get() != null,
            oldNoteRef.get() != null,
            oldPayloadRef.get() != null
        );
    }

    private static void forceGc() throws InterruptedException {
        for (int i = 0; i < 6; i++) {
            System.gc();
            System.runFinalization();
            Thread.sleep(150L);
        }
    }

    private static void dumpHeap(Path path, boolean live) throws Exception {
        Files.deleteIfExists(path);
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        HotSpotDiagnosticMXBean bean = ManagementFactory.newPlatformMXBeanProxy(
            server,
            "com.sun.management:type=HotSpotDiagnostic",
            HotSpotDiagnosticMXBean.class
        );
        bean.dumpHeap(path.toAbsolutePath().toString(), live);
    }

    private static byte[] secretBytes(String marker) {
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[65_536];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = markerBytes[i % markerBytes.length];
        }
        return payload;
    }

    private static String extractMarker(byte[] payload) {
        int len = Math.min(payload.length, 96);
        return new String(payload, 0, len, StandardCharsets.UTF_8);
    }

    private static String markerString(String label) {
        return label + "::" + UUID.randomUUID() + "::" + UUID.randomUUID();
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (target.equals(values[i])) {
                return i;
            }
        }
        throw new IllegalArgumentException("Missing property " + target + " in " + Arrays.toString(values));
    }

    private static double average(List<Long> samples) {
        long sum = 0L;
        for (long sample : samples) {
            sum += sample;
        }
        return (double) sum / samples.size();
    }

    private static double percentile(List<Long> samples, int percentile) {
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static double nanosToMicros(double nanos) {
        return nanos / 1_000.0;
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }

    private enum Scenario {
        MANAGED_HIT("managed-hit"),
        DETACH_THEN_FIND("detach-then-find"),
        CLEAR_THEN_FIND("clear-then-find");

        private final String label;

        Scenario(String label) {
            this.label = label;
        }
    }

    private record StudyOptions(
        boolean heapDumps,
        boolean nonLiveHeapDumps,
        boolean windowProbe
    ) {
        static StudyOptions parse(String[] args) {
            List<String> values = Arrays.asList(args);
            boolean heapDumps = values.contains("--heap-dumps");
            boolean nonLiveHeapDumps = values.contains("--non-live-heap-dumps");
            boolean windowProbe = !values.contains("--skip-window-probe");
            return new StudyOptions(heapDumps, nonLiveHeapDumps, windowProbe);
        }
    }

    private record RetentionResult(
        String mode,
        int managedEntriesBefore,
        int managedEntriesAfter,
        int noteIndex,
        int payloadIndex,
        String originalNoteMarker,
        String originalPayloadMarker,
        String replacementNoteMarker,
        String replacementPayloadMarker,
        ReachabilityState afterOperation,
        ReachabilityState afterSessionClose,
        PressureObservation pressureObservation,
        ReachabilityState afterForcedGc
    ) {
    }

    private record RetentionProbe(
        String mode,
        int managedEntriesBefore,
        int managedEntriesAfter,
        int noteIndex,
        int payloadIndex,
        String originalNoteMarker,
        String originalPayloadMarker,
        String replacementNoteMarker,
        String replacementPayloadMarker,
        ReachabilityState afterOperation,
        WeakReference<SecretNote> entityRef,
        WeakReference<Object[]> loadedStateRef,
        WeakReference<String> oldNoteRef,
        WeakReference<byte[]> oldPayloadRef
    ) {
        RetentionResult toResult(
            ReachabilityState afterSessionClose,
            PressureObservation pressureObservation,
            ReachabilityState afterForcedGc
        ) {
            return new RetentionResult(
                mode,
                managedEntriesBefore,
                managedEntriesAfter,
                noteIndex,
                payloadIndex,
                originalNoteMarker,
                originalPayloadMarker,
                replacementNoteMarker,
                replacementPayloadMarker,
                afterOperation,
                afterSessionClose,
                pressureObservation,
                afterForcedGc
            );
        }
    }

    private record ReachabilityState(
        boolean entityAlive,
        boolean loadedStateAlive,
        boolean oldNoteAlive,
        boolean oldPayloadAlive
    ) {
        boolean anyAlive() {
            return entityAlive || loadedStateAlive || oldNoteAlive || oldPayloadAlive;
        }
    }

    private record PressureObservation(
        boolean ran,
        int steps,
        long bytesAllocated,
        double elapsedMillis,
        ReachabilityState finalState
    ) {
        static PressureObservation notRun() {
            return new PressureObservation(false, 0, 0L, 0.0, new ReachabilityState(false, false, false, false));
        }
    }

    private record ScenarioMeasurement(
        String label,
        double averageMicros,
        double p50Micros,
        double p95Micros,
        long prepareStatementCount,
        long entityLoadCount
    ) {
    }

    private record TimingResult(
        ScenarioMeasurement managedHit,
        ScenarioMeasurement detachThenFind,
        ScenarioMeasurement clearThenFind
    ) {
    }
}
