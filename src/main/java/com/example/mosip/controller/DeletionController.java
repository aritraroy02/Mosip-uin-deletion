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
 * <p>
 * Handles the multi-step delete workflow ({@code /delete}, OTP send/verify, confirm) and the
 * audit-logs view. Split out from {@code RegistrationController} so the deletion concern lives
 * in its own file, mirroring how registration is structured.
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

    public DeletionController(UserBasicDetailsRepository userBasicDetailsRepository,
                              UserUinHashRepository userUinHashRepository,
                              UserParentDetailsRepository userParentDetailsRepository,
                              UserDataLocationRepository userDataLocationRepository,
                              DeletionAuditRepository deletionAuditRepository,
                              MinioStorageService minioStorageService,
                              SaltModuloHashService saltModuloHashService,
                              MockIdentityService mockIdentityService) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userParentDetailsRepository = userParentDetailsRepository;
        this.userDataLocationRepository = userDataLocationRepository;
        this.deletionAuditRepository = deletionAuditRepository;
        this.minioStorageService = minioStorageService;
        this.saltModuloHashService = saltModuloHashService;
        this.mockIdentityService = mockIdentityService;
    }

    @GetMapping("/delete")
    public String showDeleteForm() {
        return "delete";
    }

    /**
     * Step 1: accept an Individual ID / UIN and "send" an OTP. Validates the ID format, then confirms
     * the ID exists in the Mock Identity System or identity registry database before routing to OTP verification.
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
     * Step 2: verify the OTP, retrieve user identity details from Mock Identity System or DBs,
     * and route to the confirm-delete screen.
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

        // 1. Check local databases (Database 1, 2, 3)
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

        // 2. Check Mock Identity System if DB basic details are missing or for fallback
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
     * Step 3: Deletes all registry data associated with the UIN in the exact requested order:
     * 1. user_basic_details database (Demographics)
     * 2. user_parent_details database (Parent details)
     * 3. userprofilepic bucket (MinIO profile photo)
     * 4. user_uin_hash database (UIN hashing)
     *
     * Consults the {@link UserDataLocation} registry to know which stores SHOULD contain
     * the user's data, then records per-store outcomes in a {@link DeletionAudit} row.
     * If a store was never written to, its status is NOT_EXPECTED rather than FAILED.
     * If a store is unreachable, its status is FAILED (not silently skipped).
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

            // Look up the data-location registry to know which stores SHOULD hold data
            java.util.Optional<UserDataLocation> locationOpt = userDataLocationRepository.findById(userId);
            boolean expectBasic = true, expectParent = true, expectHash = true, expectMinio = true;
            if (locationOpt.isPresent()) {
                UserDataLocation loc = locationOpt.get();
                expectBasic = loc.isHasBasic();
                expectParent = loc.isHasParent();
                expectHash = loc.isHasHash();
                expectMinio = loc.isHasMinio();
            }

            // Create the audit record (will be populated as we go)
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

            // 3. Cascading Delete all user images from MinIO (profile pictures, Aadhar cards, documents)
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

            // Add purged store summary & MinIO file paths to audit details
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

            // Persist the audit record
            try {
                deletionAuditRepository.save(audit);
                System.out.println("Saved deletion audit record: id=" + audit.getId()
                        + ", userId=" + userId + ", status=" + audit.getOverallStatus());
            } catch (Exception e) {
                System.err.println("Failed to save deletion audit record: " + e.getMessage());
            }

            // Clean up the data-location registry on full success
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
            // Top-level catch: if we get here, something unexpected broke before we could
            // finish the deletion loop. Still try to record a FAILED audit.
            try {
                DeletionAudit failedAudit = new DeletionAudit(null, uinSaltedHash);
                failedAudit.setOverallStatus(DeletionAudit.FAILED);
                failedAudit.setDetail("Unexpected error: " + e.getMessage());
                deletionAuditRepository.save(failedAudit);
            } catch (Exception ignored) {}

            model.addAttribute("errorMessage", "An error occurred during deletion: " + e.getMessage());
            // Attempt to restore details to the model for redisplaying on confirm-delete page
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
     * Audit Logs page: shows all deletion attempts with per-store outcomes.
     * Supports optional search by User ID.
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

        // Compute summary stats
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
