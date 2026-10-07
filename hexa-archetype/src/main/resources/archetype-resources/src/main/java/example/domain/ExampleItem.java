package ${package}.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Una voce dell'esempio. Il dominio conosce solo JDK e {@code jakarta.persistence}. */
@Entity
@Table(name = "example_item")
public class ExampleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** Nome opaco di un binario dello storage (o null): e' una colonna da dichiarare in {@code IBlobReferences}, altrimenti il backup lascia fuori il file. */
    @Column(name = "attachment_filename")
    private String attachmentFilename;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExampleItem() {
    }

    public ExampleItem(String title, String attachmentFilename) {
        this.title = title;
        this.attachmentFilename = attachmentFilename;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getAttachmentFilename() {
        return attachmentFilename;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
