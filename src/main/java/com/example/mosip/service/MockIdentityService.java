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

    public MockIdentityService(@Value("${mock-identity.base-url:http://localhost:8082/v1/mock-identity-system}") String mockIdentityBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(mockIdentityBaseUrl).build();
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
