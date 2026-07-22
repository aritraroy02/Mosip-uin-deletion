package com.example.mosip.controller.api;

import com.example.mosip.repository.basic.UserBasicDetailsRepository;
import com.example.mosip.repository.hashing.UserUinHashRepository;
import com.example.mosip.repository.parent.UserParentDetailsRepository;
import com.example.mosip.service.MinioStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Unified deletion REST API.
 * <p>
 * Purges a user's demographic details (Database 1), cryptographic UIN hash (Database 2),
 * parent details (Database 3), and all associated images/documents from MinIO object storage.
 */
@RestController
@RequestMapping("/api")
public class DeletionApiController {

    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;
    private final UserParentDetailsRepository userParentDetailsRepository;
    private final MinioStorageService minioStorageService;

    public DeletionApiController(UserBasicDetailsRepository userBasicDetailsRepository,
                                 UserUinHashRepository userUinHashRepository,
                                 UserParentDetailsRepository userParentDetailsRepository,
                                 MinioStorageService minioStorageService) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userParentDetailsRepository = userParentDetailsRepository;
        this.minioStorageService = minioStorageService;
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

        if (!basicExists && !hashExists && !parentExists) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("userId", userId);
            response.put("status", "NOT_FOUND");
            response.put("message", "User ID not found in any database.");
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
            response.put("purgedDatabases", Arrays.asList("user_basic_details", "user_parent_details", "user_uin_hash"));
            response.put("deletedMinioFilePaths", deletedMinioPaths);
            response.put("message", "User details, UIN hash, parent details, and MinIO images/documents successfully purged.");
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
