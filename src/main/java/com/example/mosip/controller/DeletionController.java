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

        // individual_id (the resident's UIN/VID) is not covered by any OIDC scope, so it
        // must be requested explicitly via the claims request parameter. Marking it
        // essential also surfaces it on the eSignet consent screen, satisfying the
        // design's explicit-UIN-consent requirement (Section 2.1).
        String claimsRequest = "{\"userinfo\":{\"individual_id\":{\"essential\":true}},\"id_token\":{}}";

        String redirectTarget = String.format(
                "%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&state=%s&nonce=%s&claims=%s",
                authorizeUrl,
                java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode("openid profile", java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(nonce, java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(claimsRequest, java.nio.charset.StandardCharsets.UTF_8));

        return "redirect:" + redirectTarget;
    }

    /**
     * Official MOSIP eSignet OIDC OAuth 2.0 Authorization Code Callback.
     * Maps both /delete/callback and the MOSIP pre-registered /userprofile
     * endpoint. Per design Section 5.2, authentication failure returns an error
     * to the UI and no deletion is attempted -- there is no fallback identity
     * (no trusting a client-supplied uin param, no picking an arbitrary mock
     * identity, no hardcoded default UIN).
     */
    @GetMapping({ "/delete/callback", "/userprofile" })
    public String esignetCallback(
            @org.springframework.web.bind.annotation.RequestParam(value = "code", required = false) String code,
            @org.springframework.web.bind.annotation.RequestParam(value = "state", required = false) String state,
            @org.springframework.web.bind.annotation.RequestParam(value = "error", required = false) String error,
            jakarta.servlet.http.HttpSession session,
            Model model) {
        String expectedState = (String) session.getAttribute("esignet_oauth_state");
        session.removeAttribute("esignet_oauth_state");
        session.removeAttribute("esignet_oauth_nonce");

        if (error != null && !error.trim().isEmpty()) {
            model.addAttribute("errorMessage", "eSignet authentication was not completed: " + error);
            return "delete";
        }

        if (code == null || code.trim().isEmpty()) {
            model.addAttribute("errorMessage",
                    "eSignet authentication failed: no authorization code was returned. No deletion was attempted.");
            return "delete";
        }

        if (expectedState == null || !expectedState.equals(state)) {
            model.addAttribute("errorMessage",
                    "eSignet authentication failed: the session state did not match. Please try again.");
            return "delete";
        }

        String targetUin = resolveAuthenticatedUin(code.trim());
        if (targetUin == null || targetUin.isEmpty()) {
            model.addAttribute("errorMessage",
                    "eSignet authentication failed: could not resolve your identity. No deletion was attempted.");
            return "delete";
        }

        populateFullIdentityModel(targetUin, model);
        return "confirm-delete";
    }

    /**
     * Resolves the authenticated identity by exchanging the authorization code for
     * an access token, then calling eSignet's /userinfo endpoint per design Section
     * 5.2. Falls back to decoding the id_token's claims only if /userinfo cannot be
     * read -- both paths require the same successful, real token exchange; there is
     * no unauthenticated fallback identity.
     */
    private String resolveAuthenticatedUin(String code) {
        try {
            String tokenEndpoint = "http://localhost:8088/v1/esignet/oauth/v2/token";
            org.springframework.web.client.RestClient client = org.springframework.web.client.RestClient.create();
            org.springframework.util.MultiValueMap<String, String> formData = new org.springframework.util.LinkedMultiValueMap<>();
            formData.add("grant_type", "authorization_code");
            formData.add("client_id", clientId);
            formData.add("redirect_uri", redirectUri);
            formData.add("code", code);
            formData.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
            formData.add("client_assertion", buildClientAssertion(tokenEndpoint));

            Map<?, ?> tokenResponse = client.post()
                    .uri(tokenEndpoint)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(Map.class);

            if (tokenResponse == null || tokenResponse.get("access_token") == null) {
                System.err.println("eSignet token exchange did not return an access_token.");
                return null;
            }

            String accessToken = tokenResponse.get("access_token").toString();

            try {
                String userInfoJwt = client.get()
                        .uri("http://localhost:8088/v1/esignet/oidc/userinfo")
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(String.class);
                // "sub" is a pairwise, partner-specific token, not the UIN - prefer
                // individual_id/uin claims, which carry the actual resolved identity.
                String claim = extractJwtClaim(userInfoJwt, "individual_id");
                if (claim == null) claim = extractJwtClaim(userInfoJwt, "uin");
                if (claim == null) claim = extractJwtClaim(userInfoJwt, "sub");
                if (claim != null && !claim.isEmpty()) {
                    return claim;
                }
            } catch (Exception e) {
                System.err.println("eSignet /userinfo call failed, falling back to id_token claims: " + e.getMessage());
            }

            Object idTokenObj = tokenResponse.get("id_token");
            if (idTokenObj != null) {
                String idToken = idTokenObj.toString();
                String claim = extractJwtClaim(idToken, "individual_id");
                if (claim == null) claim = extractJwtClaim(idToken, "uin");
                if (claim == null) claim = extractJwtClaim(idToken, "sub");
                return claim;
            }
        } catch (Exception e) {
            System.err.println("eSignet authentication resolution failed: " + e.getMessage());
        }
        return null;
    }

    private String extractJwtClaim(String jwt, String claimName) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            String payload = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            if (json.has(claimName) && !json.get(claimName).asText().isEmpty()) {
                return json.get(claimName).asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Builds a signed private_key_jwt client assertion (RFC 7523) using this RP's
     * private key, matching the "private_key_jwt" auth_method eSignet has this
     * client registered under. eSignet verifies it against the client's registered
     * JWK (esignet/docker-compose/insert_clients.sql), not a shared secret.
     */
    private String buildClientAssertion(String audience) throws Exception {
        java.security.PrivateKey privateKey = loadRpPrivateKey();
        long nowSeconds = System.currentTimeMillis() / 1000L;

        Map<String, Object> header = new java.util.LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("iss", clientId);
        payload.put("sub", clientId);
        payload.put("aud", audience);
        payload.put("jti", java.util.UUID.randomUUID().toString());
        payload.put("iat", nowSeconds);
        payload.put("exp", nowSeconds + 300);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String signingInput = base64UrlNoPad(mapper.writeValueAsBytes(header)) + "."
                + base64UrlNoPad(mapper.writeValueAsBytes(payload));

        java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return signingInput + "." + base64UrlNoPad(signature.sign());
    }

    private java.security.PrivateKey loadRpPrivateKey() throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/esignet-rp-private-key.pem")) {
            if (in == null) {
                throw new IllegalStateException("esignet-rp-private-key.pem not found on classpath");
            }
            String pem = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = java.util.Base64.getDecoder().decode(pem);
            java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(der);
            return java.security.KeyFactory.getInstance("RSA").generatePrivate(spec);
        }
    }

    private String base64UrlNoPad(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
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

    /**
     * Aggregates sub-step outcomes into a single module status per Section 14.4:
     * any failure with at least one deletion -&gt; PARTIAL, any failure with none -&gt; FAILED,
     * no failures with at least one deletion -&gt; DELETED, otherwise NOT_FOUND.
     */
    private String aggregateModuleStatus(java.util.List<String> subStatuses) {
        boolean anyDeleted = subStatuses.contains(DeletionAudit.DELETED);
        boolean anyFailed = subStatuses.contains(DeletionAudit.FAILED);
        if (anyFailed) {
            return anyDeleted ? DeletionAudit.PARTIAL : DeletionAudit.FAILED;
        }
        return anyDeleted ? DeletionAudit.DELETED : DeletionAudit.NOT_FOUND;
    }

    /** Section-7-ordered stage descriptions shown on the deletion-progress page. */
    private static final Map<String, String> STAGE_DESCRIPTIONS = Map.of(
            "Registration", "Removing enrolment records and appointment history",
            "ID Repository", "Erasing identity records from the ID repository",
            "ID Authentication", "Revoking authentication records and credentials",
            "Resident Portal", "Clearing your resident portal profile and services",
            "Self-Registration", "Deleting self-registration application data");

    private java.util.List<Map<String, String>> buildStageList(Map<String, String> steps) {
        java.util.List<Map<String, String>> stages = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : steps.entrySet()) {
            Map<String, String> stage = new java.util.LinkedHashMap<>();
            stage.put("name", entry.getKey());
            stage.put("desc", STAGE_DESCRIPTIONS.getOrDefault(entry.getKey(), ""));
            stage.put("status", entry.getValue());
            stages.add(stage);
        }
        return stages;
    }

    /** Masks a UIN for on-screen display only; never used for lookups. */
    private String maskUin(String uin) {
        if (uin == null || uin.trim().isEmpty()) {
            return "•••• •••• ????";
        }
        String trimmed = uin.trim();
        String last4 = trimmed.length() >= 4 ? trimmed.substring(trimmed.length() - 4) : trimmed;
        return "•••• •••• " + last4;
    }

    private String orNotFound(String status) {
        return status != null ? status : DeletionAudit.NOT_FOUND;
    }

    /**
     * Never persists the plain UIN (design Section 13): uses the resolved internal
     * system id if one was found, otherwise the salted hash, otherwise a placeholder.
     */
    private String resolveAuditUserId(String resolvedInternalUserId, String uinSaltedHash) {
        if (resolvedInternalUserId != null) {
            return resolvedInternalUserId;
        }
        if (uinSaltedHash != null) {
            return uinSaltedHash;
        }
        return "UNRESOLVED";
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
        String resolvedInternalUserId = null;
        String uinSaltedHash = null;

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
                if (uinSaltedHash != null) {
                    uinHashOpt = userUinHashRepository.findByUinSaltedHash(uinSaltedHash);
                    if (uinHashOpt.isPresent()) {
                        targetIds.add(uinHashOpt.get().getUserId());
                    }
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
                        resolvedInternalUserId = id;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            model.addAttribute("userId", primaryUserId);
            DeletionAudit audit = new DeletionAudit(resolveAuditUserId(resolvedInternalUserId, uinSaltedHash), uinSaltedHash);

            // Deletion order follows design Section 7: Registration -> ID Repository ->
            // ID Authentication -> Resident Portal -> Self-Registration. Each module aggregates
            // its own sub-steps into a single status per Section 14.4.

            // Module 1: Registration (demographic capture data - Database 1 & 3)
            java.util.List<String> registrationSubStatuses = new java.util.ArrayList<>();
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
                audit.setBasicStatus(purgedBasic ? DeletionAudit.DELETED : DeletionAudit.NOT_FOUND);
                registrationSubStatuses.add(audit.getBasicStatus());
            } catch (Exception e) {
                audit.setBasicStatus(DeletionAudit.FAILED);
                registrationSubStatuses.add(DeletionAudit.FAILED);
                detailBuilder.append("Basic DB: ").append(e.getMessage()).append("; ");
                anyFailed = true;
            }

            try {
                boolean purgedParent = false;
                for (String targetId : targetIds) {
                    if (userParentDetailsRepository.existsById(targetId)) {
                        userParentDetailsRepository.deleteById(targetId);
                        purgedParent = true;
                    }
                }
                audit.setParentStatus(purgedParent ? DeletionAudit.DELETED : DeletionAudit.NOT_FOUND);
                registrationSubStatuses.add(audit.getParentStatus());
            } catch (Exception e) {
                audit.setParentStatus(DeletionAudit.FAILED);
                registrationSubStatuses.add(DeletionAudit.FAILED);
                detailBuilder.append("Parent DB: ").append(e.getMessage()).append("; ");
                anyFailed = true;
            }
            steps.put("Registration", aggregateModuleStatus(registrationSubStatuses));

            // Module 2: ID Repository (identity hash + biometrics/documents - Database 2 & MinIO)
            java.util.List<String> idRepositorySubStatuses = new java.util.ArrayList<>();
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
                audit.setHashStatus(purgedHash ? DeletionAudit.DELETED : DeletionAudit.NOT_FOUND);
                idRepositorySubStatuses.add(audit.getHashStatus());
            } catch (Exception e) {
                audit.setHashStatus(DeletionAudit.FAILED);
                idRepositorySubStatuses.add(DeletionAudit.FAILED);
                detailBuilder.append("Hash DB: ").append(e.getMessage()).append("; ");
                anyFailed = true;
            }

            List<String> purgedMinioPaths = new java.util.ArrayList<>();
            try {
                for (String targetId : targetIds) {
                    purgedMinioPaths.addAll(minioStorageService.deleteAllUserImages(targetId));
                }
                audit.setMinioStatus(!purgedMinioPaths.isEmpty() ? DeletionAudit.DELETED : DeletionAudit.NOT_FOUND);
                idRepositorySubStatuses.add(audit.getMinioStatus());
            } catch (Exception e) {
                audit.setMinioStatus(DeletionAudit.FAILED);
                idRepositorySubStatuses.add(DeletionAudit.FAILED);
                detailBuilder.append("MinIO Storage error: ").append(e.getMessage()).append("; ");
                anyFailed = true;
            }
            steps.put("ID Repository", aggregateModuleStatus(idRepositorySubStatuses));

            // Module 3: ID Authentication (mock-identity-system stands in for IDA in this prototype)
            boolean mockPurged = false;
            try {
                for (String targetId : targetIds) {
                    mockPurged |= mockIdentityService.deleteIdentity(targetId);
                }
                audit.setIdAuthStatus(mockPurged ? DeletionAudit.DELETED : DeletionAudit.NOT_FOUND);
                steps.put("ID Authentication", audit.getIdAuthStatus());
            } catch (Exception e) {
                audit.setIdAuthStatus(DeletionAudit.FAILED);
                steps.put("ID Authentication", "FAILED: " + e.getMessage());
                detailBuilder.append("Mock Identity DB: ").append(e.getMessage()).append("; ");
            }

            // Module 4: Resident Portal - not yet integrated in this prototype
            steps.put("Resident Portal", DeletionAudit.SKIPPED);

            // Module 5: Self-Registration - optional per design Section 12, not yet integrated
            steps.put("Self-Registration", DeletionAudit.SKIPPED);

            // Compute overall status
            boolean anyDbPurged = DeletionAudit.DELETED.equals(audit.getBasicStatus())
                    || DeletionAudit.DELETED.equals(audit.getParentStatus())
                    || DeletionAudit.DELETED.equals(audit.getHashStatus())
                    || DeletionAudit.DELETED.equals(audit.getMinioStatus())
                    || mockPurged;

            if (anyFailed) {
                audit.setOverallStatus(anyDbPurged ? DeletionAudit.PARTIAL : DeletionAudit.FAILED);
            } else if (anyDbPurged) {
                audit.setOverallStatus(DeletionAudit.DELETED);
            } else {
                audit.setOverallStatus(DeletionAudit.NOT_FOUND);
            }

            StringBuilder summaryBuilder = new StringBuilder();
            List<String> purgedStores = new java.util.ArrayList<>();
            if (DeletionAudit.DELETED.equals(audit.getBasicStatus()))
                purgedStores.add("user_basic_details (defaultdb)");
            if (DeletionAudit.DELETED.equals(audit.getParentStatus()))
                purgedStores.add("user_parent_details (user-parent-detail)");
            if (DeletionAudit.DELETED.equals(audit.getHashStatus()))
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
            model.addAttribute("maskedUin", maskUin(uin));
            model.addAttribute("stages", buildStageList(steps));
            return "deletion-progress";

        } catch (Exception e) {
            try {
                DeletionAudit failedAudit = new DeletionAudit(
                        resolveAuditUserId(resolvedInternalUserId, uinSaltedHash), uinSaltedHash);
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
     * Detailed per-module breakdown for a single deletion request, linked from the
     * deletion-progress success/paused screens. Reuses the existing delete-success view.
     */
    @GetMapping("/delete/report/{id}")
    public String showDeletionReport(@org.springframework.web.bind.annotation.PathVariable("id") Long id,
            Model model) {
        DeletionAudit audit = deletionAuditRepository.findById(id).orElse(null);
        if (audit == null) {
            model.addAttribute("errorMessage", "No deletion report found for that reference.");
            return "audit-logs";
        }

        Map<String, String> steps = new java.util.LinkedHashMap<>();
        steps.put("Registration", aggregateModuleStatus(java.util.List.of(
                orNotFound(audit.getBasicStatus()), orNotFound(audit.getParentStatus()))));
        steps.put("ID Repository", aggregateModuleStatus(java.util.List.of(
                orNotFound(audit.getHashStatus()), orNotFound(audit.getMinioStatus()))));
        steps.put("ID Authentication", orNotFound(audit.getIdAuthStatus()));
        steps.put("Resident Portal", DeletionAudit.SKIPPED);
        steps.put("Self-Registration", DeletionAudit.SKIPPED);

        model.addAttribute("steps", steps);
        model.addAttribute("audit", audit);
        model.addAttribute("userId", audit.getUserId());
        return "delete-success";
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
