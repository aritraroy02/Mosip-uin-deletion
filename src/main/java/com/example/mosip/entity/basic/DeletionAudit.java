package com.example.mosip.entity.basic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Audit record of a single UIN deletion attempt. One row is written per deletion,
 * capturing the per-store outcome so operators can see exactly which databases held a
 * user's data and whether each store was purged, empty, or failed (e.g. unreachable).
 * <p>
 * Stored in the primary "basic" database so the audit trail survives outages of the
 * hashing / parent databases and the MinIO object store.
 */
@Entity
@Table(name = "deletion_audit")
public class DeletionAudit {

    /** Per-store / overall outcome values used throughout the deletion flow and UI. */
    public static final String PURGED = "PURGED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_EXPECTED = "NOT_EXPECTED";
    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private String userId;

    /** Salt-modulo hash of the UIN this deletion targeted (UIN is never stored in the clear). */
    @Column(name = "uin_salted_hash")
    private String uinSaltedHash;

    /** SUCCESS (everything expected purged), PARTIAL (some stores failed), or FAILED. */
    @Column(name = "overall_status", nullable = false)
    private String overallStatus;

    @Column(name = "basic_status")
    private String basicStatus;

    @Column(name = "parent_status")
    private String parentStatus;

    @Column(name = "hash_status")
    private String hashStatus;

    @Column(name = "minio_status")
    private String minioStatus;

    /** Free-text detail (failure reasons, notes). */
    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    public DeletionAudit() {
    }

    public DeletionAudit(String userId, String uinSaltedHash) {
        this.userId = userId;
        this.uinSaltedHash = uinSaltedHash;
        this.attemptedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUinSaltedHash() {
        return uinSaltedHash;
    }

    public void setUinSaltedHash(String uinSaltedHash) {
        this.uinSaltedHash = uinSaltedHash;
    }

    public String getOverallStatus() {
        return overallStatus;
    }

    public void setOverallStatus(String overallStatus) {
        this.overallStatus = overallStatus;
    }

    public String getBasicStatus() {
        return basicStatus;
    }

    public void setBasicStatus(String basicStatus) {
        this.basicStatus = basicStatus;
    }

    public String getParentStatus() {
        return parentStatus;
    }

    public void setParentStatus(String parentStatus) {
        this.parentStatus = parentStatus;
    }

    public String getHashStatus() {
        return hashStatus;
    }

    public void setHashStatus(String hashStatus) {
        this.hashStatus = hashStatus;
    }

    public String getMinioStatus() {
        return minioStatus;
    }

    public void setMinioStatus(String minioStatus) {
        this.minioStatus = minioStatus;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(Instant attemptedAt) {
        this.attemptedAt = attemptedAt;
    }
}
