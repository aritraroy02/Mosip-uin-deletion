package com.example.mosip.entity.hashing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_uin_hash")
public class UserUinHash {

    @Id
    @Column(name = "user_id")
    private String userId;

    /** Salt-modulo hash of the individual ID (Credential / IDA token). */
    @Column(name = "individual_id_hash", nullable = false)
    private String individualIdHash;

    /** Salt-modulo hash of the UIN. */
    @Column(name = "uin_salted_hash", nullable = false)
    private String uinSaltedHash;

    // Default constructor
    public UserUinHash() {
    }

    // Argument constructor
    public UserUinHash(String userId, String individualIdHash, String uinSaltedHash) {
        this.userId = userId;
        this.individualIdHash = individualIdHash;
        this.uinSaltedHash = uinSaltedHash;
    }

    // Getters and Setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getIndividualIdHash() {
        return individualIdHash;
    }

    public void setIndividualIdHash(String individualIdHash) {
        this.individualIdHash = individualIdHash;
    }

    public String getUinSaltedHash() {
        return uinSaltedHash;
    }

    public void setUinSaltedHash(String uinSaltedHash) {
        this.uinSaltedHash = uinSaltedHash;
    }

    @Override
    public String toString() {
        return "UserUinHash{" +
                "userId='" + userId + '\'' +
                ", individualIdHash='" + individualIdHash + '\'' +
                ", uinSaltedHash='" + uinSaltedHash + '\'' +
                '}';
    }
}
