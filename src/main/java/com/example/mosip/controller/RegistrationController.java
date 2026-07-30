package com.example.mosip.controller;

import com.example.mosip.dto.UserRegistrationDto;
import com.example.mosip.service.MockIdentityService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class RegistrationController {

    private final MockIdentityService mockIdentityService;

    public RegistrationController(MockIdentityService mockIdentityService) {
        this.mockIdentityService = mockIdentityService;
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
            model.addAttribute("user", registration);
            return "success";
        } catch (MockIdentityService.MockIdentityServiceException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("registration", registration);
            return "register";
        }
    }
}
