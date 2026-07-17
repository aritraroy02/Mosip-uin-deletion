package com.example.mosip.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.Map;
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

    public DeletionController(UserBasicDetailsRepository userBasicDetailsRepository,
                              UserUinHashRepository userUinHashRepository,
                              UserParentDetailsRepository userParentDetailsRepository,
                              UserDataLocationRepository userDataLocationRepository,
                              DeletionAuditRepository deletionAuditRepository,
                              MinioStorageService minioStorageService,
                              SaltModuloHashService saltModuloHashService) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userParentDetailsRepository = userParentDetailsRepository;
        this.userDataLocationRepository = userDataLocationRepository;
        this.deletionAuditRepository = deletionAuditRepository;
        this.minioStorageService = minioStorageService;
        this.saltModuloHashService = saltModuloHashService;
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
     * Step 2: verify the OTP, look up the UIN hash, retrieve all registered user data,
     * and route to the confirm-delete screen.
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
            java.util.Optional<UserUinHash> uinHashOpt = userUinHashRepository.findByUinSaltedHash(uinSaltedHash);
            if (uinHashOpt.isPresent()) {
                UserUinHash uinHash = uinHashOpt.get();
                String userId = uinHash.getUserId();
                model.addAttribute("userId", userId);

                // Fetch Basic Details (Database 1)
                java.util.Optional<UserBasicDetails> basicDetailsOpt = userBasicDetailsRepository.findById(userId);
                if (basicDetailsOpt.isPresent()) {
                    model.addAttribute("basicDetails", basicDetailsOpt.get());
                } else {
                    model.addAttribute("errorMessage", "Demographic details not found for this UIN.");
                    return "verify-otp";
                }

                // Fetch Parent Details (Database 3)
                java.util.Optional<UserParentDetails> parentDetailsOpt = userParentDetailsRepository.findById(userId);
                if (parentDetailsOpt.isPresent()) {
                    model.addAttribute("parentDetails", parentDetailsOpt.get());
                }

                // Fetch Profile Image presigned URL from MinIO (if any exists)
                String profileImageUrl = minioStorageService.getProfileImagePresignedUrl(userId);
                if (profileImageUrl != null) {
                    model.addAttribute("profileImageUrl", profileImageUrl);
                }

                return "confirm-delete";
            } else {
                model.addAttribute("errorMessage", "OTP verified, but this UIN was not found in the identity registry.");
            }
        } catch (Exception e) {
            model.addAttribute("errorMessage", "An error occurred while checking the UIN: " + e.getMessage());
        }

        return "verify-otp";
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

        if (uin == null || !uin.trim().matches("\\d{10,16}")) {
            model.addAttribute("errorMessage", "Enter a valid 10 to 16 digit UIN.");
            return "delete";
        }

        uin = uin.trim();
        String uinSaltedHash = saltModuloHashService.hash(uin);

        try {
            java.util.Optional<UserUinHash> uinHashOpt = userUinHashRepository.findByUinSaltedHash(uinSaltedHash);

            if (uinHashOpt.isEmpty()) {
                model.addAttribute("errorMessage", "This UIN does not exist in the identity registry.");
                return "delete";
            }

            UserUinHash uinHash = uinHashOpt.get();
            String userId = uinHash.getUserId();
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

            // 3. Delete profile image from MinIO
            if (expectMinio) {
                try {
                    minioStorageService.deleteProfileImage(userId);
                    audit.setMinioStatus(DeletionAudit.PURGED);
                    steps.put("Profile Picture (MinIO object store)", "SUCCESSFULLY_PURGED");
                } catch (Exception e) {
                    audit.setMinioStatus(DeletionAudit.FAILED);
                    steps.put("Profile Picture (MinIO object store)", "FAILED: " + e.getMessage());
                    detailBuilder.append("MinIO: ").append(e.getMessage()).append("; ");
                    anyFailed = true;
                    System.err.println("Non-critical failure deleting profile image: " + e.getMessage());
                }
            } else {
                audit.setMinioStatus(DeletionAudit.NOT_EXPECTED);
                steps.put("Profile Picture (MinIO object store)", "NOT_EXPECTED");
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

            // Compute overall status
            boolean allExpectedPurged = !anyFailed;
            if (allExpectedPurged) {
                audit.setOverallStatus(DeletionAudit.SUCCESS);
            } else {
                // Check if anything was purged at all
                boolean anyPurged = DeletionAudit.PURGED.equals(audit.getBasicStatus())
                        || DeletionAudit.PURGED.equals(audit.getParentStatus())
                        || DeletionAudit.PURGED.equals(audit.getHashStatus())
                        || DeletionAudit.PURGED.equals(audit.getMinioStatus());
                audit.setOverallStatus(anyPurged ? DeletionAudit.PARTIAL : DeletionAudit.FAILED);
            }

            if (detailBuilder.length() > 0) {
                audit.setDetail(detailBuilder.toString().trim());
            }

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
                    String userId = uinHashOpt.get().getUserId();
                    model.addAttribute("userId", userId);
                    userBasicDetailsRepository.findById(userId).ifPresent(b -> model.addAttribute("basicDetails", b));
                    userParentDetailsRepository.findById(userId).ifPresent(p -> model.addAttribute("parentDetails", p));
                    String profileImageUrl = minioStorageService.getProfileImagePresignedUrl(userId);
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
