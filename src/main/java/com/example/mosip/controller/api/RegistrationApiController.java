package com.example.mosip.controller.api;

import com.example.mosip.entity.basic.UserBasicDetails;
import com.example.mosip.repository.basic.UserBasicDetailsRepository;
import com.example.mosip.entity.hashing.UserUinHash;
import com.example.mosip.repository.hashing.UserUinHashRepository;
import com.example.mosip.entity.parent.UserParentDetails;
import com.example.mosip.repository.parent.UserParentDetailsRepository;
import com.example.mosip.entity.basic.UserDataLocation;
import com.example.mosip.repository.basic.UserDataLocationRepository;
import com.example.mosip.service.SaltModuloHashService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api")
public class RegistrationApiController {

    private final UserBasicDetailsRepository userBasicDetailsRepository;
    private final UserUinHashRepository userUinHashRepository;
    private final UserParentDetailsRepository userParentDetailsRepository;
    private final UserDataLocationRepository userDataLocationRepository;
    private final SaltModuloHashService saltModuloHashService;

    public RegistrationApiController(UserBasicDetailsRepository userBasicDetailsRepository,
                                     UserUinHashRepository userUinHashRepository,
                                     UserParentDetailsRepository userParentDetailsRepository,
                                     UserDataLocationRepository userDataLocationRepository,
                                     SaltModuloHashService saltModuloHashService) {
        this.userBasicDetailsRepository = userBasicDetailsRepository;
        this.userUinHashRepository = userUinHashRepository;
        this.userParentDetailsRepository = userParentDetailsRepository;
        this.userDataLocationRepository = userDataLocationRepository;
        this.saltModuloHashService = saltModuloHashService;
    }

    /**
     * Unified Single Registration API
     * Endpoint: POST /api/register
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerUser(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String phone = (String) request.get("phone");
        String email = (String) request.get("email");
        String dob = (String) request.get("dob");
        String nationality = (String) request.get("nationality");
        Boolean consent = (Boolean) request.get("consent");
        String uin = (String) request.get("uin");
        String profileImage = (String) request.get("profileImage");
        String fatherName = (String) request.get("fatherName");
        String motherName = (String) request.get("motherName");

        if (name == null || phone == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Both 'name' and 'phone' fields are required.");
            errorResponse.put("status", "FAILED");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        // Generate clean User ID if not provided
        String userId = "USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Generate 10-digit UIN if not provided
        if (uin == null || uin.trim().isEmpty()) {
            long randomUin = ThreadLocalRandom.current().nextLong(1000000000L, 10000000000L);
            uin = String.valueOf(randomUin);
        }

        String hashedUin = saltModuloHashService.hash(uin);
        String individualIdHash = saltModuloHashService.hash(userId);

        // 1. Persist demographic details to primary database
        UserBasicDetails basicDetails = new UserBasicDetails(userId, name, phone);

        // 2. Persist salt-modulo hashes to hashing database
        //    individual_id_hash = salt-modulo hash of the individual ID (userId)
        //    uin_salted_hash     = salt-modulo hash of the UIN
        UserUinHash uinHash = new UserUinHash(userId, individualIdHash, hashedUin);

        try {
            userBasicDetailsRepository.save(basicDetails);
            userUinHashRepository.save(uinHash);

            // 3. Persist father/mother names to the parent details database
            saveParentDetails(userId, fatherName, motherName);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("userId", userId);
            response.put("name", name);
            response.put("phone", phone);
            response.put("email", email != null ? email : "");
            response.put("dob", dob != null ? dob : "");
            response.put("nationality", nationality != null ? nationality : "");
            response.put("consent", consent != null ? consent : false);
            response.put("fatherName", fatherName != null ? fatherName : "");
            response.put("motherName", motherName != null ? motherName : "");
            response.put("uin", uin);
            response.put("individualIdHash", individualIdHash);
            response.put("uinSaltedHash", hashedUin);
            response.put("profileImage", profileImage != null ? profileImage : "");
            response.put("status", "SUCCESS");

            // Record data location registry for this user (API flow)
            try {
                UserDataLocation location = new UserDataLocation(
                        userId, hashedUin, true, true, true, false);
                userDataLocationRepository.save(location);
            } catch (Exception locEx) {
                System.err.println("Failed to save data-location for API user: " + locEx.getMessage());
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Database write failed: " + e.getMessage());
            errorResponse.put("status", "ERROR");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Persists a user's father/mother names to the parent details database.
     * This is the single owner of writes to that database — both the REST API
     * and the web form flow route parent data through here.
     *
     * @return the persisted entity
     */
    public UserParentDetails saveParentDetails(String userId, String fatherName, String motherName) {
        UserParentDetails parentDetails = new UserParentDetails(
                userId,
                fatherName != null ? fatherName : "",
                motherName != null ? motherName : "");
        return userParentDetailsRepository.save(parentDetails);
    }

    /**
     * Unified Bulk Registration API
     * Endpoint: POST /api/register/bulk
     */
    @PostMapping("/register/bulk")
    public ResponseEntity<Map<String, Object>> registerBulkUsers(@RequestBody List<Map<String, Object>> requestList) {
        if (requestList == null || requestList.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Request list cannot be empty.");
            errorResponse.put("status", "FAILED");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        List<UserBasicDetails> basicDetailsList = new ArrayList<>();
        List<UserUinHash> uinHashList = new ArrayList<>();
        List<UserParentDetails> parentDetailsList = new ArrayList<>();
        List<Map<String, Object>> successRecords = new ArrayList<>();

        for (Map<String, Object> request : requestList) {
            String name = (String) request.get("name");
            String phone = (String) request.get("phone");
            String email = (String) request.get("email");
            String dob = (String) request.get("dob");
            String nationality = (String) request.get("nationality");
            Boolean consent = (Boolean) request.get("consent");
            String uin = (String) request.get("uin");
            String profileImage = (String) request.get("profileImage");
            String fatherName = (String) request.get("fatherName");
            String motherName = (String) request.get("motherName");

            if (name != null && phone != null) {
                String userId = "USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                if (uin == null || uin.trim().isEmpty()) {
                    long randomUin = ThreadLocalRandom.current().nextLong(1000000000L, 10000000000L);
                    uin = String.valueOf(randomUin);
                }
                String hashedUin = saltModuloHashService.hash(uin);
                String individualIdHash = saltModuloHashService.hash(userId);

                basicDetailsList.add(new UserBasicDetails(userId, name, phone));
                uinHashList.add(new UserUinHash(userId, individualIdHash, hashedUin));
                parentDetailsList.add(new UserParentDetails(userId,
                        fatherName != null ? fatherName : "",
                        motherName != null ? motherName : ""));

                Map<String, Object> record = new LinkedHashMap<>();
                record.put("userId", userId);
                record.put("name", name);
                record.put("phone", phone);
                record.put("email", email != null ? email : "");
                record.put("dob", dob != null ? dob : "");
                record.put("nationality", nationality != null ? nationality : "");
                record.put("consent", consent != null ? consent : false);
                record.put("fatherName", fatherName != null ? fatherName : "");
                record.put("motherName", motherName != null ? motherName : "");
                record.put("uin", uin);
                record.put("individualIdHash", individualIdHash);
                record.put("uinSaltedHash", hashedUin);
                record.put("profileImage", profileImage != null ? profileImage : "");
                successRecords.add(record);
            }
        }

        try {
            userBasicDetailsRepository.saveAll(basicDetailsList);
            userUinHashRepository.saveAll(uinHashList);
            userParentDetailsRepository.saveAll(parentDetailsList);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("savedCount", successRecords.size());
            response.put("records", successRecords);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Bulk database write failed: " + e.getMessage());
            errorResponse.put("status", "ERROR");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
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
