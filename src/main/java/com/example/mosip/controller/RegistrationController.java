package com.example.mosip.controller;

import com.example.mosip.dto.UserRegistrationDto;
import com.example.mosip.service.MinioStorageService;
import com.example.mosip.service.MockIdentityService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class RegistrationController {

    private final MockIdentityService mockIdentityService;
    private final MinioStorageService minioStorageService;

    public RegistrationController(MockIdentityService mockIdentityService,
                                  MinioStorageService minioStorageService) {
        this.mockIdentityService = mockIdentityService;
        this.minioStorageService = minioStorageService;
    }

    @GetMapping("/")
    public String showHomePage() {
        return "home";
    }

    @GetMapping("/register")
    public String showRegistrationForm(Model model) {
        UserRegistrationDto registration = new UserRegistrationDto();
        registration.setPreferredLang("en");
        model.addAttribute("registration", registration);
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@ModelAttribute("registration") UserRegistrationDto registration, Model model) {
        registration.setName((registration.getGivenName() + " " + registration.getFamilyName()).trim());
        registration.setUin(registration.getIndividualId());
        registration.setUserId(registration.getIndividualId());

        try {
            mockIdentityService.createIdentity(registration);

            // Upload profile picture to MinIO storage if present
            if (registration.getProfileImage() != null && !registration.getProfileImage().isEmpty()) {
                try {
                    minioStorageService.uploadProfileImage(registration.getProfileImage(), registration.getIndividualId());
                } catch (Exception minioEx) {
                    System.err.println("MinIO profile image upload warning for user '" + registration.getIndividualId() + "': " + minioEx.getMessage());
                }
            }

            model.addAttribute("user", registration);
            return "success";
        } catch (MockIdentityService.MockIdentityServiceException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("registration", registration);
            return "register";
        }
    }
}
