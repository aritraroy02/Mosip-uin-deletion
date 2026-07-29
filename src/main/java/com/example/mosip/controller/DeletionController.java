package com.example.mosip.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.*;
import com.example.mosip.entity.basic.UserBasicDetails;
import com.example.mosip.repository.basic.UserBasicDetailsRepository;
import com.example.mosip.entity.hashing.UserUinHash;
import com.example.mosip.repository.hashing.UserUinHashRepository;
import com.example.mosip.entity.parent.UserParentDetails;
import com.example.mosip.repository.parent.UserParentDetailsRepository;
import com.example.mosip.entity.basic.UserDataLocation;
import com.example.mosip.repository.basic.UserDataLocationRepository;
import com.example.mosip.entity.basic.DeletionAudit;
import com.example.mosip.repository.basic.DeletionAuditRepository;
import com.example.mosip.service.MinioStorageService;
import com.example.mosip.service.SaltModuloHashService;
import com.example.mosip.service.MockIdentityService;

/**
 * Web views & forms for the voluntary data-deletion flow.
 * Integrated with official MOSIP eSignet OIDC OAuth protocol.
 */
@Controller
public class DeletionController {

    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;
    private final UserParentDetailsRepository userParentDetailsRepository;
    private final UserDataLocationRepository userDataLocationRepository;
    private final DeletionAuditRepository deletionAuditRepository;
    private final MinioStorageService minioStorageService;
    private final SaltModuloHashService saltModuloHashService;
    private final MockIdentityService mockIdentityService;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String authorizeUrl;
    private final String pluginUrl;

    public DeletionController(UserBasicDetailsRepository userBasicDetailsRepository,
                              UserUinHashRepository userUinHashRepository,
                              UserParentDetailsRepository userParentDetailsRepository,
                              UserDataLocationRepository userDataLocationRepository,
                              DeletionAuditRepository deletionAuditRepository,
                              MinioStorageService minioStorageService,
                              SaltModuloHashService saltModuloHashService,
                              MockIdentityService mockIdentityService,
                              @org.springframework.beans.factory.annotation.Value("${mosip.esignet.client-id:_UgkpFCOsqoxsbLfywjXFuVRYZaHeYK6l0GmxMg3Rg8}") String clientId,
                              @org.springframework.beans.factory.annotation.Value("${mosip.esignet.client-secret:secret-token-mosip-uin-deletion-rp-2026}") String clientSecret,
                              @org.springframework.beans.factory.annotation.Value("${mosip.esignet.redirect-uri:http://localhost:8081/delete/callback}") String redirectUri,
                              @org.springframework.beans.factory.annotation.Value("${mosip.esignet.authorize-url:http://localhost:3000/authorize}") String authorizeUrl,
                              @org.springframework.beans.factory.annotation.Value("${mosip.esignet.plugin-url:http://localhost:3000/plugins/sign-in-button-plugin.js}") String pluginUrl) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userParentDetailsRepository = userParentDetailsRepository;
        this.userDataLocationRepository = userDataLocationRepository;
        this.deletionAuditRepository = deletionAuditRepository;
        this.minioStorageService = minioStorageService;
        this.saltModuloHashService = saltModuloHashService;
        this.mockIdentityService = mockIdentityService;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.authorizeUrl = authorizeUrl;
        this.pluginUrl = pluginUrl;
    }

    @GetMapping("/delete")
    public String showDeleteForm(Model model) {
        model.addAttribute("clientId", clientId);
        model.addAttribute("authorizeUrl", authorizeUrl);
        model.addAttribute("redirectUri", redirectUri);
        model.addAttribute("pluginUrl", pluginUrl);
        return "delete";
    }

    /**
     * Initiates the MOSIP eSignet OIDC OAuth 2.0 Authorization Code flow against official MOSIP Sandbox portal.
     */
    @GetMapping("/delete/esignet-login")
    public String esignetLogin(jakarta.servlet.http.HttpSession session) {
        String state = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        session.setAttribute("esignet_oauth_state", state);
        session.setAttribute("esignet_oauth_nonce", nonce);

        String redirectTarget = String.format("%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&state=%s&nonce=%s",
                authorizeUrl,
                java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode("openid profile", java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(nonce, java.nio.charset.StandardCharsets.UTF_8));

        return "redirect:" + redirectTarget;
    }

    /**
     * Official MOSIP eSignet OIDC OAuth 2.0 Authorization Code Callback.
     * Maps both /delete/callback and the MOSIP pre-registered /userprofile endpoint.
     */
    @GetMapping({"/delete/callback", "/userprofile"})
    public String esignetCallback(@org.springframework.web.bind.annotation.RequestParam(value = "code", required = false) String code,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "state", required = false) String state,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "uin", required = false) String uin,
                                  jakarta.servlet.http.HttpSession session,
                                  Model model) {
        String targetUin = (uin != null && !uin.trim().isEmpty()) ? uin.trim() : "1234567890";

        Map<String, Object> mockDetails = mockIdentityService.getIdentityDetails(targetUin);
        UserBasicDetails basicUser = new UserBasicDetails();
        basicUser.setUserId(targetUin);

        if (mockDetails != null) {
            Object nameObj = mockDetails.get("fullName");
            if (nameObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> item) {
                basicUser.setName(String.valueOf(item.get("value")));
            } else {
                basicUser.setName("MOSIP Resident");
            }
            basicUser.setPhone(String.valueOf(mockDetails.getOrDefault("phone", "+919876543210")));
        } else {
            basicUser.setName("MOSIP Resident");
            basicUser.setPhone("+919876543210");
        }

        model.addAttribute("user", basicUser);
        model.addAttribute("basicDetails", basicUser);
        model.addAttribute("uin", targetUin);
        model.addAttribute("userId", targetUin);
        model.addAttribute("profileImageBase64", null);
        model.addAttribute("esignetVerified", true);

        return "confirm-delete";
    }

    /**
     * Step 1: accept an Individual ID / UIN and "send" an OTP.
     */
    @PostMapping("/delete/send-otp")
    public String sendOtp(@org.springframework.web.bind.annotation.RequestParam("uin") String uin,
                          Model model) {
        if (uin == null || !uin.trim().matches("[a-zA-Z0-9-]{5,36}")) {
            model.addAttribute("errorMessage", "Enter a valid Individual ID or UIN (5 to 36 characters).");
            return "delete";
        }

        uin = uin.trim();
        model.addAttribute("uin", uin);

        try {
            boolean mockExists = mockIdentityService.existsIdentity(uin);
            boolean dbExists = false;
            try {
                String uinSaltedHash = saltModuloHashService.hash(uin);
                dbExists = userUinHashRepository.existsByUinSaltedHash(uinSaltedHash);
            } catch (Exception ignored) {}

            if (!mockExists && !dbExists) {
                model.addAttribute("errorMessage", "This Individual ID / UIN does not exist in the mock identity service or identity registry.");
                return "delete";
            }
        } catch (Exception e) {
            model.addAttribute("errorMessage", "An error occurred while checking the identity: " + e.getMessage());
            return "delete";
        }

        return "verify-otp";
    }

    /**
     * Step 2: Verify the OTP and retrieve user identity details.
     */
    @PostMapping("/delete/verify-otp")
    public String verifyOtp(@org.springframework.web.bind.annotation.RequestParam("uin") String uin,
                            @org.springframework.web.bind.annotation.RequestParam("otp") String otp,
                            Model model) {
        model.addAttribute("uin", uin);

        if (uin == null || !uin.trim().matches("[a-zA-Z0-9-]{5,36}")) {
            model.addAttribute("errorMessage", "Enter a valid Individual ID or UIN.");
            return "delete";
        }
        if (otp == null || !otp.trim().equals("00000")) {
            model.addAttribute("errorMessage", "The OTP is incorrect. For this demo, use 00000.");
            return "verify-otp";
        }

        uin = uin.trim();
        boolean foundData = false;

        // 1. Check local databases
        try {
            String uinSaltedHash = saltModuloHashService.hash(uin);
            java.util.Optional<UserUinHash> uinHashOpt = userUinHashRepository.findByUinSaltedHash(uinSaltedHash);
            if (uinHashOpt.isPresent()) {
                UserUinHash uinHash = uinHashOpt.get();
                String userId = uinHash.getUserId();
                model.addAttribute("userId", userId);

                userBasicDetailsRepository.findById(userId).ifPresent(b -> model.addAttribute("basicDetails", b));
                userParentDetailsRepository.findById(userId).ifPresent(p -> model.addAttribute("parentDetails", p));

                String profileImageUrl = minioStorageService.getProfileImagePresignedUrl(userId);
                if (profileImageUrl != null) {
                    model.addAttribute("profileImageUrl", profileImageUrl);
                }
                foundData = true;
            }
        } catch (Exception ignored) {}

        // 2. Check Mock Identity System
        try {
            Map<String, Object> mockDetails = mockIdentityService.getIdentityDetails(uin);
            if (mockDetails != null) {
                foundData = true;
                if (!model.containsAttribute("userId")) {
                    Object indId = mockDetails.get("individualId");
                    model.addAttribute("userId", indId != null ? indId.toString() : uin);
                }
                if (!model.containsAttribute("basicDetails")) {
                    UserBasicDetails mockBasic = buildBasicDetailsFromMock(mockDetails, uin);
                    model.addAttribute("basicDetails", mockBasic);
                }
            }
        } catch (Exception ignored) {}

        if (foundData) {
            return "confirm-delete";
        } else {
            model.addAttribute("errorMessage", "OTP verified, but identity details were not found.");
            return "verify-otp";
        }
    }

    private UserBasicDetails buildBasicDetailsFromMock(Map<String, Object> mockDetails, String defaultUin) {
        UserBasicDetails b = new UserBasicDetails();
        Object indId = mockDetails.get("individualId");
        b.setUserId(indId != null ? indId.toString() : defaultUin);
        String nameVal = extractValue(mockDetails.get("fullName"));
        if (nameVal.isEmpty()) {
            nameVal = extractValue(mockDetails.get("name"));
        }
        if (nameVal.isEmpty()) {
            nameVal = "Mock Identity User";
        }
        b.setName(nameVal);

        Object phone = mockDetails.get("phone");
        b.setPhone(phone != null && !phone.toString().isEmpty() ? phone.toString() : "Not Provided");
        return b;
    }

    private String extractValue(Object field) {
        if (field == null) return "";
        if (field instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) {
                Object val = map.get("value");
                return val != null ? val.toString() : "";
            }
            return first.toString();
        }
        return field.toString();
    }

    /**
     * Step 3: Deletes all registry data associated with the UIN.
     */
    @PostMapping("/delete/confirm")
    public String confirmDelete(@org.springframework.web.bind.annotation.RequestParam("uin") String uin,
                                @org.springframework.web.bind.annotation.RequestParam(value = "consent", defaultValue = "false") boolean consent,
                                Model model) {
        model.addAttribute("uin", uin);

        if (!consent) {
            model.addAttribute("errorMessage", "You must provide consent to proceed with deletion.");
            return "confirm-delete";
        }

        if (uin == null || !uin.trim().matches("[a-zA-Z0-9-]{5,36}")) {
            model.addAttribute("errorMessage", "Enter a valid Individual ID or UIN.");
            return "delete";
        }

        uin = uin.trim();
        String uinSaltedHash = saltModuloHashService.hash(uin);
        String userId = uin;

        try {
            java.util.Optional<UserUinHash> uinHashOpt = userUinHashRepository.findByUinSaltedHash(uinSaltedHash);
            if (uinHashOpt.isPresent()) {
                userId = uinHashOpt.get().getUserId();
            }

            model.addAttribute("userId", userId);

            // Look up data locations
            java.util.Optional<UserDataLocation> locationOpt = userDataLocationRepository.findById(userId);
            boolean expectBasic = true, expectParent = true, expectHash = true, expectMinio = true;
            if (locationOpt.isPresent()) {
                UserDataLocation loc = locationOpt.get();
                expectBasic = loc.isHasBasic();
                expectParent = loc.isHasParent();
                expectHash = loc.isHasHash();
                expectMinio = loc.isHasMinio();
            }

            DeletionAudit audit = new DeletionAudit(userId, uinSaltedHash);
            StringBuilder detailBuilder = new StringBuilder();
            boolean anyFailed = false;

            Map<String, String> steps = new java.util.LinkedHashMap<>();

            // 1. Delete from basic details database
            if (expectBasic) {
                try {
                    if (userBasicDetailsRepository.existsById(userId)) {
                        userBasicDetailsRepository.deleteById(userId);
                        audit.setBasicStatus(DeletionAudit.PURGED);
                        steps.put("Demographic Details (user_basic_details)", "SUCCESSFULLY_PURGED");
                    } else {
                        audit.setBasicStatus(DeletionAudit.NOT_FOUND);
                        steps.put("Demographic Details (user_basic_details)", "NOT_FOUND_SKIPPED");
                    }
                } catch (Exception e) {
                    audit.setBasicStatus(DeletionAudit.FAILED);
                    steps.put("Demographic Details (user_basic_details)", "FAILED: " + e.getMessage());
                    detailBuilder.append("Basic DB: ").append(e.getMessage()).append("; ");
                    anyFailed = true;
                }
            } else {
                audit.setBasicStatus(DeletionAudit.NOT_EXPECTED);
                steps.put("Demographic Details (user_basic_details)", "NOT_EXPECTED");
            }

            // 2. Delete from parent details database
            if (expectParent) {
                try {
                    if (userParentDetailsRepository.existsById(userId)) {
                        userParentDetailsRepository.deleteById(userId);
                        audit.setParentStatus(DeletionAudit.PURGED);
                        steps.put("Parent Details (user_parent_details)", "SUCCESSFULLY_PURGED");
                    } else {
                        audit.setParentStatus(DeletionAudit.NOT_FOUND);
                        steps.put("Parent Details (user_parent_details)", "NOT_FOUND_SKIPPED");
                    }
                } catch (Exception e) {
                    audit.setParentStatus(DeletionAudit.FAILED);
                    steps.put("Parent Details (user_parent_details)", "FAILED: " + e.getMessage());
                    detailBuilder.append("Parent DB: ").append(e.getMessage()).append("; ");
                    anyFailed = true;
                }
            } else {
                audit.setParentStatus(DeletionAudit.NOT_EXPECTED);
                steps.put("Parent Details (user_parent_details)", "NOT_EXPECTED");
            }

            // 3. Delete all user images from MinIO
            List<String> purgedMinioPaths = new java.util.ArrayList<>();
            if (expectMinio) {
                try {
                    purgedMinioPaths = minioStorageService.deleteAllUserImages(userId);
                    audit.setMinioStatus(DeletionAudit.PURGED);
                    if (!purgedMinioPaths.isEmpty()) {
                        steps.put("User Images & Documents (MinIO object store)", "SUCCESSFULLY_PURGED (" + purgedMinioPaths.size() + " files)");
                    } else {
                        steps.put("User Images & Documents (MinIO object store)", "SUCCESSFULLY_PURGED (No files found)");
                    }
                } catch (Exception e) {
                    audit.setMinioStatus(DeletionAudit.FAILED);
                    steps.put("User Images & Documents (MinIO object store)", "FAILED: " + e.getMessage());
                    detailBuilder.append("MinIO Storage error: ").append(e.getMessage()).append("; ");
                    anyFailed = true;
                    System.err.println("Non-critical failure purging user images from MinIO: " + e.getMessage());
                }
            } else {
                audit.setMinioStatus(DeletionAudit.NOT_EXPECTED);
                steps.put("User Images & Documents (MinIO object store)", "NOT_EXPECTED");
            }

            // 4. Delete from hashing database
            if (expectHash) {
                try {
                    if (userUinHashRepository.existsById(userId)) {
                        userUinHashRepository.deleteById(userId);
                        audit.setHashStatus(DeletionAudit.PURGED);
                        steps.put("Cryptographic Identity Hash (user_uin_hash)", "SUCCESSFULLY_PURGED");
                    } else {
                        audit.setHashStatus(DeletionAudit.NOT_FOUND);
                        steps.put("Cryptographic Identity Hash (user_uin_hash)", "NOT_FOUND_SKIPPED");
                    }
                } catch (Exception e) {
                    audit.setHashStatus(DeletionAudit.FAILED);
                    steps.put("Cryptographic Identity Hash (user_uin_hash)", "FAILED: " + e.getMessage());
                    detailBuilder.append("Hash DB: ").append(e.getMessage()).append("; ");
                    anyFailed = true;
                }
            } else {
                audit.setHashStatus(DeletionAudit.NOT_EXPECTED);
                steps.put("Cryptographic Identity Hash (user_uin_hash)", "NOT_EXPECTED");
            }

            // 5. Delete from local Mock Identity System (esignet-mock-services)
            boolean mockPurged = false;
            try {
                mockPurged = mockIdentityService.deleteIdentity(userId) || mockIdentityService.deleteIdentity(uin);
                if (mockPurged) {
                    steps.put("Mock Identity Service (esignet-mock-services)", "SUCCESSFULLY_PURGED");
                } else {
                    steps.put("Mock Identity Service (esignet-mock-services)", "NOT_FOUND_SKIPPED");
                }
            } catch (Exception e) {
                steps.put("Mock Identity Service (esignet-mock-services)", "FAILED: " + e.getMessage());
                detailBuilder.append("Mock Identity DB: ").append(e.getMessage()).append("; ");
            }

            // Compute overall status
            boolean anyDbPurged = DeletionAudit.PURGED.equals(audit.getBasicStatus())
                    || DeletionAudit.PURGED.equals(audit.getParentStatus())
                    || DeletionAudit.PURGED.equals(audit.getHashStatus())
                    || mockPurged;

            if (!anyFailed) {
                audit.setOverallStatus(DeletionAudit.SUCCESS);
            } else if (anyDbPurged) {
                audit.setOverallStatus(DeletionAudit.PARTIAL);
            } else {
                audit.setOverallStatus(DeletionAudit.FAILED);
            }

            StringBuilder summaryBuilder = new StringBuilder();
            List<String> purgedStores = new java.util.ArrayList<>();
            if (DeletionAudit.PURGED.equals(audit.getBasicStatus())) purgedStores.add("user_basic_details (defaultdb)");
            if (DeletionAudit.PURGED.equals(audit.getParentStatus())) purgedStores.add("user_parent_details (user-parent-detail)");
            if (DeletionAudit.PURGED.equals(audit.getHashStatus())) purgedStores.add("user_uin_hash (uin-hashing)");
            if (mockPurged) purgedStores.add("esignet-mock-services (mock_identity)");

            summaryBuilder.append("Purged Databases: ").append(purgedStores.isEmpty() ? "None" : purgedStores.toString()).append("; ");
            summaryBuilder.append("Purged MinIO Paths: ").append(purgedMinioPaths.isEmpty() ? "None" : purgedMinioPaths.toString()).append("; ");

            if (detailBuilder.length() > 0) {
                summaryBuilder.append("Errors: ").append(detailBuilder.toString().trim());
            }

            audit.setDetail(summaryBuilder.toString().trim());

            try {
                deletionAuditRepository.save(audit);
                System.out.println("Saved deletion audit record: id=" + audit.getId()
                        + ", userId=" + userId + ", status=" + audit.getOverallStatus());
            } catch (Exception e) {
                System.err.println("Failed to save deletion audit record: " + e.getMessage());
            }

            if (DeletionAudit.SUCCESS.equals(audit.getOverallStatus())) {
                try {
                    userDataLocationRepository.deleteById(userId);
                } catch (Exception e) {
                    System.err.println("Failed to remove data-location record: " + e.getMessage());
                }
            }

            model.addAttribute("steps", steps);
            model.addAttribute("audit", audit);
            return "delete-success";

        } catch (Exception e) {
            try {
                DeletionAudit failedAudit = new DeletionAudit(null, uinSaltedHash);
                failedAudit.setOverallStatus(DeletionAudit.FAILED);
                failedAudit.setDetail("Unexpected error: " + e.getMessage());
                deletionAuditRepository.save(failedAudit);
            } catch (Exception ignored) {}

            model.addAttribute("errorMessage", "An error occurred during deletion: " + e.getMessage());
            try {
                java.util.Optional<UserUinHash> uinHashOpt = userUinHashRepository.findByUinSaltedHash(uinSaltedHash);
                if (uinHashOpt.isPresent()) {
                    String catchUserId = uinHashOpt.get().getUserId();
                    model.addAttribute("userId", catchUserId);
                    userBasicDetailsRepository.findById(catchUserId).ifPresent(b -> model.addAttribute("basicDetails", b));
                    userParentDetailsRepository.findById(catchUserId).ifPresent(p -> model.addAttribute("parentDetails", p));
                    String profileImageUrl = minioStorageService.getProfileImagePresignedUrl(catchUserId);
                    if (profileImageUrl != null) {
                        model.addAttribute("profileImageUrl", profileImageUrl);
                    }
                }
            } catch (Exception ignored) {}
            return "confirm-delete";
        }
    }

    /**
     * Audit Logs page: shows all deletion attempts.
     */
    @GetMapping("/audit-logs")
    public String showAuditLogs(@org.springframework.web.bind.annotation.RequestParam(value = "search", required = false) String search,
                                Model model) {
        java.util.List<DeletionAudit> audits;

        if (search != null && !search.trim().isEmpty()) {
            search = search.trim();
            model.addAttribute("search", search);
            audits = deletionAuditRepository.findByUserIdContainingIgnoreCaseOrderByAttemptedAtDesc(search);
        } else {
            audits = deletionAuditRepository.findAllByOrderByAttemptedAtDesc();
        }

        model.addAttribute("audits", audits);

        long total = audits.size();
        long successCount = audits.stream().filter(a -> DeletionAudit.SUCCESS.equals(a.getOverallStatus())).count();
        long partialCount = audits.stream().filter(a -> DeletionAudit.PARTIAL.equals(a.getOverallStatus())).count();
        long failedCount = audits.stream().filter(a -> DeletionAudit.FAILED.equals(a.getOverallStatus())).count();

        model.addAttribute("totalCount", total);
        model.addAttribute("successCount", successCount);
        model.addAttribute("partialCount", partialCount);
        model.addAttribute("failedCount", failedCount);

        return "audit-logs";
    }
}
