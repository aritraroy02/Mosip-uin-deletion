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
import com.example.mosip.service.MinioStorageService;
import com.example.mosip.util.HashUtils;

@Controller
public class RegistrationController {

    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;
    private final MinioStorageService minioStorageService;

    public RegistrationController(UserBasicDetailsRepository userBasicDetailsRepository,
                                  UserUinHashRepository userUinHashRepository,
                                  MinioStorageService minioStorageService) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.minioStorageService = minioStorageService;
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

        // Save Basic Details to PostgreSQL basic database (defaultdb)
        UserBasicDetails basicDetails = new UserBasicDetails(
                registration.getUserId(),
                registration.getName(),
                registration.getPhone()
        );
        try {
            userBasicDetailsRepository.save(basicDetails);
            System.out.println("Successfully saved basic details to database: " + basicDetails);
        } catch (Exception e) {
            System.err.println("Failed to save user basic details to database: " + e.getMessage());
            model.addAttribute("databaseError", "Profile saved in-memory, but database write failed.");
        }

        // Save Hashed UIN to PostgreSQL hashing database (uin-hashing)
        String hashedUin = HashUtils.sha256(registration.getUin());
        UserUinHash uinHash = new UserUinHash(registration.getUserId(), hashedUin);
        try {
            userUinHashRepository.save(uinHash);
            System.out.println("Successfully saved UIN hash to database: " + uinHash);
        } catch (Exception e) {
            System.err.println("Failed to save UIN hash to database: " + e.getMessage());
            model.addAttribute("hashingDatabaseError", "Profile saved, but UIN hashing write failed.");
        }

        // Upload profile image to MinIO (bucket: userprofilepic) and show it on the success page
        if (registration.getProfileImage() != null && !registration.getProfileImage().isEmpty()) {
            try {
                String objectName = minioStorageService.uploadProfileImage(
                        registration.getProfileImage(), registration.getUserId());
                System.out.println("Uploaded profile image to MinIO: " + objectName);
                model.addAttribute("profileImageObject", objectName);
                model.addAttribute("profileImageBase64", minioStorageService.getPresignedUrl(objectName));
            } catch (Exception e) {
                System.err.println("Error uploading profile image to MinIO: " + e.getMessage());
                model.addAttribute("imageError", "Could not store uploaded image in object storage");
            }
        }

        // Pass details to the success view for summary
        model.addAttribute("user", registration);
        return "success";
    }

    @GetMapping("/delete")
    public String showDeleteForm() {
        return "delete";
    }

    @PostMapping("/delete")
    public String deleteUserData(@org.springframework.web.bind.annotation.RequestParam("userId") String userId,
                                 @org.springframework.web.bind.annotation.RequestParam(value = "confirmPurge", required = false) Boolean confirmPurge,
                                 Model model) {
        if (userId == null || userId.trim().isEmpty()) {
            model.addAttribute("errorMessage", "User ID is required.");
            return "delete";
        }
        if (confirmPurge == null || !confirmPurge) {
            model.addAttribute("errorMessage", "You must check the confirmation checkbox to proceed.");
            return "delete";
        }

        userId = userId.trim();
        boolean basicExists = userBasicDetailsRepository.existsById(userId);
        boolean hashExists = userUinHashRepository.existsById(userId);

        if (!basicExists && !hashExists) {
            model.addAttribute("errorMessage", "The User ID '" + userId + "' was not found in our registries.");
            return "delete";
        }

        try {
            if (basicExists) {
                userBasicDetailsRepository.deleteById(userId);
            }
            if (hashExists) {
                userUinHashRepository.deleteById(userId);
            }
            model.addAttribute("successMessage", "All demographic details and security hash archives for User ID '" + userId + "' have been successfully purged from all registries.");
        } catch (Exception e) {
            model.addAttribute("errorMessage", "An error occurred during database purging: " + e.getMessage());
        }

        return "delete";
    }
}
