package com.example.mosip.controller;

import com.example.mosip.dto.UserRegistrationDto;
import com.example.mosip.entity.basic.UserBasicDetails;
import com.example.mosip.entity.basic.UserDataLocation;
import com.example.mosip.entity.hashing.UserUinHash;
import com.example.mosip.entity.parent.UserParentDetails;
import com.example.mosip.repository.basic.UserBasicDetailsRepository;
import com.example.mosip.repository.basic.UserDataLocationRepository;
import com.example.mosip.repository.hashing.UserUinHashRepository;
import com.example.mosip.repository.parent.UserParentDetailsRepository;
import com.example.mosip.service.MockIdentityService;
import com.example.mosip.service.SaltModuloHashService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class RegistrationController {

    private final MockIdentityService mockIdentityService;
    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;
    private final UserParentDetailsRepository userParentDetailsRepository;
    private final UserDataLocationRepository userDataLocationRepository;
    private final SaltModuloHashService saltModuloHashService;

    public RegistrationController(MockIdentityService mockIdentityService,
                                  UserBasicDetailsRepository userBasicDetailsRepository,
                                  UserUinHashRepository userUinHashRepository,
                                  UserParentDetailsRepository userParentDetailsRepository,
                                  UserDataLocationRepository userDataLocationRepository,
                                  SaltModuloHashService saltModuloHashService) {
        this.mockIdentityService = mockIdentityService;
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userParentDetailsRepository = userParentDetailsRepository;
        this.userDataLocationRepository = userDataLocationRepository;
        this.saltModuloHashService = saltModuloHashService;
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

            String id = registration.getIndividualId();
            if (id != null && !id.trim().isEmpty()) {
                id = id.trim();
                String phone = registration.getPhone() != null && !registration.getPhone().isEmpty() ? registration.getPhone() : "9999999999";
                UserBasicDetails basicDetails = new UserBasicDetails(id, registration.getName(), phone);
                userBasicDetailsRepository.save(basicDetails);

                String hashedUin = saltModuloHashService.hash(id);
                UserUinHash uinHash = new UserUinHash(id, hashedUin, hashedUin);
                userUinHashRepository.save(uinHash);

                UserParentDetails parentDetails = new UserParentDetails(id, "Father of " + registration.getName(), "Mother of " + registration.getName());
                userParentDetailsRepository.save(parentDetails);

                UserDataLocation location = new UserDataLocation(id, hashedUin, true, true, true, false);
                userDataLocationRepository.save(location);
            }

            model.addAttribute("user", registration);
            return "success";
        } catch (Exception ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("registration", registration);
            return "register";
        }
    }
}
