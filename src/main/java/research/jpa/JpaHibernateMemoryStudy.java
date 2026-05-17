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

import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
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
            SeedData seedData = seed(sessionFactory);

            NoteRetentionResult detachBaseline = runNoteRetentionExperiment(
                sessionFactory,
                seedData.noteId(),
                NoteBoundary.DETACH,
                options,
                outputDir
            );
            NoteRetentionResult clearBaseline = runNoteRetentionExperiment(
                sessionFactory,
                seedData.noteId(),
                NoteBoundary.CLEAR,
                options,
                outputDir
            );

            List<GraphRetentionResult> graphResults = new ArrayList<>();
            for (GraphScenario scenario : GraphScenario.values()) {
                for (GraphBoundary boundary : GraphBoundary.values()) {
                    graphResults.add(runGraphRetentionExperiment(sessionFactory, seedData, scenario, boundary, options, outputDir));
                }
            }

            TimingResult timingResult = runTimingExperiment(sessionFactory, seedData.noteId());

            String report = buildReport(detachBaseline, clearBaseline, graphResults, timingResult, options);
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
            .addAnnotatedClass(GraphRoot.class)
            .addAnnotatedClass(GraphChild.class)
            .buildMetadata()
            .buildSessionFactory();
    }

    private static SeedData seed(SessionFactory sessionFactory) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            SecretNote note = new SecretNote(
                "owner-" + UUID.randomUUID(),
                markerString("db-note"),
                secretBytes(markerString("db-secret"))
            );
            session.persist(note);

            GraphRoot singleRoot = new GraphRoot(
                markerString("single-root-title"),
                secretBytes(markerString("single-root-payload"))
            );
            singleRoot.addChild(new GraphChild(
                markerString("single-child-name"),
                secretBytes(markerString("single-child-payload"))
            ));
            session.persist(singleRoot);

            GraphRoot multiRoot = new GraphRoot(
                markerString("multi-root-title"),
                secretBytes(markerString("multi-root-payload"))
            );
            multiRoot.addChild(new GraphChild(
                markerString("multi-child-a-name"),
                secretBytes(markerString("multi-child-a-payload"))
            ));
            multiRoot.addChild(new GraphChild(
                markerString("multi-child-b-name"),
                secretBytes(markerString("multi-child-b-payload"))
            ));
            multiRoot.addChild(new GraphChild(
                markerString("multi-child-c-name"),
                secretBytes(markerString("multi-child-c-payload"))
            ));
            session.persist(multiRoot);

            session.getTransaction().commit();
            return new SeedData(note.getId(), singleRoot.getId(), multiRoot.getId());
        }
    }

    private static NoteRetentionResult runNoteRetentionExperiment(
        SessionFactory sessionFactory,
        Long entityId,
        NoteBoundary boundary,
        StudyOptions options,
        Path outputDir
    ) throws Exception {
        NoteRetentionProbe probe = createNoteRetentionProbe(sessionFactory, entityId, boundary, options, outputDir);
        NoteReachabilityState afterSessionClose = snapshotNoteReachability(probe);
        NotePressureObservation pressureObservation = options.windowProbe()
            ? runNoteAllocationPressureWindow(probe)
            : NotePressureObservation.notRun();

        forceGc();

        if (options.heapDumps()) {
            dumpHeap(outputDir.resolve(boundary.filePrefix() + "-after-gc-live.hprof"), true);
        }
        if (options.nonLiveHeapDumps()) {
            dumpHeap(outputDir.resolve(boundary.filePrefix() + "-after-gc-all.hprof"), false);
        }

        NoteReachabilityState afterForcedGc = snapshotNoteReachability(probe);
        return probe.toResult(afterSessionClose, pressureObservation, afterForcedGc);
    }

    private static NoteRetentionProbe createNoteRetentionProbe(
        SessionFactory sessionFactory,
        Long entityId,
        NoteBoundary boundary,
        StudyOptions options,
        Path outputDir
    ) throws Exception {
        String mode = boundary.filePrefix();
        String replacementNoteMarker = markerString(mode + "-replacement-note");
        String replacementPayloadMarker = markerString(mode + "-replacement-payload");

        WeakReference<SecretNote> entityRef;
        WeakReference<Object[]> loadedStateRef;
        WeakReference<byte[]> oldPayloadRef;
        WeakReference<String> oldNoteRef;

        int managedEntriesBefore;
        int managedEntriesAfter;
        int payloadIndex;
        int noteIndex;
        NoteReachabilityState afterOperation;
        String originalNoteMarker;
        String originalPayloadMarker;

        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        try {
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

            entity.setNote(replacementNoteMarker);
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

            if (boundary == NoteBoundary.CLEAR) {
                session.clear();
            } else {
                session.detach(entity);
            }

            managedEntriesAfter = persistenceContext.getNumberOfManagedEntities();
            afterOperation = snapshotNoteReachability(entityRef, loadedStateRef, oldNoteRef, oldPayloadRef);

            if (options.heapDumps()) {
                dumpHeap(outputDir.resolve(mode + "-after-op-live.hprof"), true);
            }
            if (options.nonLiveHeapDumps()) {
                dumpHeap(outputDir.resolve(mode + "-after-op-all.hprof"), false);
            }

            tx.rollback();

            entity = null;
            loadedState = null;
            oldPayload = null;
            oldNote = null;
            entityEntry = null;
            persistenceContext = null;
            sessionImplementor = null;
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }

        return new NoteRetentionProbe(
            boundary,
            managedEntriesBefore,
            managedEntriesAfter,
            noteIndex,
            payloadIndex,
            originalNoteMarker,
            originalPayloadMarker,
            replacementNoteMarker,
            replacementPayloadMarker,
            afterOperation,
            entityRef,
            loadedStateRef,
            oldNoteRef,
            oldPayloadRef
        );
    }

    private static NotePressureObservation runNoteAllocationPressureWindow(NoteRetentionProbe probe) {
        long start = System.nanoTime();
        List<byte[]> pressure = new ArrayList<>();
        NoteReachabilityState lastState = snapshotNoteReachability(probe);
        int steps = 0;

        while (steps < PRESSURE_MAX_STEPS) {
            if (!lastState.anyAlive()) {
                break;
            }

            pressure.add(new byte[PRESSURE_STEP_BYTES]);
            steps++;
            lastState = snapshotNoteReachability(probe);
        }

        long elapsedNanos = System.nanoTime() - start;
        pressure.clear();

        return new NotePressureObservation(
            true,
            steps,
            PRESSURE_STEP_BYTES * steps,
            elapsedNanos / 1_000_000.0,
            lastState
        );
    }

    private static GraphRetentionResult runGraphRetentionExperiment(
        SessionFactory sessionFactory,
        SeedData seedData,
        GraphScenario scenario,
        GraphBoundary boundary,
        StudyOptions options,
        Path outputDir
    ) throws Exception {
        GraphRetentionProbe probe = createGraphRetentionProbe(sessionFactory, seedData, scenario, boundary, options, outputDir);
        GraphReachabilityState afterSessionClose = snapshotGraphReachability(probe);
        GraphPressureObservation pressureObservation = options.windowProbe()
            ? runGraphAllocationPressureWindow(probe)
            : GraphPressureObservation.notRun();

        forceGc();

        if (options.heapDumps()) {
            dumpHeap(outputDir.resolve(probe.filePrefix() + "-after-gc-live.hprof"), true);
        }
        if (options.nonLiveHeapDumps()) {
            dumpHeap(outputDir.resolve(probe.filePrefix() + "-after-gc-all.hprof"), false);
        }

        GraphReachabilityState afterForcedGc = snapshotGraphReachability(probe);
        return probe.toResult(afterSessionClose, pressureObservation, afterForcedGc);
    }

    private static GraphRetentionProbe createGraphRetentionProbe(
        SessionFactory sessionFactory,
        SeedData seedData,
        GraphScenario scenario,
        GraphBoundary boundary,
        StudyOptions options,
        Path outputDir
    ) throws Exception {
        String filePrefix = scenario.fileSlug + "-" + boundary.fileSlug;
        WeakReference<GraphRoot> rootRef;
        WeakReference<Object[]> rootLoadedStateRef;
        WeakReference<byte[]> originalRootPayloadRef;
        WeakReference<Object> childrenCollectionRef;
        List<WeakReference<GraphChild>> childEntityRefs = new ArrayList<>();
        List<WeakReference<Object[]>> childLoadedStateRefs = new ArrayList<>();
        List<WeakReference<byte[]>> originalChildPayloadRefs = new ArrayList<>();

        int rootPayloadIndex;
        int childPayloadIndex = -1;
        int managedEntriesBefore;
        int managedEntriesAfter;
        int initializedChildCount = 0;
        String rootOriginalPayloadMarker;
        List<String> childOriginalPayloadMarkers = new ArrayList<>();
        GraphReachabilityState afterOperation;

        Session session = sessionFactory.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Long rootId = scenario == GraphScenario.GRAPH_INITIALIZED_SINGLE
                ? seedData.singleGraphRootId()
                : seedData.multiGraphRootId();

            GraphRoot root = session.find(GraphRoot.class, rootId);
            SessionImplementor sessionImplementor = session.unwrap(SessionImplementor.class);
            PersistenceContext persistenceContext = sessionImplementor.getPersistenceContextInternal();
            EntityEntry rootEntry = persistenceContext.getEntry(root);
            Object[] rootLoadedState = rootEntry.getLoadedState();
            String[] rootProperties = rootEntry.getPersister().getPropertyNames();
            rootPayloadIndex = indexOf(rootProperties, "payload");
            byte[] originalRootPayload = (byte[]) rootLoadedState[rootPayloadIndex];
            rootOriginalPayloadMarker = extractMarker(originalRootPayload);

            rootRef = new WeakReference<>(root);
            rootLoadedStateRef = new WeakReference<>(rootLoadedState);
            originalRootPayloadRef = new WeakReference<>(originalRootPayload);
            childrenCollectionRef = new WeakReference<>(root.getChildren());
            managedEntriesBefore = persistenceContext.getNumberOfManagedEntities();

            root.setTitle(markerString(filePrefix + "-root-title"));
            root.setPayload(secretBytes(markerString(filePrefix + "-root-replacement-payload")));

            if (scenario.initializesChildren()) {
                Hibernate.initialize(root.getChildren());
                initializedChildCount = root.getChildren().size();

                for (int i = 0; i < root.getChildren().size(); i++) {
                    GraphChild child = root.getChildren().get(i);
                    EntityEntry childEntry = persistenceContext.getEntry(child);
                    Object[] childLoadedState = childEntry.getLoadedState();
                    String[] childProperties = childEntry.getPersister().getPropertyNames();
                    if (childPayloadIndex == -1) {
                        childPayloadIndex = indexOf(childProperties, "payload");
                    }
                    byte[] originalChildPayload = (byte[]) childLoadedState[childPayloadIndex];
                    childOriginalPayloadMarkers.add(extractMarker(originalChildPayload));

                    childEntityRefs.add(new WeakReference<>(child));
                    childLoadedStateRefs.add(new WeakReference<>(childLoadedState));
                    originalChildPayloadRefs.add(new WeakReference<>(originalChildPayload));

                    child.setName(markerString(filePrefix + "-child-" + i + "-name"));
                    child.setPayload(secretBytes(markerString(filePrefix + "-child-" + i + "-replacement-payload")));
                }
            }

            if (options.heapDumps()) {
                dumpHeap(outputDir.resolve(filePrefix + "-before-live.hprof"), true);
            }
            if (options.nonLiveHeapDumps()) {
                dumpHeap(outputDir.resolve(filePrefix + "-before-all.hprof"), false);
            }

            switch (boundary) {
                case DETACH -> session.detach(root);
                case CLEAR -> session.clear();
                case COMMIT -> tx.commit();
                case ROLLBACK -> tx.rollback();
                case CLOSE -> session.close();
            }

            managedEntriesAfter = session.isOpen() ? persistenceContext.getNumberOfManagedEntities() : 0;
            afterOperation = snapshotGraphReachability(
                rootRef,
                rootLoadedStateRef,
                originalRootPayloadRef,
                childrenCollectionRef,
                childEntityRefs,
                childLoadedStateRefs,
                originalChildPayloadRefs
            );

            if (options.heapDumps()) {
                dumpHeap(outputDir.resolve(filePrefix + "-after-op-live.hprof"), true);
            }
            if (options.nonLiveHeapDumps()) {
                dumpHeap(outputDir.resolve(filePrefix + "-after-op-all.hprof"), false);
            }

            if (boundary == GraphBoundary.DETACH || boundary == GraphBoundary.CLEAR) {
                tx.rollback();
            }
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }

        return new GraphRetentionProbe(
            scenario,
            boundary,
            filePrefix,
            managedEntriesBefore,
            managedEntriesAfter,
            initializedChildCount,
            rootPayloadIndex,
            childPayloadIndex,
            rootOriginalPayloadMarker,
            childOriginalPayloadMarkers,
            afterOperation,
            rootRef,
            rootLoadedStateRef,
            originalRootPayloadRef,
            childrenCollectionRef,
            childEntityRefs,
            childLoadedStateRefs,
            originalChildPayloadRefs
        );
    }

    private static GraphPressureObservation runGraphAllocationPressureWindow(GraphRetentionProbe probe) {
        long start = System.nanoTime();
        List<byte[]> pressure = new ArrayList<>();
        GraphReachabilityState lastState = snapshotGraphReachability(probe);
        int steps = 0;

        while (steps < PRESSURE_MAX_STEPS) {
            if (!lastState.anyAlive()) {
                break;
            }

            pressure.add(new byte[PRESSURE_STEP_BYTES]);
            steps++;
            lastState = snapshotGraphReachability(probe);
        }

        long elapsedNanos = System.nanoTime() - start;
        pressure.clear();

        return new GraphPressureObservation(
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

        ScenarioMeasurement managedHit = measureScenario(sessionFactory, statistics, entityId, TimingScenario.MANAGED_HIT);
        ScenarioMeasurement detachMiss = measureScenario(sessionFactory, statistics, entityId, TimingScenario.DETACH_THEN_FIND);
        ScenarioMeasurement clearMiss = measureScenario(sessionFactory, statistics, entityId, TimingScenario.CLEAR_THEN_FIND);

        return new TimingResult(managedHit, detachMiss, clearMiss);
    }

    private static ScenarioMeasurement measureScenario(
        SessionFactory sessionFactory,
        Statistics statistics,
        Long entityId,
        TimingScenario scenario
    ) {
        statistics.clear();
        List<Long> samples = new ArrayList<>(TIMING_ITERATIONS);

        try (Session session = sessionFactory.openSession()) {
            if (scenario == TimingScenario.MANAGED_HIT) {
                session.find(SecretNote.class, entityId);
            }

            for (int i = 0; i < TIMING_ITERATIONS; i++) {
                if (scenario == TimingScenario.DETACH_THEN_FIND) {
                    SecretNote entity = session.find(SecretNote.class, entityId);
                    session.detach(entity);
                } else if (scenario == TimingScenario.CLEAR_THEN_FIND) {
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
        NoteRetentionResult detachBaseline,
        NoteRetentionResult clearBaseline,
        List<GraphRetentionResult> graphResults,
        TimingResult timingResult,
        StudyOptions options
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# JPA/Hibernate Persistence-Context Memory Study\n\n");
        builder.append("Generated: ").append(Instant.now()).append("\n\n");

        builder.append("## Scope\n\n");
        builder.append("- Baseline retention question: after `detach()` or `clear()`, do Hibernate dirty-check snapshots or entity values remain reachable long enough to be recovered?\n");
        builder.append("- Phase 2 question: how much of an object graph remains reachable across `detach()`, `clear()`, `close()`, `commit()`, and `rollback()`?\n");
        builder.append("- Timing question: does first-level-cache membership create measurable latency differences?\n");
        builder.append("- Heap dump mode: live=").append(options.heapDumps()).append(", nonLive=").append(options.nonLiveHeapDumps()).append(", windowProbe=").append(options.windowProbe()).append("\n\n");

        builder.append("## Baseline Retention Results\n\n");
        appendNoteRetention(builder, detachBaseline);
        appendNoteRetention(builder, clearBaseline);

        builder.append("## Phase 2: Object Graph Retention Across Lifecycle Boundaries\n\n");
        builder.append("| Scenario | Boundary | Init children | managed before | managed after | after operation | after session close | after pressure | after forced GC |\n");
        builder.append("| --- | --- | ---: | ---: | ---: | --- | --- | --- | --- |\n");
        for (GraphRetentionResult result : graphResults) {
            builder.append("| ")
                .append(result.scenario().label).append(" | ")
                .append(result.boundary().label).append(" | ")
                .append(result.initializedChildCount()).append(" | ")
                .append(result.managedEntriesBefore()).append(" | ")
                .append(result.managedEntriesAfter()).append(" | ")
                .append(result.afterOperation().summary()).append(" | ")
                .append(result.afterSessionClose().summary()).append(" | ")
                .append(result.pressureObservation().finalState().summary()).append(" | ")
                .append(result.afterForcedGc().summary()).append(" |\n");
        }

        builder.append("\n## Timing Results\n\n");
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
        builder.append("- `detach()` and `clear()` are still lifecycle changes, not cleanup primitives.\n");
        builder.append("- The graph matrix answers the stronger question: whether traversing more of the object graph increases what remains recoverable before GC.\n");
        builder.append("- `close()`, `commit()`, and `rollback()` are now directly comparable against `detach()` and `clear()` within the same harness.\n");
        builder.append("- `managed-hit` versus detached/cleared timing remains a concrete membership oracle inside the same JVM and persistence-context boundary.\n");
        return builder.toString();
    }

    private static void appendNoteRetention(StringBuilder builder, NoteRetentionResult result) {
        builder.append("### `").append(result.boundary().label).append("`\n\n");
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
        appendNoteReachability(builder, "afterOperation", result.afterOperation());
        appendNoteReachability(builder, "afterSessionClose", result.afterSessionClose());
        builder.append("| pressureProbeRan | ").append(result.pressureObservation().ran()).append(" |\n");
        builder.append("| pressureSteps | ").append(result.pressureObservation().steps()).append(" |\n");
        builder.append("| pressureBytesAllocated | ").append(result.pressureObservation().bytesAllocated()).append(" |\n");
        builder.append("| pressureElapsedMs | ").append(format(result.pressureObservation().elapsedMillis())).append(" |\n");
        appendNoteReachability(builder, "afterPressure", result.pressureObservation().finalState());
        appendNoteReachability(builder, "afterForcedGc", result.afterForcedGc());
        builder.append("\n");
    }

    private static void appendNoteReachability(StringBuilder builder, String label, NoteReachabilityState state) {
        builder.append("| ").append(label).append(".entityAlive | ").append(state.entityAlive()).append(" |\n");
        builder.append("| ").append(label).append(".loadedStateAlive | ").append(state.loadedStateAlive()).append(" |\n");
        builder.append("| ").append(label).append(".oldNoteAlive | ").append(state.oldNoteAlive()).append(" |\n");
        builder.append("| ").append(label).append(".oldPayloadAlive | ").append(state.oldPayloadAlive()).append(" |\n");
    }

    private static NoteReachabilityState snapshotNoteReachability(NoteRetentionProbe probe) {
        return snapshotNoteReachability(
            probe.entityRef(),
            probe.loadedStateRef(),
            probe.oldNoteRef(),
            probe.oldPayloadRef()
        );
    }

    private static NoteReachabilityState snapshotNoteReachability(
        WeakReference<SecretNote> entityRef,
        WeakReference<Object[]> loadedStateRef,
        WeakReference<String> oldNoteRef,
        WeakReference<byte[]> oldPayloadRef
    ) {
        return new NoteReachabilityState(
            entityRef.get() != null,
            loadedStateRef.get() != null,
            oldNoteRef.get() != null,
            oldPayloadRef.get() != null
        );
    }

    private static GraphReachabilityState snapshotGraphReachability(GraphRetentionProbe probe) {
        return snapshotGraphReachability(
            probe.rootRef(),
            probe.rootLoadedStateRef(),
            probe.originalRootPayloadRef(),
            probe.childrenCollectionRef(),
            probe.childEntityRefs(),
            probe.childLoadedStateRefs(),
            probe.originalChildPayloadRefs()
        );
    }

    private static GraphReachabilityState snapshotGraphReachability(
        WeakReference<GraphRoot> rootRef,
        WeakReference<Object[]> rootLoadedStateRef,
        WeakReference<byte[]> originalRootPayloadRef,
        WeakReference<Object> childrenCollectionRef,
        List<WeakReference<GraphChild>> childEntityRefs,
        List<WeakReference<Object[]>> childLoadedStateRefs,
        List<WeakReference<byte[]>> originalChildPayloadRefs
    ) {
        return new GraphReachabilityState(
            rootRef.get() != null,
            rootLoadedStateRef.get() != null,
            originalRootPayloadRef.get() != null,
            childrenCollectionRef.get() != null,
            countAlive(childEntityRefs),
            childEntityRefs.size(),
            countAlive(childLoadedStateRefs),
            childLoadedStateRefs.size(),
            countAlive(originalChildPayloadRefs),
            originalChildPayloadRefs.size()
        );
    }

    private static int countAlive(List<? extends WeakReference<?>> refs) {
        int alive = 0;
        for (WeakReference<?> ref : refs) {
            if (ref.get() != null) {
                alive++;
            }
        }
        return alive;
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

    private enum NoteBoundary {
        DETACH("detach", "detach"),
        CLEAR("clear", "clear");

        private final String label;
        private final String filePrefix;

        NoteBoundary(String label, String filePrefix) {
            this.label = label;
            this.filePrefix = filePrefix;
        }

        String label() {
            return label;
        }

        String filePrefix() {
            return filePrefix;
        }
    }

    private enum GraphBoundary {
        DETACH("detach(entity)", "detach"),
        CLEAR("clear()", "clear"),
        CLOSE("session.close()", "close"),
        COMMIT("transaction.commit()", "commit"),
        ROLLBACK("transaction.rollback()", "rollback");

        private final String label;
        private final String fileSlug;

        GraphBoundary(String label, String fileSlug) {
            this.label = label;
            this.fileSlug = fileSlug;
        }
    }

    private enum GraphScenario {
        GRAPH_UNINITIALIZED_MULTI("multi-child graph, lazy uninitialized", "graph-uninitialized-multi", false),
        GRAPH_INITIALIZED_SINGLE("single-child graph, initialized", "graph-initialized-single", true),
        GRAPH_INITIALIZED_MULTI("multi-child graph, initialized", "graph-initialized-multi", true);

        private final String label;
        private final String fileSlug;
        private final boolean initializesChildren;

        GraphScenario(String label, String fileSlug, boolean initializesChildren) {
            this.label = label;
            this.fileSlug = fileSlug;
            this.initializesChildren = initializesChildren;
        }

        boolean initializesChildren() {
            return initializesChildren;
        }
    }

    private enum TimingScenario {
        MANAGED_HIT("managed-hit"),
        DETACH_THEN_FIND("detach-then-find"),
        CLEAR_THEN_FIND("clear-then-find");

        private final String label;

        TimingScenario(String label) {
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

    private record SeedData(
        Long noteId,
        Long singleGraphRootId,
        Long multiGraphRootId
    ) {
    }

    private record NoteRetentionResult(
        NoteBoundary boundary,
        int managedEntriesBefore,
        int managedEntriesAfter,
        int noteIndex,
        int payloadIndex,
        String originalNoteMarker,
        String originalPayloadMarker,
        String replacementNoteMarker,
        String replacementPayloadMarker,
        NoteReachabilityState afterOperation,
        NoteReachabilityState afterSessionClose,
        NotePressureObservation pressureObservation,
        NoteReachabilityState afterForcedGc
    ) {
    }

    private record NoteRetentionProbe(
        NoteBoundary boundary,
        int managedEntriesBefore,
        int managedEntriesAfter,
        int noteIndex,
        int payloadIndex,
        String originalNoteMarker,
        String originalPayloadMarker,
        String replacementNoteMarker,
        String replacementPayloadMarker,
        NoteReachabilityState afterOperation,
        WeakReference<SecretNote> entityRef,
        WeakReference<Object[]> loadedStateRef,
        WeakReference<String> oldNoteRef,
        WeakReference<byte[]> oldPayloadRef
    ) {
        NoteRetentionResult toResult(
            NoteReachabilityState afterSessionClose,
            NotePressureObservation pressureObservation,
            NoteReachabilityState afterForcedGc
        ) {
            return new NoteRetentionResult(
                boundary,
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

    private record NoteReachabilityState(
        boolean entityAlive,
        boolean loadedStateAlive,
        boolean oldNoteAlive,
        boolean oldPayloadAlive
    ) {
        boolean anyAlive() {
            return entityAlive || loadedStateAlive || oldNoteAlive || oldPayloadAlive;
        }
    }

    private record NotePressureObservation(
        boolean ran,
        int steps,
        long bytesAllocated,
        double elapsedMillis,
        NoteReachabilityState finalState
    ) {
        static NotePressureObservation notRun() {
            return new NotePressureObservation(false, 0, 0L, 0.0, new NoteReachabilityState(false, false, false, false));
        }
    }

    private record GraphRetentionResult(
        GraphScenario scenario,
        GraphBoundary boundary,
        int managedEntriesBefore,
        int managedEntriesAfter,
        int initializedChildCount,
        int rootPayloadIndex,
        int childPayloadIndex,
        String rootOriginalPayloadMarker,
        List<String> childOriginalPayloadMarkers,
        GraphReachabilityState afterOperation,
        GraphReachabilityState afterSessionClose,
        GraphPressureObservation pressureObservation,
        GraphReachabilityState afterForcedGc
    ) {
    }

    private record GraphRetentionProbe(
        GraphScenario scenario,
        GraphBoundary boundary,
        String filePrefix,
        int managedEntriesBefore,
        int managedEntriesAfter,
        int initializedChildCount,
        int rootPayloadIndex,
        int childPayloadIndex,
        String rootOriginalPayloadMarker,
        List<String> childOriginalPayloadMarkers,
        GraphReachabilityState afterOperation,
        WeakReference<GraphRoot> rootRef,
        WeakReference<Object[]> rootLoadedStateRef,
        WeakReference<byte[]> originalRootPayloadRef,
        WeakReference<Object> childrenCollectionRef,
        List<WeakReference<GraphChild>> childEntityRefs,
        List<WeakReference<Object[]>> childLoadedStateRefs,
        List<WeakReference<byte[]>> originalChildPayloadRefs
    ) {
        GraphRetentionResult toResult(
            GraphReachabilityState afterSessionClose,
            GraphPressureObservation pressureObservation,
            GraphReachabilityState afterForcedGc
        ) {
            return new GraphRetentionResult(
                scenario,
                boundary,
                managedEntriesBefore,
                managedEntriesAfter,
                initializedChildCount,
                rootPayloadIndex,
                childPayloadIndex,
                rootOriginalPayloadMarker,
                childOriginalPayloadMarkers,
                afterOperation,
                afterSessionClose,
                pressureObservation,
                afterForcedGc
            );
        }
    }

    private record GraphReachabilityState(
        boolean rootEntityAlive,
        boolean rootLoadedStateAlive,
        boolean rootOriginalPayloadAlive,
        boolean childrenCollectionAlive,
        int childEntitiesAlive,
        int childEntitiesTracked,
        int childLoadedStatesAlive,
        int childLoadedStatesTracked,
        int childOriginalPayloadsAlive,
        int childOriginalPayloadsTracked
    ) {
        boolean anyAlive() {
            return rootEntityAlive
                || rootLoadedStateAlive
                || rootOriginalPayloadAlive
                || childrenCollectionAlive
                || childEntitiesAlive > 0
                || childLoadedStatesAlive > 0
                || childOriginalPayloadsAlive > 0;
        }

        String summary() {
            return "root=" + flag(rootEntityAlive)
                + ", collection=" + flag(childrenCollectionAlive)
                + ", childEntities=" + childEntitiesAlive + "/" + childEntitiesTracked
                + ", childPayloads=" + childOriginalPayloadsAlive + "/" + childOriginalPayloadsTracked;
        }
    }

    private record GraphPressureObservation(
        boolean ran,
        int steps,
        long bytesAllocated,
        double elapsedMillis,
        GraphReachabilityState finalState
    ) {
        static GraphPressureObservation notRun() {
            return new GraphPressureObservation(false, 0, 0L, 0.0, new GraphReachabilityState(false, false, false, false, 0, 0, 0, 0, 0, 0));
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

    private static String flag(boolean value) {
        return value ? "Y" : "-";
    }
}
