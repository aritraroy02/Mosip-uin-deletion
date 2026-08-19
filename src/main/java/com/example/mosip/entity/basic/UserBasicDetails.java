package com.example.mosip.entity.basic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_basic_details")
public class UserBasicDetails {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "phone", nullable = false)
    private String phone;

    // Pairwise eSignet subject (base64url SHA3-256 of individualId + relying
    // party id) computed at registration time, so a later eSignet login can be
    // matched back to this user without the mock IDA disclosing individual_id.
    @Column(name = "pairwise_sub")
    private String pairwiseSub;

    // Default constructor
    public UserBasicDetails() {
    }

    // Argument constructor
    public UserBasicDetails(String userId, String name, String phone) {
        this.userId = userId;
        this.name = name;
        this.phone = phone;
    }

    // Getters and Setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPairwiseSub() {
        return pairwiseSub;
    }

    public void setPairwiseSub(String pairwiseSub) {
        this.pairwiseSub = pairwiseSub;
    }

    @Override
    public String toString() {
        return "UserBasicDetails{" +
                "userId='" + userId + '\'' +
                ", name='" + name + '\'' +
                ", phone='" + phone + '\'' +
                '}';
    }
}
