package com.example.mosip.controller.api;

import com.example.mosip.entity.hashing.UserUinHash;
import com.example.mosip.repository.hashing.UserUinHashRepository;
import com.example.mosip.util.HashUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/uin")
public class UinApiController {

    private final UserUinHashRepository userUinHashRepository;

    public UinApiController(UserUinHashRepository userUinHashRepository) {
        this.userUinHashRepository = userUinHashRepository;
    }

    /**
     * Store a single UIN in hash format along with User ID.
     * Endpoint: POST /api/uin
     * Body: { "userId": "...", "uin": "..." }
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> storeUinHash(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        String uin = request.get("uin");

        if (userId == null || uin == null) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Both 'userId' and 'uin' fields are required.");
            errorResponse.put("status", "FAILED");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        String hashedUin = HashUtils.sha256(uin);
        UserUinHash entity = new UserUinHash(userId, hashedUin);
        
        try {
            userUinHashRepository.save(entity);
            
            Map<String, String> response = new HashMap<>();
            response.put("userId", userId);
            response.put("hashedUin", hashedUin);
            response.put("status", "SUCCESS");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Database write failed: " + e.getMessage());
            errorResponse.put("status", "ERROR");
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Store multiple UINs in hash format.
     * Endpoint: POST /api/uin/bulk
     * Body: [ { "userId": "...", "uin": "..." }, { "userId": "...", "uin": "..." } ]
     */
    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> storeMultipleUinHashes(@RequestBody List<Map<String, String>> requestList) {
        if (requestList == null || requestList.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Request list cannot be empty.");
            errorResponse.put("status", "FAILED");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        List<UserUinHash> entitiesToSave = new ArrayList<>();
        List<Map<String, String>> savedDetails = new ArrayList<>();

        for (Map<String, String> request : requestList) {
            String userId = request.get("userId");
            String uin = request.get("uin");

            if (userId != null && uin != null) {
                String hashedUin = HashUtils.sha256(uin);
                entitiesToSave.add(new UserUinHash(userId, hashedUin));

                Map<String, String> saved = new HashMap<>();
                saved.put("userId", userId);
                saved.put("hashedUin", hashedUin);
                savedDetails.add(saved);
            }
        }

        try {
            userUinHashRepository.saveAll(entitiesToSave);

            Map<String, Object> response = new HashMap<>();
            response.put("savedCount", entitiesToSave.size());
            response.put("records", savedDetails);
            response.put("status", "SUCCESS");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Database bulk write failed: " + e.getMessage());
            errorResponse.put("status", "ERROR");
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}
