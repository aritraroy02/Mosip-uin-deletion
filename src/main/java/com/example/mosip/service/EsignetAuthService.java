package com.example.mosip.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared MOSIP eSignet OIDC Authorization Code + private_key_jwt client
 * authentication flow, used by both the deletion and registration ("unified
 * login") entry points.
 */
@Service
public class EsignetAuthService {

    private static final String STATE_SESSION_KEY = "esignet_oauth_state";
    private static final String NONCE_SESSION_KEY = "esignet_oauth_nonce";
    private static final String PURPOSE_SESSION_KEY = "esignet_oauth_purpose";

    private final String clientId;
    private final String redirectUri;
    private final String authorizeUrl;
    private final String pluginUrl;
    private final String relyingPartyId;
    private final String tokenEndpoint;
    private final String userInfoEndpoint;

    public EsignetAuthService(
            @Value("${mosip.esignet.client-id:_UgkpFCOsqoxsbLfywjXFuVRYZaHeYK6l0GmxMg3Rg8}") String clientId,
            @Value("${mosip.esignet.redirect-uri:http://localhost:8081/delete/callback}") String redirectUri,
            @Value("${mosip.esignet.authorize-url:http://localhost:3000/authorize}") String authorizeUrl,
            @Value("${mosip.esignet.plugin-url:http://localhost:3000/plugins/sign-in-button-plugin.js}") String pluginUrl,
            @Value("${mosip.esignet.relying-party-id:mock-relying-party-ui}") String relyingPartyId,
            @Value("${mosip.esignet.token-url:http://localhost:8088/v1/esignet/oauth/v2/token}") String tokenEndpoint,
            @Value("${mosip.esignet.userinfo-url:http://localhost:8088/v1/esignet/oidc/userinfo}") String userInfoEndpoint) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.authorizeUrl = authorizeUrl;
        this.pluginUrl = pluginUrl;
        this.relyingPartyId = relyingPartyId;
        this.tokenEndpoint = tokenEndpoint;
        this.userInfoEndpoint = userInfoEndpoint;
    }

    public String getClientId() {
        return clientId;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public String getAuthorizeUrl() {
        return authorizeUrl;
    }

    public String getPluginUrl() {
        return pluginUrl;
    }

    /**
     * Builds the eSignet authorize redirect URL and stashes state/nonce/purpose
     * (e.g. "register" or "delete") in the session so the shared callback can
     * validate the response and route it back to the right flow.
     */
    public String buildAuthorizeRedirectUrl(HttpSession session, String purpose) {
        String state = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        session.setAttribute(STATE_SESSION_KEY, state);
        session.setAttribute(NONCE_SESSION_KEY, nonce);
        session.setAttribute(PURPOSE_SESSION_KEY, purpose);

        // individual_id is not covered by any standard OIDC scope, so it must be
        // requested explicitly via the claims request parameter and marked
        // essential. Note: the local mock eSignet/mock-identity-system stack does
        // not actually disclose this claim (see resolveIdentity's pairwise-subject
        // fallback) - real MOSIP deployments gate raw UIN disclosure behind
        // partner policy that this lightweight mock stack doesn't implement.
        String claimsRequest = "{\"userinfo\":{\"individual_id\":{\"essential\":true}},\"id_token\":{}}";

        return String.format(
                "%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&state=%s&nonce=%s&claims=%s",
                authorizeUrl,
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
                URLEncoder.encode("openid profile", StandardCharsets.UTF_8),
                URLEncoder.encode(state, StandardCharsets.UTF_8),
                URLEncoder.encode(nonce, StandardCharsets.UTF_8),
                URLEncoder.encode(claimsRequest, StandardCharsets.UTF_8));
    }

    /** Outcome of validating and resolving an eSignet OAuth callback. */
    public record CallbackResult(boolean success, String errorMessage, String purpose, String subject) {
    }

    /**
     * Validates the callback's state and exchanges the authorization code for an
     * access token, resolving the authenticated subject (individual_id/uin claim
     * if disclosed, otherwise the pairwise `sub`). Per design Section 5.2, there
     * is no unauthenticated fallback identity - a failure returns success=false.
     */
    public CallbackResult handleCallback(String code, String state, String error, HttpSession session) {
        String expectedState = (String) session.getAttribute(STATE_SESSION_KEY);
        String purpose = (String) session.getAttribute(PURPOSE_SESSION_KEY);
        session.removeAttribute(STATE_SESSION_KEY);
        session.removeAttribute(NONCE_SESSION_KEY);
        session.removeAttribute(PURPOSE_SESSION_KEY);

        if (error != null && !error.trim().isEmpty()) {
            return new CallbackResult(false, "eSignet authentication was not completed: " + error, purpose, null);
        }
        if (code == null || code.trim().isEmpty()) {
            return new CallbackResult(false,
                    "eSignet authentication failed: no authorization code was returned. No action was taken.",
                    purpose, null);
        }
        if (expectedState == null || !expectedState.equals(state)) {
            return new CallbackResult(false,
                    "eSignet authentication failed: the session state did not match. Please try again.", purpose,
                    null);
        }

        String subject = resolveAuthenticatedSubject(code.trim());
        if (subject == null || subject.isEmpty()) {
            return new CallbackResult(false,
                    "eSignet authentication failed: could not resolve your identity. No action was taken.", purpose,
                    null);
        }
        return new CallbackResult(true, null, purpose, subject);
    }

    /**
     * Exchanges the authorization code for an access token, then calls eSignet's
     * /userinfo endpoint. Prefers individual_id/uin claims if a deployment ever
     * discloses them; otherwise returns the pairwise `sub` for the caller to
     * resolve via {@link #computePairwiseSubject}.
     */
    private String resolveAuthenticatedSubject(String code) {
        try {
            RestClient client = RestClient.create();
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "authorization_code");
            formData.add("client_id", clientId);
            formData.add("redirect_uri", redirectUri);
            formData.add("code", code);
            formData.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
            formData.add("client_assertion", buildClientAssertion(tokenEndpoint));

            Map<?, ?> tokenResponse = client.post()
                    .uri(tokenEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(Map.class);

            if (tokenResponse == null || tokenResponse.get("access_token") == null) {
                System.err.println("eSignet token exchange did not return an access_token.");
                return null;
            }
            String accessToken = tokenResponse.get("access_token").toString();

            try {
                String userInfoJwt = client.get()
                        .uri(userInfoEndpoint)
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(String.class);
                String claim = extractJwtClaim(userInfoJwt, "individual_id");
                if (claim == null) claim = extractJwtClaim(userInfoJwt, "uin");
                if (claim == null) claim = extractJwtClaim(userInfoJwt, "sub");
                if (claim != null && !claim.isEmpty()) {
                    return claim;
                }
            } catch (Exception e) {
                System.err.println("eSignet /userinfo call failed, falling back to id_token claims: " + e.getMessage());
            }

            Object idTokenObj = tokenResponse.get("id_token");
            if (idTokenObj != null) {
                String idToken = idTokenObj.toString();
                String claim = extractJwtClaim(idToken, "individual_id");
                if (claim == null) claim = extractJwtClaim(idToken, "uin");
                if (claim == null) claim = extractJwtClaim(idToken, "sub");
                return claim;
            }
        } catch (Exception e) {
            System.err.println("eSignet authentication resolution failed: " + e.getMessage());
        }
        return null;
    }

    private String extractJwtClaim(String jwt, String claimName) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            String payload = new String(decoded, StandardCharsets.UTF_8);
            JsonNode json = new ObjectMapper().readTree(payload);
            if (json.has(claimName) && !json.get(claimName).asText().isEmpty()) {
                return json.get(claimName).asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Builds a signed private_key_jwt client assertion (RFC 7523) using this RP's
     * private key, matching the "private_key_jwt" auth_method eSignet has this
     * client registered under.
     */
    private String buildClientAssertion(String audience) throws Exception {
        PrivateKey privateKey = loadRpPrivateKey();
        long nowSeconds = System.currentTimeMillis() / 1000L;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", clientId);
        payload.put("sub", clientId);
        payload.put("aud", audience);
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("iat", nowSeconds);
        payload.put("exp", nowSeconds + 300);

        ObjectMapper mapper = new ObjectMapper();
        String signingInput = base64UrlNoPad(mapper.writeValueAsBytes(header)) + "."
                + base64UrlNoPad(mapper.writeValueAsBytes(payload));

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));

        return signingInput + "." + base64UrlNoPad(signature.sign());
    }

    private PrivateKey loadRpPrivateKey() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/esignet-rp-private-key.pem")) {
            if (in == null) {
                throw new IllegalStateException("esignet-rp-private-key.pem not found on classpath");
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        }
    }

    private String base64UrlNoPad(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /**
     * Reproduces the pairwise `sub` MOSIP's mock eSignet/mock-identity-system
     * issues for a given individualId: base64url(SHA3-256(individualId +
     * relyingPartyId)). Confirmed against a live mock-identity-system response
     * (2026-08-19) - the deployed mock stack never discloses individual_id as a
     * userinfo claim (that requires MOSIP Policy Manager partner-claim
     * authorization this lightweight stack doesn't run), but the pairwise
     * subject IS deterministic, so we can compute and store it ourselves at
     * registration time and reverse-match it on login instead.
     */
    public String computePairwiseSubject(String individualId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA3-256");
            byte[] hash = digest.digest((individualId + relyingPartyId).getBytes(StandardCharsets.UTF_8));
            return base64UrlNoPad(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute pairwise subject", e);
        }
    }
}
