package research.jpa;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "graph_root")
public class GraphRoot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String title;

    @Lob
    @Column(nullable = false)
    private byte[] payload;

    @OneToMany(mappedBy = "root", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<GraphChild> children = new ArrayList<>();

    protected GraphRoot() {
    }

    public GraphRoot(String title, byte[] payload) {
        this.title = title;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public List<GraphChild> getChildren() {
        return children;
    }

    public void addChild(GraphChild child) {
        children.add(child);
        child.setRoot(this);
    }
}
