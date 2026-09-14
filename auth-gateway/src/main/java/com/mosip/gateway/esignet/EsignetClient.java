package com.mosip.gateway.esignet;

import com.mosip.gateway.config.GatewayProperties;
import com.mosip.gateway.jwt.RsaSigner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Talks to eSignet: exchanges the authorization code for tokens (authenticating
 * as the RP with private_key_jwt) and calls /userinfo to resolve the UIN.
 */
@Service
public class EsignetClient {

    public static class EsignetException extends RuntimeException {
        public EsignetException(String message) { super(message); }
    }

    private static final Logger log = LoggerFactory.getLogger(EsignetClient.class);

    private final GatewayProperties props;
    private final ResourceLoader resourceLoader;
    private final RestClient http = RestClient.create();
    private PrivateKey rpKey;

    public EsignetClient(GatewayProperties props, ResourceLoader resourceLoader) {
        this.props = props;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() throws Exception {
        rpKey = RsaSigner.loadPrivateKey(
                resourceLoader.getResource(props.getEsignet().getRpPrivateKey()));
    }

    /** Exchange the code for tokens and return the resolved UIN (individual_id). */
    public String resolveUin(String code, String redirectUri, String codeVerifier) {
        var esignet = props.getEsignet();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", esignet.getClientId());
        form.add("redirect_uri", redirectUri);
        form.add("client_assertion_type",
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.add("client_assertion", clientAssertion(esignet.getTokenUrl()));
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            form.add("code_verifier", codeVerifier);      // present only if PKCE is on
        }

        Map<?, ?> token;
        try {
            token = http.post().uri(esignet.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
        } catch (Exception e) {
            throw new EsignetException("token exchange failed: " + e.getMessage());
        }
        if (token == null || token.get("access_token") == null) {
            throw new EsignetException("token exchange returned no access_token");
        }
        String accessToken = token.get("access_token").toString();
        log.info("eSignet /token OK (access_token received), calling /userinfo ...");

        // Preferred: /userinfo (design section 5.2). Fall back to id_token claims.
        String uin = null;
        try {
            String userInfoJwt = http.get().uri(esignet.getUserinfoUrl())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve().body(String.class);
            log.info("eSignet /userinfo response (decoded claims): {}", decodeClaims(userInfoJwt));
            uin = firstClaim(userInfoJwt);
            log.info("resolved subject from userinfo: {}", uin);
        } catch (Exception e) {
            log.warn("/userinfo call failed, falling back to id_token: {}", e.getMessage());
        }
        if (uin == null && token.get("id_token") != null) {
            uin = firstClaim(token.get("id_token").toString());
        }
        if (uin == null || uin.isBlank()) {
            throw new EsignetException("could not resolve UIN from userinfo/id_token");
        }
        return uin;
    }

    private static String firstClaim(String jwt) {
        String v = RsaSigner.claim(jwt, "individual_id");
        if (v == null) v = RsaSigner.claim(jwt, "uin");
        if (v == null) v = RsaSigner.claim(jwt, "sub");
        return v;
    }

    /** Decode a JWS payload to its JSON claims string, for logging (dev aid). */
    private static String decodeClaims(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return "<not a JWT: " + jwt + ">";
            return new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<undecodable userinfo>";
        }
    }

    private String clientAssertion(String audience) {
        try {
            long now = System.currentTimeMillis() / 1000L;
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "RS256");
            header.put("typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", props.getEsignet().getClientId());
            payload.put("sub", props.getEsignet().getClientId());
            payload.put("aud", audience);
            payload.put("iat", now);
            payload.put("exp", now + 300);
            payload.put("jti", UUID.randomUUID().toString());
            return RsaSigner.signRs256(header, payload, rpKey);
        } catch (Exception e) {
            throw new EsignetException("failed to build client assertion: " + e.getMessage());
        }
    }
}
