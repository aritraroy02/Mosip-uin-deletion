package com.example.mosip.controller;

import com.example.mosip.dto.UserRegistrationDto;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.example.mosip.entity.basic.UserBasicDetails;
import com.example.mosip.repository.basic.UserBasicDetailsRepository;
import com.example.mosip.entity.hashing.UserUinHash;
import com.example.mosip.repository.hashing.UserUinHashRepository;
import com.example.mosip.entity.basic.UserDataLocation;
import com.example.mosip.repository.basic.UserDataLocationRepository;
import com.example.mosip.controller.api.RegistrationApiController;
import com.example.mosip.service.MinioStorageService;
import com.example.mosip.service.SaltModuloHashService;
import com.example.mosip.enums.ImageType;

@Controller
public class RegistrationController {

    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;
    private final UserDataLocationRepository userDataLocationRepository;
    private final MinioStorageService minioStorageService;
    private final RegistrationApiController registrationApiController;
    private final SaltModuloHashService saltModuloHashService;

    public RegistrationController(UserBasicDetailsRepository userBasicDetailsRepository,
                                  UserUinHashRepository userUinHashRepository,
                                  UserDataLocationRepository userDataLocationRepository,
                                  MinioStorageService minioStorageService,
                                  RegistrationApiController registrationApiController,
                                  SaltModuloHashService saltModuloHashService) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userDataLocationRepository = userDataLocationRepository;
        this.minioStorageService = minioStorageService;
        this.registrationApiController = registrationApiController;
        this.saltModuloHashService = saltModuloHashService;
    }

    @GetMapping("/")
    public String showHomePage() {
        return "home";
    }

    @GetMapping("/register")
    public String showRegistrationForm(Model model) {
        model.addAttribute("registration", new UserRegistrationDto());
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@ModelAttribute("registration") UserRegistrationDto registration, Model model) {
        // Generate a clean, unique User ID (USR-XXXXXXXX)
        String uniqueId = "USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        registration.setUserId(uniqueId);

        // Generate a 10-digit UIN (from 1,000,000,000 to 9,999,999,999)
        long randomUin = ThreadLocalRandom.current().nextLong(1000000000L, 10000000000L);
        registration.setUin(String.valueOf(randomUin));

        System.out.println("Processing registration for: " + registration.toString());

        // Track per-store write outcomes for the data-location registry
        boolean basicSaved = false;
        boolean hashSaved = false;
        boolean parentSaved = false;
        boolean minioSaved = false;

        // Save Basic Details to PostgreSQL basic database (defaultdb)
        UserBasicDetails basicDetails = new UserBasicDetails(
                registration.getUserId(),
                registration.getName(),
                registration.getPhone()
        );
        try {
            userBasicDetailsRepository.save(basicDetails);
            basicSaved = true;
            System.out.println("Successfully saved basic details to database: " + basicDetails);
        } catch (Exception e) {
            System.err.println("Failed to save user basic details to database: " + e.getMessage());
            model.addAttribute("databaseError", "Profile saved in-memory, but database write failed.");
        }

        // Save salt-modulo UIN hashes to PostgreSQL hashing database (uin-hashing)
        String uinSaltedHash = saltModuloHashService.hash(registration.getUin());
        String individualIdHash = saltModuloHashService.hash(registration.getUserId());
        UserUinHash uinHash = new UserUinHash(registration.getUserId(), individualIdHash, uinSaltedHash);
        try {
            userUinHashRepository.save(uinHash);
            hashSaved = true;
            System.out.println("Successfully saved UIN hash to database: " + uinHash);
        } catch (Exception e) {
            System.err.println("Failed to save UIN hash to database: " + e.getMessage());
            model.addAttribute("hashingDatabaseError", "Profile saved, but UIN hashing write failed.");
        }

        // Save father/mother names via the REST API controller (single owner of
        // the parent-details database write) so all parent data flows through the API.
        try {
            registrationApiController.saveParentDetails(
                    registration.getUserId(),
                    registration.getFatherName(),
                    registration.getMotherName());
            parentSaved = true;
            System.out.println("Successfully saved parent details to database for user: " + registration.getUserId());
        } catch (Exception e) {
            System.err.println("Failed to save parent details to database: " + e.getMessage());
            model.addAttribute("parentDatabaseError", "Profile saved, but parent details write failed.");
        }
        // Upload Profile Picture, Aadhar Card, and Document images to MinIO in structured subfolders
        if (registration.getProfileImage() != null && !registration.getProfileImage().isEmpty()) {
            try {
                String objectName = minioStorageService.uploadImage(
                        registration.getProfileImage(), registration.getUserId(), ImageType.PROFILE_PICTURE);
                minioSaved = true;
                System.out.println("Uploaded profile image to MinIO: " + objectName);
                model.addAttribute("profileImageObject", objectName);
                model.addAttribute("profileImageBase64", minioStorageService.getPresignedUrl(objectName));
            } catch (Exception e) {
                System.err.println("Error uploading profile image: " + e.getMessage());
                model.addAttribute("imageError", "Could not store profile image: " + e.getMessage());
            }
        }

        if (registration.getAadharCardImage() != null && !registration.getAadharCardImage().isEmpty()) {
            try {
                String objectName = minioStorageService.uploadImage(
                        registration.getAadharCardImage(), registration.getUserId(), ImageType.AADHAR_CARD);
                minioSaved = true;
                System.out.println("Uploaded Aadhar card image to MinIO: " + objectName);
                model.addAttribute("aadharImageObject", objectName);
                model.addAttribute("aadharImageBase64", minioStorageService.getPresignedUrl(objectName));
            } catch (Exception e) {
                System.err.println("Error uploading Aadhar card image: " + e.getMessage());
            }
        }

        if (registration.getDocumentImage() != null && !registration.getDocumentImage().isEmpty()) {
            try {
                String objectName = minioStorageService.uploadImage(
                        registration.getDocumentImage(), registration.getUserId(), ImageType.DOCUMENT);
                minioSaved = true;
                System.out.println("Uploaded document image to MinIO: " + objectName);
                model.addAttribute("documentImageObject", objectName);
                model.addAttribute("documentImageBase64", minioStorageService.getPresignedUrl(objectName));
            } catch (Exception e) {
                System.err.println("Error uploading document image: " + e.getMessage());
            }
        }

        // Record which stores this user's data was written to (data-location registry).
        // This record survives store outages so the deletion flow knows exactly which
        // stores to check and can report FAILED rather than silently skipping.
        try {
            UserDataLocation location = new UserDataLocation(
                    registration.getUserId(), uinSaltedHash,
                    basicSaved, parentSaved, hashSaved, minioSaved);
            userDataLocationRepository.save(location);
            System.out.println("Saved data-location registry: " + location);
        } catch (Exception e) {
            System.err.println("Failed to save data-location registry: " + e.getMessage());
        }

        // Pass details to the success view for summary
        model.addAttribute("user", registration);
        return "success";
    }
}
