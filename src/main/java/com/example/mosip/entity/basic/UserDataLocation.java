package com.example.mosip.entity.basic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Data-location registry: records which stores a given user's data was written to at
 * registration time. This lets the deletion flow know which stores to <em>expect</em>
 * data in, even when one of those stores is offline — so an unreachable database can be
 * reported as a failed deletion rather than being silently missed.
 * <p>
 * Stored in the primary "basic" database so it stays readable independently of the
 * hashing / parent databases and the MinIO object store.
 */
@Entity
@Table(name = "user_data_location")
public class UserDataLocation {

    @Id
    @Column(name = "user_id")
    private String userId;

    /** Salt-modulo hash of the UIN, so an audit trail can be located by UIN without storing it in the clear. */
    @Column(name = "uin_salted_hash")
    private String uinSaltedHash;

    /** Demographic details written to the basic database (user_basic_details). */
    @Column(name = "has_basic", nullable = false)
    private boolean hasBasic;

    /** Parent details written to the parent database (user_parent_details). */
    @Column(name = "has_parent", nullable = false)
    private boolean hasParent;

    /** Cryptographic UIN hash written to the hashing database (user_uin_hash). */
    @Column(name = "has_hash", nullable = false)
    private boolean hasHash;

    /** Profile image written to the MinIO object store. */
    @Column(name = "has_minio", nullable = false)
    private boolean hasMinio;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    public UserDataLocation() {
    }

    public UserDataLocation(String userId, String uinSaltedHash,
                            boolean hasBasic, boolean hasParent, boolean hasHash, boolean hasMinio) {
        this.userId = userId;
        this.uinSaltedHash = uinSaltedHash;
        this.hasBasic = hasBasic;
        this.hasParent = hasParent;
        this.hasHash = hasHash;
        this.hasMinio = hasMinio;
        this.registeredAt = Instant.now();
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

    public boolean isHasBasic() {
        return hasBasic;
    }

    public void setHasBasic(boolean hasBasic) {
        this.hasBasic = hasBasic;
    }

    public boolean isHasParent() {
        return hasParent;
    }

    public void setHasParent(boolean hasParent) {
        this.hasParent = hasParent;
    }

    public boolean isHasHash() {
        return hasHash;
    }

    public void setHasHash(boolean hasHash) {
        this.hasHash = hasHash;
    }

    public boolean isHasMinio() {
        return hasMinio;
    }

    public void setHasMinio(boolean hasMinio) {
        this.hasMinio = hasMinio;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    @Override
    public String toString() {
        return "UserDataLocation{" +
                "userId='" + userId + '\'' +
                ", hasBasic=" + hasBasic +
                ", hasParent=" + hasParent +
                ", hasHash=" + hasHash +
                ", hasMinio=" + hasMinio +
                '}';
    }
}
