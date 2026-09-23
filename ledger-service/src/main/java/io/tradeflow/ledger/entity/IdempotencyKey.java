package io.tradeflow.ledger.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * JPA entity representing a processed idempotency key.
 *
 * <p>Every mutating operation (reserve funds, credit account) inserts one row
 * here before executing. The {@code UNIQUE KEY uq_idempotency_keys_key} on the
 * {@code idempotency_key} column is the enforcement mechanism: a duplicate
 * INSERT fails at the database level before any application logic runs,
 * making duplicate detection atomic and race-condition-free.
 *
 * <p><b>Why store response_body:</b> duplicate requests must receive the
 * exact same response as the original — byte-for-byte identical. The
 * {@code LedgerEngine} detects the duplicate key exception, fetches this
 * row, and returns the stored response. The caller cannot distinguish a
 * duplicate from the original.
 *
 * <p><b>Why columnDefinition = "json":</b> MySQL 8's JSON column type
 * validates content on INSERT, rejecting malformed JSON at the database
 * level. The JPA type remains {@code String} — Hibernate passes the value
 * through as a string literal; MySQL validates and stores it as a binary
 * JSON document.
 *
 * <p><b>Why immutable after insert:</b> once an idempotency key is recorded,
 * it must never change — it is an audit record of what was executed and
 * what was returned. No setters are exposed.
 *
 * <p><b>Thread safety:</b> not thread-safe — JPA entities are request-scoped.
 *
 * <p><b>Spring context:</b> none — managed by the JPA persistence context.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 64)
    private LedgerOperation operation;

    @Column(name = "response_body", nullable = false, columnDefinition = "json")
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * No-arg constructor required by the JPA specification.
     *
     * <p><b>Why protected:</b> enforces that application code uses the
     * parameterised constructor, guaranteeing a valid and complete entity state.
     */
    protected IdempotencyKey() { }

    /**
     * Creates a new idempotency record for a completed operation.
     *
     * @param idempotencyKey  UUID from the client request; must not be null or blank
     * @param operation       the operation this key was recorded for; must not be null
     * @param responseBody    JSON-serialised original response; must be valid JSON
     */
    public IdempotencyKey(String idempotencyKey, LedgerOperation operation, String responseBody) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.responseBody = responseBody;
    }

    public Long getId() {
        return this.id;
    }

    public String getIdempotencyKey() {
        return this.idempotencyKey;
    }

    public LedgerOperation getOperation() {
        return this.operation;
    }

    public String getResponseBody() {
        return this.responseBody;
    }

    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }
}
