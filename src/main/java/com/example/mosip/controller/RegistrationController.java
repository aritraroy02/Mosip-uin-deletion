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
import com.example.mosip.service.EsignetAuthService;
import jakarta.servlet.http.HttpSession;
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
    private final EsignetAuthService esignetAuthService;

    public RegistrationController(MockIdentityService mockIdentityService,
                                  UserBasicDetailsRepository userBasicDetailsRepository,
                                  UserUinHashRepository userUinHashRepository,
                                  UserParentDetailsRepository userParentDetailsRepository,
                                  UserDataLocationRepository userDataLocationRepository,
                                  SaltModuloHashService saltModuloHashService,
                                  EsignetAuthService esignetAuthService) {
        this.mockIdentityService = mockIdentityService;
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userParentDetailsRepository = userParentDetailsRepository;
        this.userDataLocationRepository = userDataLocationRepository;
        this.saltModuloHashService = saltModuloHashService;
        this.esignetAuthService = esignetAuthService;
    }

    @GetMapping("/")
    public String showHomePage() {
        return "home";
    }

    @GetMapping("/register")
    public String showRegistrationForm(Model model, HttpSession session) {
        UserRegistrationDto registration = new UserRegistrationDto();
        registration.setPreferredLang("en");
        model.addAttribute("registration", registration);
        model.addAttribute("esignetVerified", session.getAttribute("pending_esignet_sub") != null);
        return "register";
    }

    /** Starts the "Sign up with eSignet" unified-login entry point. */
    @GetMapping("/register/esignet-login")
    public String esignetLogin(HttpSession session) {
        return "redirect:" + esignetAuthService.buildAuthorizeRedirectUrl(session, "register");
    }

    @PostMapping("/register")
    public String registerUser(@ModelAttribute("registration") UserRegistrationDto registration, Model model,
            HttpSession session) {
        registration.setName((registration.getGivenName() + " " + registration.getFamilyName()).trim());
        registration.setUin(registration.getIndividualId());
        registration.setUserId(registration.getIndividualId());

        String id = registration.getIndividualId();
        String pendingEsignetSub = (String) session.getAttribute("pending_esignet_sub");
        String pairwiseSub = id != null && !id.trim().isEmpty()
                ? esignetAuthService.computePairwiseSubject(id.trim())
                : null;

        if (pendingEsignetSub != null && !pendingEsignetSub.equals(pairwiseSub)) {
            model.addAttribute("errorMessage",
                    "The Individual ID you entered doesn't match your eSignet-verified identity. Please enter the ID you signed in with.");
            model.addAttribute("registration", registration);
            model.addAttribute("esignetVerified", true);
            return "register";
        }

        try {
            mockIdentityService.createIdentity(registration);

            if (id != null && !id.trim().isEmpty()) {
                id = id.trim();
                String phone = registration.getPhone() != null && !registration.getPhone().isEmpty() ? registration.getPhone() : "9999999999";
                UserBasicDetails basicDetails = new UserBasicDetails(id, registration.getName(), phone);
                basicDetails.setPairwiseSub(pairwiseSub);
                userBasicDetailsRepository.save(basicDetails);

                String hashedUin = saltModuloHashService.hash(id);
                UserUinHash uinHash = new UserUinHash(id, hashedUin, hashedUin);
                userUinHashRepository.save(uinHash);

                UserParentDetails parentDetails = new UserParentDetails(id, "Father of " + registration.getName(), "Mother of " + registration.getName());
                userParentDetailsRepository.save(parentDetails);

                UserDataLocation location = new UserDataLocation(id, hashedUin, true, true, true, false);
                userDataLocationRepository.save(location);
            }

            session.removeAttribute("pending_esignet_sub");
            model.addAttribute("user", registration);
            return "success";
        } catch (Exception ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("registration", registration);
            model.addAttribute("esignetVerified", pendingEsignetSub != null);
            return "register";
        }
    }
}
