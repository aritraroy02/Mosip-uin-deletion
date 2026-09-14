package com.mosip.deletion.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mosip.deletion.config.DeletionProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Verifies the short-lived RS256 JWT minted by the auth-gateway after a
 * successful eSignet authentication.
 *
 * Checks, in order: RS256 signature against the gateway's public key, token not
 * expired, issuer and audience match configuration. On success the UIN claim is
 * returned. There is no other way into the deletion API -- a valid, unexpired
 * token is the sole authorisation to delete (consent was proven at eSignet).
 */
@Component
public class JwtVerifier {

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) { super(message); }
    }

    private final DeletionProperties props;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper mapper = new ObjectMapper();
    private PublicKey publicKey;

    public JwtVerifier(DeletionProperties props, ResourceLoader resourceLoader) {
        this.props = props;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void load() throws Exception {
        Resource res = resourceLoader.getResource(props.getSecurity().getJwt().getPublicKey());
        String pem = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        this.publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
    }

    /** Verifies the token and returns the {@code uin} claim, or throws. */
    public String verifyAndGetUin(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("malformed token");
        }
        // 1. signature over header.payload
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new InvalidTokenException("bad signature");
            }
        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidTokenException("signature check failed: " + e.getMessage());
        }

        JsonNode claims;
        try {
            claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            throw new InvalidTokenException("unreadable claims");
        }

        // 2. expiry (5-minute window set by the gateway)
        long now = System.currentTimeMillis() / 1000L;
        if (!claims.has("exp") || claims.get("exp").asLong() < now) {
            throw new InvalidTokenException("token expired");
        }
        // 3. issuer / audience
        var jwt = props.getSecurity().getJwt();
        if (jwt.getIssuer() != null && !jwt.getIssuer().equals(claims.path("iss").asText())) {
            throw new InvalidTokenException("wrong issuer");
        }
        if (jwt.getAudience() != null && !jwt.getAudience().equals(claims.path("aud").asText())) {
            throw new InvalidTokenException("wrong audience");
        }
        // 4. the UIN claim
        String uin = claims.path("uin").asText(null);
        if (uin == null || uin.isBlank()) {
            uin = claims.path("sub").asText(null);
        }
        if (uin == null || uin.isBlank()) {
            throw new InvalidTokenException("no uin claim");
        }
        return uin;
    }
}
