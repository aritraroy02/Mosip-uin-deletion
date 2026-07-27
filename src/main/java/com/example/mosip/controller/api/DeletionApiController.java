package com.example.mosip.controller.api;

import com.example.mosip.repository.basic.UserBasicDetailsRepository;
import com.example.mosip.repository.hashing.UserUinHashRepository;
import com.example.mosip.repository.parent.UserParentDetailsRepository;
import com.example.mosip.service.MinioStorageService;
import com.example.mosip.service.MockIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Unified deletion REST API.
 * <p>
 * Purges a user's demographic details (Database 1), cryptographic UIN hash (Database 2),
 * parent details (Database 3), all associated images/documents from MinIO object storage,
 * and identity data from the local Mock Identity System (esignet-mock-services).
 */
@RestController
@RequestMapping("/api")
public class DeletionApiController {

    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;
    private final UserParentDetailsRepository userParentDetailsRepository;
    private final MinioStorageService minioStorageService;
    private final MockIdentityService mockIdentityService;

    public DeletionApiController(UserBasicDetailsRepository userBasicDetailsRepository,
                                 UserUinHashRepository userUinHashRepository,
                                 UserParentDetailsRepository userParentDetailsRepository,
                                 MinioStorageService minioStorageService,
                                 MockIdentityService mockIdentityService) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userParentDetailsRepository = userParentDetailsRepository;
        this.minioStorageService = minioStorageService;
        this.mockIdentityService = mockIdentityService;
    }

    /**
     * Unified Delete User API
     * Endpoint: DELETE /api/user/{userId}
     */
    @DeleteMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String userId) {
        boolean basicExists = userBasicDetailsRepository.existsById(userId);
        boolean hashExists = userUinHashRepository.existsById(userId);
        boolean parentExists = userParentDetailsRepository.existsById(userId);

        // Also check if present in mock identity system
        boolean mockPurged = false;
        try {
            mockPurged = mockIdentityService.deleteIdentity(userId);
        } catch (Exception ignored) {}

        if (!basicExists && !hashExists && !parentExists && !mockPurged) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("userId", userId);
            response.put("status", "NOT_FOUND");
            response.put("message", "User ID not found in any database or mock identity system.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        try {
            if (basicExists) {
                userBasicDetailsRepository.deleteById(userId);
            }
            if (hashExists) {
                userUinHashRepository.deleteById(userId);
            }
            if (parentExists) {
                userParentDetailsRepository.deleteById(userId);
            }

            // Cascading purge of all MinIO image types (profile, aadhar, documents)
            List<String> deletedMinioPaths = new ArrayList<>();
            try {
                deletedMinioPaths = minioStorageService.deleteAllUserImages(userId);
            } catch (Exception minioEx) {
                System.err.println("Non-critical MinIO purge failure for API user deletion: " + minioEx.getMessage());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("userId", userId);
            response.put("status", "DELETED");
            response.put("purgedDatabases", Arrays.asList("user_basic_details", "user_parent_details", "user_uin_hash", "mock_identity_system"));
            response.put("mockIdentityPurged", mockPurged);
            response.put("deletedMinioFilePaths", deletedMinioPaths);
            response.put("message", "User details, UIN hash, parent details, MinIO images/documents, and mock identity data successfully purged.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("userId", userId);
            response.put("status", "ERROR");
            response.put("message", "Error deleting user: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
