package com.mosip.deletion.esignet;

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
 * Minimal RS256 signing and unverified claim reading, used only to build the
 * private_key_jwt client assertion eSignet requires and to read claims out of
 * the userinfo response. Deliberately dependency-free: no JOSE library is
 * needed for one signature and one base64 decode.
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
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(text)));
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

    /**
     * Reads one claim from a JWS payload WITHOUT verifying the signature. That
     * is safe here only because the value came straight from eSignet over a
     * direct call authenticated with our own client assertion.
     */
    public static String claim(String jwt, String name) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            JsonNode json = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return json.hasNonNull(name) && !json.get(name).asText().isEmpty()
                    ? json.get(name).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes a JWS payload to its JSON claims, for logging during a deletion. */
    public static String decodeClaims(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return "<not a JWT>";
            }
            return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<undecodable>";
        }
    }

    private static String encode(Map<String, Object> m) throws Exception {
        return B64URL.encodeToString(MAPPER.writeValueAsBytes(m));
    }
}
