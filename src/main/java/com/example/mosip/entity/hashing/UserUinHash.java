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

    @Column(name = "hashed_uin", nullable = false)
    private String hashedUin;

    // Default constructor
    public UserUinHash() {
    }

    // Argument constructor
    public UserUinHash(String userId, String hashedUin) {
        this.userId = userId;
        this.hashedUin = hashedUin;
    }

    // Getters and Setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getHashedUin() {
        return hashedUin;
    }

    public void setHashedUin(String hashedUin) {
        this.hashedUin = hashedUin;
    }

    @Override
    public String toString() {
        return "UserUinHash{" +
                "userId='" + userId + '\'' +
                ", hashedUin='" + hashedUin + '\'' +
                '}';
    }
}
