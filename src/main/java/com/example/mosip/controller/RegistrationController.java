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
import com.example.mosip.controller.api.RegistrationApiController;
import com.example.mosip.service.MinioStorageService;
import com.example.mosip.service.SaltModuloHashService;

@Controller
public class RegistrationController {

    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;
    private final MinioStorageService minioStorageService;
    private final RegistrationApiController registrationApiController;
    private final SaltModuloHashService saltModuloHashService;

    public RegistrationController(UserBasicDetailsRepository userBasicDetailsRepository,
                                  UserUinHashRepository userUinHashRepository,
                                  MinioStorageService minioStorageService,
                                  RegistrationApiController registrationApiController,
                                  SaltModuloHashService saltModuloHashService) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
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

        // Save salt-modulo UIN hashes to PostgreSQL hashing database (uin-hashing)
        String uinSaltedHash = saltModuloHashService.hash(registration.getUin());
        String individualIdHash = saltModuloHashService.hash(registration.getUserId());
        UserUinHash uinHash = new UserUinHash(registration.getUserId(), individualIdHash, uinSaltedHash);
        try {
            userUinHashRepository.save(uinHash);
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
            System.out.println("Successfully saved parent details to database for user: " + registration.getUserId());
        } catch (Exception e) {
            System.err.println("Failed to save parent details to database: " + e.getMessage());
            model.addAttribute("parentDatabaseError", "Profile saved, but parent details write failed.");
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

    /**
     * Step 1: accept a UIN and "send" an OTP. Validates the UIN format, then confirms the UIN
     * exists in the hashing database (Database 2) before routing to the OTP verification page.
     * If the UIN is not found, the user is kept on the delete page with a "user does not exist"
     * message. (Demo-only: OTP delivery is not wired to an SMS/email provider — the fixed demo
     * OTP {@code 00000} is used on the next page.)
     */
    @PostMapping("/delete/send-otp")
    public String sendOtp(@org.springframework.web.bind.annotation.RequestParam("uin") String uin,
                          Model model) {
        if (uin == null || !uin.trim().matches("\\d{10,16}")) {
            model.addAttribute("errorMessage", "Enter a valid 10 to 16 digit UIN.");
            return "delete";
        }

        uin = uin.trim();
        model.addAttribute("uin", uin);

        // Only send an OTP if the UIN actually exists in the identity registry.
        try {
            String uinSaltedHash = saltModuloHashService.hash(uin);
            boolean exists = userUinHashRepository.existsByUinSaltedHash(uinSaltedHash);
            if (!exists) {
                model.addAttribute("errorMessage", "This UIN does not exist in the identity registry.");
                return "delete";
            }
        } catch (Exception e) {
            model.addAttribute("errorMessage", "An error occurred while checking the UIN: " + e.getMessage());
            return "delete";
        }

        // UIN exists — carry it forward to the OTP verification page.
        return "verify-otp";
    }

    /**
     * Step 2: verify the OTP, then check whether the UIN exists in the hashing database
     * (Database 2) by re-hashing it with the salt-modulo scheme and looking up the salted hash.
     */
    @PostMapping("/delete/verify-otp")
    public String verifyOtp(@org.springframework.web.bind.annotation.RequestParam("uin") String uin,
                            @org.springframework.web.bind.annotation.RequestParam("otp") String otp,
                            Model model) {
        model.addAttribute("uin", uin);

        if (uin == null || !uin.trim().matches("\\d{10,16}")) {
            model.addAttribute("errorMessage", "Enter a valid 10 to 16 digit UIN.");
            return "delete";
        }
        if (otp == null || !otp.trim().equals("00000")) {
            model.addAttribute("errorMessage", "The OTP is incorrect. For this demo, use 00000.");
            return "verify-otp";
        }

        uin = uin.trim();
        try {
            String uinSaltedHash = saltModuloHashService.hash(uin);
            boolean exists = userUinHashRepository.existsByUinSaltedHash(uinSaltedHash);
            if (exists) {
                model.addAttribute("successMessage", "OTP verified. This UIN exists in the identity registry.");
            } else {
                model.addAttribute("errorMessage", "OTP verified, but this UIN was not found in the identity registry.");
            }
        } catch (Exception e) {
            model.addAttribute("errorMessage", "An error occurred while checking the UIN: " + e.getMessage());
        }

        return "verify-otp";
    }
}
