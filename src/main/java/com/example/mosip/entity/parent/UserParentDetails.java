package com.example.mosip.entity.parent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_parent_details")
public class UserParentDetails {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "father_name", nullable = false)
    private String fatherName;

    @Column(name = "mother_name", nullable = false)
    private String motherName;

    // Default constructor
    public UserParentDetails() {
    }

    // Argument constructor
    public UserParentDetails(String userId, String fatherName, String motherName) {
        this.userId = userId;
        this.fatherName = fatherName;
        this.motherName = motherName;
    }

    // Getters and Setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFatherName() {
        return fatherName;
    }

    public void setFatherName(String fatherName) {
        this.fatherName = fatherName;
    }

    public String getMotherName() {
        return motherName;
    }

    public void setMotherName(String motherName) {
        this.motherName = motherName;
    }

    @Override
    public String toString() {
        return "UserParentDetails{" +
                "userId='" + userId + '\'' +
                ", fatherName='" + fatherName + '\'' +
                ", motherName='" + motherName + '\'' +
                '}';
    }
}
