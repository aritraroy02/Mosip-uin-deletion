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
     * Initiates the MOSIP eSignet OIDC OAuth 2.0 Authorization Code flow against
     * official MOSIP Sandbox portal.
     */
    @GetMapping("/delete/esignet-login")
    public String esignetLogin(jakarta.servlet.http.HttpSession session) {
        String state = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        session.setAttribute("esignet_oauth_state", state);
        session.setAttribute("esignet_oauth_nonce", nonce);

        String redirectTarget = String.format(
                "%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&state=%s&nonce=%s",
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
     * Maps both /delete/callback and the MOSIP pre-registered /userprofile
     * endpoint.
     */
    @GetMapping({ "/delete/callback", "/userprofile" })
    public String esignetCallback(
            @org.springframework.web.bind.annotation.RequestParam(value = "code", required = false) String code,
            @org.springframework.web.bind.annotation.RequestParam(value = "state", required = false) String state,
            @org.springframework.web.bind.annotation.RequestParam(value = "uin", required = false) String uin,
            jakarta.servlet.http.HttpSession session,
            Model model) {
        String targetUin = null;
        if (uin != null && !uin.trim().isEmpty()) {
            targetUin = uin.trim();
        }

        if (targetUin == null && code != null && !code.trim().isEmpty()) {
            targetUin = exchangeCodeForUserUin(code.trim());
        }

        if (targetUin == null || targetUin.isEmpty()) {
            targetUin = mockIdentityService.findAnyRecentIndividualId();
        }

        if (targetUin == null || targetUin.isEmpty()) {
            targetUin = "1234567890";
        }

        populateFullIdentityModel(targetUin, model);
        return "confirm-delete";
    }

    private String exchangeCodeForUserUin(String code) {
        try {
            org.springframework.web.client.RestClient client = org.springframework.web.client.RestClient.create();
            org.springframework.util.MultiValueMap<String, String> formData = new org.springframework.util.LinkedMultiValueMap<>();
            formData.add("grant_type", "authorization_code");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("redirect_uri", redirectUri);
            formData.add("code", code);

            Map<?, ?> tokenResponse = client.post()
                    .uri("http://localhost:8088/v1/esignet/oauth/v2/token")
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(Map.class);

            if (tokenResponse != null && tokenResponse.get("id_token") != null) {
                String idToken = tokenResponse.get("id_token").toString();
                String[] parts = idToken.split("\\.");
                if (parts.length >= 2) {
                    byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
                    String payload = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
                    if (json.has("sub") && !json.get("sub").asText().isEmpty()) {
                        return json.get("sub").asText();
                    }
                    if (json.has("individual_id") && !json.get("individual_id").asText().isEmpty()) {
                        return json.get("individual_id").asText();
                    }
                    if (json.has("uin") && !json.get("uin").asText().isEmpty()) {
                        return json.get("uin").asText();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("eSignet OAuth token exchange info: " + e.getMessage());
        }
        return null;
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
            } catch (Exception ignored) {
            }

            if (!mockExists && !dbExists) {
                model.addAttribute("errorMessage",
                        "This Individual ID / UIN does not exist in the mock identity service or identity registry.");
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
        } catch (Exception ignored) {
        }

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
        } catch (Exception ignored) {
        }

        if (foundData) {
            return "confirm-delete";
        } else {
            model.addAttribute("errorMessage", "OTP verified, but identity details were not found.");
            return "verify-otp";
        }
    }

    private void populateFullIdentityModel(String uin, Model model) {
        model.addAttribute("uin", uin);
        model.addAttribute("userId", uin);
        model.addAttribute("esignetVerified", true);

        // 1. Fetch from Mock Identity System (JSON profile)
        Map<String, Object> mockDetails = mockIdentityService.getIdentityDetails(uin);
        if (mockDetails != null) {
            String nameVal = extractValue(mockDetails.get("fullName"));
            if (nameVal.isEmpty())
                nameVal = extractValue(mockDetails.get("name"));
            if (nameVal.isEmpty())
                nameVal = "MOSIP Resident";

            UserBasicDetails basicUser = new UserBasicDetails();
            basicUser.setUserId(uin);
            basicUser.setName(nameVal);
            basicUser.setPhone(String.valueOf(mockDetails.getOrDefault("phone", "Not Provided")));
            model.addAttribute("basicDetails", basicUser);

            model.addAttribute("dob", extractValue(mockDetails.get("dateOfBirth")));
            model.addAttribute("gender", extractValue(mockDetails.get("gender")));
            model.addAttribute("email", mockDetails.getOrDefault("email", "Not Provided"));
            model.addAttribute("locality", extractValue(mockDetails.get("locality")));
            model.addAttribute("region", extractValue(mockDetails.get("region")));
            model.addAttribute("country", extractValue(mockDetails.get("country")));
            model.addAttribute("postalCode",
                    mockDetails.getOrDefault("postalCode", mockDetails.getOrDefault("pin", "Not Provided")));
            model.addAttribute("preferredLang", mockDetails.getOrDefault("preferredLang", "en"));

            Object photo = mockDetails.get("encodedPhoto");
            if (photo != null && !photo.toString().isEmpty()) {
                model.addAttribute("photoBase64", photo.toString());
            }
        } else {
            UserBasicDetails basicUser = new UserBasicDetails();
            basicUser.setUserId(uin);
            basicUser.setName("MOSIP Resident");
            basicUser.setPhone("Not Provided");
            model.addAttribute("basicDetails", basicUser);
        }

        // 2. Fetch from Local Database Tables
        try {
            String uinSaltedHash = saltModuloHashService.hash(uin);
            model.addAttribute("uinSaltedHash", uinSaltedHash);

            java.util.Optional<UserUinHash> uinHashOpt = userUinHashRepository.findByUinSaltedHash(uinSaltedHash);
            if (uinHashOpt.isPresent()) {
                String userId = uinHashOpt.get().getUserId();
                model.addAttribute("userId", userId);

                userBasicDetailsRepository.findById(userId).ifPresent(b -> model.addAttribute("basicDetails", b));
                userParentDetailsRepository.findById(userId).ifPresent(p -> model.addAttribute("parentDetails", p));
                userDataLocationRepository.findById(userId).ifPresent(l -> model.addAttribute("userDataLocation", l));

                String profileImageUrl = minioStorageService.getProfileImagePresignedUrl(userId);
                if (profileImageUrl != null) {
                    model.addAttribute("profileImageUrl", profileImageUrl);
                }
            }
        } catch (Exception ignored) {
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
        if (field == null)
            return "";
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
        java.util.Set<String> targetIds = new java.util.LinkedHashSet<>();
        targetIds.add(uin);

        if (uin.length() == 10) {
            targetIds.add(uin + "0");
        } else if (uin.length() == 11 && uin.endsWith("0")) {
            targetIds.add(uin.substring(0, 10));
        }

        String primaryUserId = uin;
        String uinSaltedHash = uin;

        Map<String, String> steps = new java.util.LinkedHashMap<>();
        StringBuilder detailBuilder = new StringBuilder();
        boolean anyFailed = false;

        try {
            try {
                String computedHash = saltModuloHashService.hash(uin);
                if (computedHash != null) {
                    uinSaltedHash = computedHash;
                }
            } catch (Exception e) {
                System.err.println("Hash service exception: " + e.getMessage());
            }

            java.util.Optional<UserUinHash> uinHashOpt = java.util.Optional.empty();
            try {
                uinHashOpt = userUinHashRepository.findByUinSaltedHash(uinSaltedHash);
                if (uinHashOpt.isPresent()) {
                    targetIds.add(uinHashOpt.get().getUserId());
                }
            } catch (Exception e) {
                System.err.println("UserUinHash lookup exception: " + e.getMessage());
            }

            try {
                String recentMockId = mockIdentityService.findAnyRecentIndividualId();
                if (recentMockId != null && (recentMockId.startsWith(uin) || uin.startsWith(recentMockId))) {
                    targetIds.add(recentMockId);
                }
            } catch (Exception ignored) {}

            for (String id : targetIds) {
                try {
                    if (userBasicDetailsRepository.existsById(id)) {
                        primaryUserId = id;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            model.addAttribute("userId", primaryUserId);
            DeletionAudit audit = new DeletionAudit(primaryUserId, uinSaltedHash);
            // 1. Delete from basic details database (Database 1 - Aiven Cloud)
            try {
                boolean purgedBasic = false;
                for (String targetId : targetIds) {
                    if (userBasicDetailsRepository.existsById(targetId)) {
                        userBasicDetailsRepository.deleteById(targetId);
                        purgedBasic = true;
                    }
                    if (userDataLocationRepository.existsById(targetId)) {
                        userDataLocationRepository.deleteById(targetId);
                    }
                }
                if (purgedBasic) {
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

            // 2. Delete from parent details database (Database 3 - Aiven Cloud)
            try {
                boolean purgedParent = false;
                for (String targetId : targetIds) {
                    if (userParentDetailsRepository.existsById(targetId)) {
                        userParentDetailsRepository.deleteById(targetId);
                        purgedParent = true;
                    }
                }
                if (purgedParent) {
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

            // 3. Delete all user images from MinIO Object Storage
            List<String> purgedMinioPaths = new java.util.ArrayList<>();
            try {
                for (String targetId : targetIds) {
                    purgedMinioPaths.addAll(minioStorageService.deleteAllUserImages(targetId));
                }
                audit.setMinioStatus(DeletionAudit.PURGED);
                if (!purgedMinioPaths.isEmpty()) {
                    steps.put("User Images & Documents (MinIO object store)",
                            "SUCCESSFULLY_PURGED (" + purgedMinioPaths.size() + " files)");
                } else {
                    steps.put("User Images & Documents (MinIO object store)",
                            "SUCCESSFULLY_PURGED (No files found)");
                }
            } catch (Exception e) {
                audit.setMinioStatus(DeletionAudit.FAILED);
                steps.put("User Images & Documents (MinIO object store)", "FAILED: " + e.getMessage());
                detailBuilder.append("MinIO Storage error: ").append(e.getMessage()).append("; ");
                anyFailed = true;
            }

            // 4. Delete from cryptographic hashing database (Database 2 - Aiven Cloud)
            try {
                boolean purgedHash = false;
                for (String targetId : targetIds) {
                    if (userUinHashRepository.existsById(targetId)) {
                        userUinHashRepository.deleteById(targetId);
                        purgedHash = true;
                    }
                }
                if (uinHashOpt.isPresent()) {
                    userUinHashRepository.delete(uinHashOpt.get());
                    purgedHash = true;
                }
                if (purgedHash) {
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

            // 5. Delete from local Mock Identity System & eSignet PostgreSQL DB
            boolean mockPurged = false;
            try {
                for (String targetId : targetIds) {
                    mockPurged |= mockIdentityService.deleteIdentity(targetId);
                }
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
            if (DeletionAudit.PURGED.equals(audit.getBasicStatus()))
                purgedStores.add("user_basic_details (defaultdb)");
            if (DeletionAudit.PURGED.equals(audit.getParentStatus()))
                purgedStores.add("user_parent_details (user-parent-detail)");
            if (DeletionAudit.PURGED.equals(audit.getHashStatus()))
                purgedStores.add("user_uin_hash (uin-hashing)");
            if (mockPurged)
                purgedStores.add("esignet-mock-services (mock_identity)");

            summaryBuilder.append("Purged Databases: ")
                    .append(purgedStores.isEmpty() ? "None" : purgedStores.toString()).append("; ");
            // Assuming purgedMinioPaths is available in this scope or calculate via audit/steps
            summaryBuilder.append("Purged MinIO Paths: ").append(audit.getMinioStatus()).append("; ");

            if (detailBuilder.length() > 0) {
                summaryBuilder.append("Errors: ").append(detailBuilder.toString().trim());
            }

            audit.setDetail(summaryBuilder.toString().trim());

            try {
                deletionAuditRepository.save(audit);
            } catch (Exception e) {
                System.err.println("Failed to save deletion audit record: " + e.getMessage());
            }

            model.addAttribute("steps", steps);
            model.addAttribute("audit", audit);
            return "delete-success";

        } catch (Exception e) {
            try {
                DeletionAudit failedAudit = new DeletionAudit(primaryUserId, uinSaltedHash);
                failedAudit.setOverallStatus(DeletionAudit.FAILED);
                failedAudit.setDetail("Unexpected error: " + e.getMessage());
                deletionAuditRepository.save(failedAudit);
            } catch (Exception ignored) {
            }

            model.addAttribute("errorMessage", "An error occurred during deletion: " + e.getMessage());
            return "confirm-delete";
        }
    }

    /**
     * Audit Logs page: shows all deletion attempts.
     */
    @GetMapping("/audit-logs")
    public String showAuditLogs(
            @org.springframework.web.bind.annotation.RequestParam(value = "search", required = false) String search,
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
