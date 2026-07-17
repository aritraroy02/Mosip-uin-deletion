package com.example.mosip.controller.api;

import com.example.mosip.repository.basic.UserBasicDetailsRepository;
import com.example.mosip.repository.hashing.UserUinHashRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Unified deletion REST API.
 * <p>
 * Split out from {@code RegistrationApiController} so deletion has its own API entry point,
 * mirroring how registration is structured. Purges a user's demographic details (Database 1)
 * and cryptographic UIN hash (Database 2).
 */
@RestController
@RequestMapping("/api")
public class DeletionApiController {

    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;

    public DeletionApiController(UserBasicDetailsRepository userBasicDetailsRepository,
                                 UserUinHashRepository userUinHashRepository) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
    }

    /**
     * Unified Delete User API
     * Endpoint: DELETE /api/user/{userId}
     */
    @DeleteMapping("/user/{userId}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable String userId) {
        boolean basicExists = userBasicDetailsRepository.existsById(userId);
        boolean hashExists = userUinHashRepository.existsById(userId);

        if (!basicExists && !hashExists) {
            Map<String, String> response = new HashMap<>();
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

            Map<String, String> response = new HashMap<>();
            response.put("userId", userId);
            response.put("status", "DELETED");
            response.put("message", "User details and UIN hash successfully deleted from all databases.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> response = new HashMap<>();
            response.put("userId", userId);
            response.put("status", "ERROR");
            response.put("message", "Error deleting user: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
