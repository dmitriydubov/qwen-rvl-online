package ru.projectx.contracts.entity.document;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import ru.projectx.contracts.entity.party.PersonOwner;

@Entity
@Table(name = "contract_documents")
public class PrintedForm {

    private static final int FIRST_VERSION = 1;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Nullable
    @Column(name = "contract_id", updatable = false)
    private UUID contractId;

    @Nullable
    @Column(name = "lm_policy_id", updatable = false)
    private UUID lmPolicyId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, updatable = false)
    private PrintedFormKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "doc_type", nullable = false, updatable = false)
    private PrintedFormType docType;

    @Column(nullable = false, updatable = false)
    private int version;

    @Nullable
    @Column(name = "file_key", updatable = false)
    private String fileKey;

    @Nullable
    @Column(name = "file_hash", updatable = false)
    private String fileHash;

    @Nullable
    @Column(name = "file_size", updatable = false)
    private Long fileSize;

    @Nullable
    @Column(name = "content_type", updatable = false)
    private String contentType;

    @Nullable
    @Column(name = "usk_file_url", updatable = false)
    private String uskFileUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Nullable
    @Embedded
    private DocumentAttachment attachment;

    protected PrintedForm() {
    }

    private PrintedForm(PersonOwner owner, UskDocument document, Instant now) {
        this.contractId = owner.contractId();
        this.lmPolicyId = owner.lmPolicyId();
        this.kind = PrintedFormKind.FINAL;
        this.docType = document.type();
        this.version = FIRST_VERSION;
        this.fileSize = document.fileSize();
        this.uskFileUrl = document.fileUrl();
        this.createdAt = now;
    }

    private PrintedForm(PrintedFormType docType, DocumentAttachment attachment, StoredFile file, Instant now) {
        this.attachment = attachment;
        this.kind = PrintedFormKind.FINAL;
        this.docType = docType;
        this.version = FIRST_VERSION;
        this.fileKey = file.key();
        this.fileHash = file.hash();
        this.fileSize = file.size();
        this.contentType = file.contentType();
        this.createdAt = now;
    }

    public static PrintedForm issuedFor(PersonOwner owner, UskDocument document, Instant now) {
        return new PrintedForm(owner, document, now);
    }

    public static PrintedForm receivedAttachment(PrintedFormType docType, DocumentAttachment attachment,
            StoredFile file, Instant now) {
        return new PrintedForm(docType, attachment, file, now);
    }

    public UUID id() {
        return id;
    }

    public Optional<UUID> contractId() {
        return Optional.ofNullable(contractId);
    }

    public Optional<UUID> lmPolicyId() {
        return Optional.ofNullable(lmPolicyId);
    }

    public PrintedFormKind kind() {
        return kind;
    }

    public PrintedFormType docType() {
        return docType;
    }

    public Optional<String> uskFileUrl() {
        return Optional.ofNullable(uskFileUrl);
    }

    public Optional<DocumentAttachment> attachment() {
        return Optional.ofNullable(attachment);
    }

    public Optional<StoredFile> storedFile() {
        return Optional.ofNullable(fileKey).map(key -> new StoredFile(key, Objects.requireNonNull(fileHash),
                Objects.requireNonNull(fileSize), Objects.requireNonNull(contentType)));
    }

    public Instant createdAt() {
        return createdAt;
    }

    public int version() {
        return version;
    }
}
