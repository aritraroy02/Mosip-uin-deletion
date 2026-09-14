package com.mosip.gateway.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Minimal RS256 JWT signing and JWT-claim decoding, shared by the eSignet
 * private_key_jwt client assertion and the gateway's own 5-minute token. Kept
 * dependency-free (no JOSE library) to stay small.
 */
public final class RsaSigner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private RsaSigner() {}

    public static PrivateKey loadPrivateKey(Resource pem) throws Exception {
        String text = new String(pem.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(text);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Signs {header}.{payload} with RS256 and returns the compact JWT. */
    public static String signRs256(Map<String, Object> header, Map<String, Object> payload,
                                   PrivateKey key) throws Exception {
        String signingInput = encode(header) + "." + encode(payload);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + B64URL.encodeToString(sig.sign());
    }

    /** Reads one claim from a JWS/JWT payload without verifying (caller trusts the source). */
    public static String claim(String jwt, String name) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            JsonNode json = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return json.hasNonNull(name) && !json.get(name).asText().isEmpty()
                    ? json.get(name).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(Map<String, Object> m) throws Exception {
        return B64URL.encodeToString(MAPPER.writeValueAsBytes(m));
    }
}
