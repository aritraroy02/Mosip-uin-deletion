package com.example.mosip.entity.hashing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Salt store for MOSIP-style salt-modulo hashing.
 * <p>
 * Each row is a salt bucket keyed by {@code id mod modulo}. The salt for a given
 * identifier is selected by computing its modulo bucket, then the stored hash is
 * {@code SHA-256(id + salt)}. Buckets are seeded once on application startup.
 */
@Entity
@Table(name = "uin_hash_salt")
public class UinHashSalt {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "salt", nullable = false)
    private String salt;

    public UinHashSalt() {
    }

    public UinHashSalt(Long id, String salt) {
        this.id = id;
        this.salt = salt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }
}
