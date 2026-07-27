package com.example.mosip.service;

import com.example.mosip.dto.UserRegistrationDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The local mock-identity-system is the authoritative store for web registrations.
 */
@Service
public class MockIdentityService {

    private final RestClient restClient;
    private final String mockIdentityDbUrl;
    private final String mockIdentityDbUsername;
    private final String mockIdentityDbPassword;

    public MockIdentityService(
            @Value("${mock-identity.base-url:http://localhost:8082/v1/mock-identity-system}") String mockIdentityBaseUrl,
            @Value("${mock-identity.datasource.url:jdbc:postgresql://localhost:5455/mosip_mockidentitysystem}") String mockIdentityDbUrl,
            @Value("${mock-identity.datasource.username:postgres}") String mockIdentityDbUsername,
            @Value("${mock-identity.datasource.password:postgres}") String mockIdentityDbPassword) {
        this.restClient = RestClient.builder().baseUrl(mockIdentityBaseUrl).build();
        this.mockIdentityDbUrl = mockIdentityDbUrl;
        this.mockIdentityDbUsername = mockIdentityDbUsername;
        this.mockIdentityDbPassword = mockIdentityDbPassword;
    }

    /**
     * Checks if an identity exists in the local Mock Identity System by Individual ID or UIN.
     */
    public boolean existsIdentity(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return false;
        }
        String id = identifier.trim();
        // 1. Try REST API endpoint
        try {
            Map<?, ?> response = restClient.get()
                    .uri("/identity/{individualId}", id)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.get("response") != null && !hasErrors(response)) {
                return true;
            }
        } catch (Exception ignored) {}

        // 2. Fallback to direct DB query
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(mockIdentityDbUrl, mockIdentityDbUsername, mockIdentityDbPassword)) {
            String sql = "SELECT COUNT(*) FROM mockidentitysystem.mock_identity WHERE individual_id = ? OR identity_json LIKE ? OR identity_json LIKE ?";
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, "%\"individualId\":\"" + id + "\"%");
                stmt.setString(3, "%\"uin\":\"" + id + "\"%");
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Mock identity exists check error for '" + id + "': " + e.getMessage());
        }
        return false;
    }

    /**
     * Retrieves raw identity details from the local Mock Identity System.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getIdentityDetails(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return null;
        }
        String id = identifier.trim();
        // 1. Try REST API
        try {
            Map<?, ?> response = restClient.get()
                    .uri("/identity/{individualId}", id)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.get("response") instanceof Map<?, ?> details) {
                return (Map<String, Object>) details;
            }
        } catch (Exception ignored) {}

        // 2. Fallback to DB
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(mockIdentityDbUrl, mockIdentityDbUsername, mockIdentityDbPassword)) {
            String sql = "SELECT identity_json FROM mockidentitysystem.mock_identity WHERE individual_id = ? OR identity_json LIKE ? OR identity_json LIKE ? LIMIT 1";
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, "%\"individualId\":\"" + id + "\"%");
                stmt.setString(3, "%\"uin\":\"" + id + "\"%");
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String json = rs.getString("identity_json");
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        return mapper.readValue(json, Map.class);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Mock identity details fetch error for '" + id + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Purges identity data from the local esignet-mock-services PostgreSQL database matching the given ID or UIN.
     */
    public boolean deleteIdentity(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return false;
        }
        String id = identifier.trim();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(mockIdentityDbUrl, mockIdentityDbUsername, mockIdentityDbPassword)) {
            try (java.sql.PreparedStatement stmtClaims = conn.prepareStatement(
                    "DELETE FROM mockidentitysystem.verified_claim WHERE individual_id = ?")) {
                stmtClaims.setString(1, id);
                stmtClaims.executeUpdate();
            }

            String sql = "DELETE FROM mockidentitysystem.mock_identity WHERE individual_id = ? OR identity_json LIKE ? OR identity_json LIKE ?";
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, "%\"individualId\":\"" + id + "\"%");
                stmt.setString(3, "%\"uin\":\"" + id + "\"%");
                int affected = stmt.executeUpdate();
                return affected > 0;
            }
        } catch (Exception e) {
            System.err.println("Mock identity purge error for identifier '" + id + "': " + e.getMessage());
            return false;
        }
    }

    public void createIdentity(UserRegistrationDto registration) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("individualId", registration.getIndividualId());
        request.put("fullName", languageValue(registration.getName()));
        request.put("givenName", languageValue(registration.getGivenName()));
        request.put("familyName", languageValue(registration.getFamilyName()));
        request.put("gender", languageValue(registration.getGender()));
        request.put("locality", languageValue(registration.getLocality()));
        request.put("region", languageValue(registration.getRegion()));
        request.put("country", languageValue(registration.getCountry()));
        request.put("pin", registration.getPin());
        request.put("preferredLang", registration.getPreferredLang());
        request.put("dateOfBirth", registration.getDob());
        request.put("postalCode", registration.getPostalCode());
        request.put("encodedPhoto", encodePhoto(registration));
        request.put("email", registration.getEmail());
        request.put("phone", registration.getPhone());

        Map<String, Object> payload = Map.of(
                "requestTime", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                "request", request);

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/identity")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            if (response == null || hasErrors(response)) {
                throw new MockIdentityServiceException(errorMessage(response));
            }
        } catch (RestClientException ex) {
            throw new MockIdentityServiceException("Could not store the identity in the local mock service.", ex);
        }
    }

    private List<Map<String, String>> languageValue(String value) {
        return List.of(Map.of("language", "en", "value", value));
    }

    private String encodePhoto(UserRegistrationDto registration) {
        if (registration.getProfileImage() == null || registration.getProfileImage().isEmpty()) {
            return "";
        }
        try {
            BufferedImage source = javax.imageio.ImageIO.read(registration.getProfileImage().getInputStream());
            if (source == null) {
                throw new MockIdentityServiceException("The profile photo must be a valid image.");
            }
            int largestSide = Math.max(source.getWidth(), source.getHeight());
            double scale = Math.min(1.0, 256.0 / largestSide);
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(resized, "jpg", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException ex) {
            throw new MockIdentityServiceException("Could not read the profile photo.", ex);
        }
    }

    private boolean hasErrors(Map<?, ?> response) {
        Object errors = response.get("errors");
        return errors instanceof List<?> errorList && !errorList.isEmpty();
    }

    private String errorMessage(Map<?, ?> response) {
        if (response == null) {
            return "The mock identity service returned an empty response.";
        }
        Object errors = response.get("errors");
        if (errors instanceof List<?> errorList && !errorList.isEmpty() && errorList.get(0) instanceof Map<?, ?> error) {
            Object message = error.get("message");
            if (message != null) {
                return "The mock identity service rejected this registration: " + message;
            }
        }
        return "The mock identity service rejected this registration.";
    }

    public static class MockIdentityServiceException extends RuntimeException {
        public MockIdentityServiceException(String message) {
            super(message);
        }

        public MockIdentityServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
