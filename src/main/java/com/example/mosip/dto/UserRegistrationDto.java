package com.example.mosip.dto;

import org.springframework.web.multipart.MultipartFile;

public class UserRegistrationDto {
    private String name;
    private String individualId;
    private String givenName;
    private String familyName;
    private String gender;
    private String locality;
    private String region;
    private String country;
    private String pin;
    private String preferredLang;
    private String postalCode;
    private String phone;
    private String email;
    private String dob;
    private String nationality;
    private boolean consent;
    private String fatherName;
    private String motherName;
    private MultipartFile profileImage;
    private MultipartFile aadharCardImage;
    private MultipartFile documentImage;
    private String userId;
    private String uin;

    // Default Constructor
    public UserRegistrationDto() {
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIndividualId() {
        return individualId;
    }

    public void setIndividualId(String individualId) {
        this.individualId = individualId;
    }

    public String getGivenName() {
        return givenName;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getLocality() {
        return locality;
    }

    public void setLocality(String locality) {
        this.locality = locality;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    public String getPreferredLang() {
        return preferredLang;
    }

    public void setPreferredLang(String preferredLang) {
        this.preferredLang = preferredLang;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDob() {
        return dob;
    }

    public void setDob(String dob) {
        this.dob = dob;
    }

    public String getNationality() {
        return nationality;
    }

    public void setNationality(String nationality) {
        this.nationality = nationality;
    }

    public boolean isConsent() {
        return consent;
    }

    public void setConsent(boolean consent) {
        this.consent = consent;
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

    public MultipartFile getProfileImage() {
        return profileImage;
    }

    public void setProfileImage(MultipartFile profileImage) {
        this.profileImage = profileImage;
    }

    public MultipartFile getAadharCardImage() {
        return aadharCardImage;
    }

    public void setAadharCardImage(MultipartFile aadharCardImage) {
        this.aadharCardImage = aadharCardImage;
    }

    public MultipartFile getDocumentImage() {
        return documentImage;
    }

    public void setDocumentImage(MultipartFile documentImage) {
        this.documentImage = documentImage;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUin() {
        return uin;
    }

    public void setUin(String uin) {
        this.uin = uin;
    }

    @Override
    public String toString() {
        return "UserRegistrationDto{" +
                "name='" + name + '\'' +
                ", phone='" + phone + '\'' +
                ", email='" + email + '\'' +
                ", dob='" + dob + '\'' +
                ", nationality='" + nationality + '\'' +
                ", consent=" + consent +
                ", fatherName='" + fatherName + '\'' +
                ", motherName='" + motherName + '\'' +
                ", hasProfileImage=" + (profileImage != null && !profileImage.isEmpty()) +
                ", hasAadharCardImage=" + (aadharCardImage != null && !aadharCardImage.isEmpty()) +
                ", hasDocumentImage=" + (documentImage != null && !documentImage.isEmpty()) +
                ", userId='" + userId + '\'' +
                ", uin='" + uin + '\'' +
                '}';
    }
}
