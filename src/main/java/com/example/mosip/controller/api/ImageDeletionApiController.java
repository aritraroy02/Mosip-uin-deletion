package com.example.mosip.controller.api;

import com.example.mosip.enums.ImageType;
import com.example.mosip.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Image Deletion REST API Endpoint.
 * <p>
 * Provides capabilities to:
 * 1. Delete specific image types for a user (PROFILE_PICTURE, AADHAR_CARD, DOCUMENT).
 * 2. Delete all stored images for a user.
 * 3. Delete specific images by MinIO object key (filepath).
 * 4. Perform batch deletion for multiple users or object keys.
 * 5. Validate user permissions via headers (e.g. X-User-Role or X-User-Id).
 */
@RestController
@RequestMapping("/api/images")
public class ImageDeletionApiController {

    private static final Logger log = LoggerFactory.getLogger(ImageDeletionApiController.class);

    private final MinioStorageService minioStorageService;

    public ImageDeletionApiController(MinioStorageService minioStorageService) {
        this.minioStorageService = minioStorageService;
    }

    /**
     * Validates if the requesting caller has permission to perform deletion.
     * Permission granted if caller is ADMIN or caller's user ID matches the target user ID.
     */
    private boolean isAuthorized(String requestUserId, String requestUserRole, String targetUserId) {
        if ("ADMIN".equalsIgnoreCase(requestUserRole) || "SYSTEM".equalsIgnoreCase(requestUserRole)) {
            return true;
        }
        if (requestUserId != null && targetUserId != null && requestUserId.trim().equalsIgnoreCase(targetUserId.trim())) {
            return true;
        }
        // Default permission strategy for demo/dev if headers omitted
        return requestUserId == null && requestUserRole == null;
    }

    /**
     * Delete image(s) for a specific user.
     * Endpoint: DELETE /api/images/user/{userId}?type={IMAGE_TYPE}
     */
    @DeleteMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUserImages(
            @PathVariable String userId,
            @RequestParam(value = "type", required = false) String typeStr,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-User-Role", required = false) String headerUserRole) {

        if (!isAuthorized(headerUserId, headerUserRole, userId)) {
            Map<String, Object> forbiddenResponse = new LinkedHashMap<>();
            forbiddenResponse.put("status", "FORBIDDEN");
            forbiddenResponse.put("error", "Permission denied. You do not have permission to delete images for user: " + userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenResponse);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            List<String> deletedFilePaths = new ArrayList<>();
            if (typeStr != null && !typeStr.trim().isEmpty()) {
                ImageType imageType = ImageType.fromString(typeStr);
                deletedFilePaths = minioStorageService.deleteImage(userId, imageType);
                response.put("imageType", imageType.name());
            } else {
                deletedFilePaths = minioStorageService.deleteAllUserImages(userId);
                response.put("imageType", "ALL");
            }

            response.put("userId", userId);
            response.put("status", "SUCCESS");
            response.put("deletedCount", deletedFilePaths.size());
            response.put("deletedFilePaths", deletedFilePaths);
            response.put("message", "Image(s) successfully deleted from MinIO object storage.");

            log.info("API Image deletion successful for user {}. Deleted file paths: {}", userId, deletedFilePaths);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("status", "BAD_REQUEST");
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("Error deleting image(s) for user {}: {}", userId, e.getMessage(), e);
            response.put("status", "ERROR");
            response.put("error", "Failed to delete image(s): " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Delete a single image by MinIO object key / filepath.
     * Endpoint: DELETE /api/images/object?objectKey={objectKey}
     */
    @DeleteMapping("/object")
    public ResponseEntity<Map<String, Object>> deleteByObjectKey(
            @RequestParam("objectKey") String objectKey,
            @RequestHeader(value = "X-User-Role", required = false) String headerUserRole) {

        if (headerUserRole != null && !"ADMIN".equalsIgnoreCase(headerUserRole) && !"SYSTEM".equalsIgnoreCase(headerUserRole)) {
            Map<String, Object> forbiddenResponse = new LinkedHashMap<>();
            forbiddenResponse.put("status", "FORBIDDEN");
            forbiddenResponse.put("error", "Permission denied. Only ADMIN/SYSTEM can delete by object key directly.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenResponse);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            boolean result = minioStorageService.deleteImageByObjectKey(objectKey);
            response.put("objectKey", objectKey);
            response.put("status", result ? "SUCCESS" : "NOT_FOUND");
            response.put("deletedFilePaths", Collections.singletonList(objectKey));
            response.put("message", "Object successfully purged from MinIO.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error deleting object key {}: {}", objectKey, e.getMessage());
            response.put("status", "ERROR");
            response.put("error", "Error purging object key: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Batch deletion API endpoint.
     * Endpoint: POST /api/images/batch-delete
     * Body: { "userIds": ["USR-1", "USR-2"], "objectKeys": ["profile-pictures/USR-3.jpg"] }
     */
    @PostMapping("/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDeleteImages(
            @RequestBody Map<String, Object> requestBody,
            @RequestHeader(value = "X-User-Role", required = false) String headerUserRole) {

        if (headerUserRole != null && !"ADMIN".equalsIgnoreCase(headerUserRole) && !"SYSTEM".equalsIgnoreCase(headerUserRole)) {
            Map<String, Object> forbiddenResponse = new LinkedHashMap<>();
            forbiddenResponse.put("status", "FORBIDDEN");
            forbiddenResponse.put("error", "Permission denied. Admin role required for batch deletion.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenResponse);
        }

        List<String> userIds = (List<String>) requestBody.getOrDefault("userIds", Collections.emptyList());
        List<String> objectKeys = (List<String>) requestBody.getOrDefault("objectKeys", Collections.emptyList());

        List<String> allDeletedPaths = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();

        // Process batch user IDs
        for (String userId : userIds) {
            try {
                List<String> paths = minioStorageService.deleteAllUserImages(userId);
                allDeletedPaths.addAll(paths);
            } catch (Exception e) {
                errors.put("user:" + userId, e.getMessage());
            }
        }

        // Process batch object keys
        for (String key : objectKeys) {
            try {
                minioStorageService.deleteImageByObjectKey(key);
                allDeletedPaths.add(key);
            } catch (Exception e) {
                errors.put("key:" + key, e.getMessage());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", errors.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS");
        response.put("totalDeletedCount", allDeletedPaths.size());
        response.put("deletedFilePaths", allDeletedPaths);
        if (!errors.isEmpty()) {
            response.put("errors", errors);
        }

        return ResponseEntity.ok(response);
    }
}
