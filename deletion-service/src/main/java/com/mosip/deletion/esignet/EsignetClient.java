package com.mosip.deletion.esignet;

import com.mosip.deletion.config.Databases;
import com.mosip.deletion.config.DeletionProperties;
import com.mosip.deletion.console.ConsoleAudit;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The eSignet relying party, now living inside the deletion service itself.
 *
 * Turns an authorization code into the resident's PLAIN UIN in three moves:
 *
 *   1. POST /oauth/v2/token  authenticating as the registered client with a
 *      private_key_jwt assertion signed by our RP key -> access_token
 *   2. GET  /oidc/userinfo   with that access token
 *   3. translate whatever identifier came back into the UIN
 *
 * Step 3 normally costs nothing: the identity system is configured to emit the
 * UIN as the individual_id claim (see MOSIP_MOCK_IDA_IDENTITY_OPENID_CLAIMS_MAPPING
 * in esignet/docker-compose), so userinfo hands over the plain UIN and there is
 * nothing to translate.
 *
 * The kyc_auth fallback below exists for deployments without that mapping. There
 * userinfo carries only a pairwise pseudonym (the `sub`, a
 * partner_specific_user_token) and the UIN has to be recovered from the identity
 * system's own psu_token -> individual_id record. Which path was taken is printed
 * in the terminal on every request, so it is never a guess.
 */
@Service
public class EsignetClient {

    public static class EsignetException extends RuntimeException {
        public EsignetException(String message) { super(message); }
    }

    private static final Logger log = LoggerFactory.getLogger(EsignetClient.class);

    private final DeletionProperties props;
    private final ResourceLoader resourceLoader;
    private final JdbcTemplate mockIdentity;
    private final ConsoleAudit console;
    private final RestClient http = RestClient.create();
    private PrivateKey rpKey;

    public EsignetClient(DeletionProperties props, ResourceLoader resourceLoader, Databases db,
                         ConsoleAudit console) {
        this.props = props;
        this.resourceLoader = resourceLoader;
        this.console = console;
        this.mockIdentity = db.has("mockidentity") ? db.db("mockidentity") : null;
    }

    @PostConstruct
    void init() throws Exception {
        rpKey = RsaSigner.loadPrivateKey(
                resourceLoader.getResource(props.getEsignet().getRpPrivateKey()));
    }

    /** Authorization code in, plain UIN out. Throws if the code cannot be trusted. */
    public String resolveUin(String code, String redirectUri, String codeVerifier) {
        console.authStart(code, redirectUri);
        String accessToken = exchangeCode(code, redirectUri, codeVerifier);
        String subject = fetchSubject(accessToken);

        if (subject != null && subject.matches("\\d+")) {
            // eSignet handed over the plain UIN as the individual_id claim.
            console.uinResolved(subject,
                    "individual_id claim (eSignet passed the plain UIN directly)");
            return subject;
        }
        String uin = resolvePsut(subject);
        if (uin == null) {
            throw new EsignetException("authenticated, but the UIN could not be resolved "
                    + "from the pseudonym returned by userinfo");
        }
        console.uinResolved(uin,
                "kyc_auth fallback (userinfo carried only a pairwise pseudonym)");
        return uin;
    }

    private String exchangeCode(String code, String redirectUri, String codeVerifier) {
        var cfg = props.getEsignet();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", cfg.getClientId());
        form.add("redirect_uri", redirectUri);
        form.add("client_assertion_type",
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.add("client_assertion", clientAssertion(cfg.getTokenUrl()));
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            form.add("code_verifier", codeVerifier);        // only when PKCE is on
        }

        Map<?, ?> token;
        try {
            token = http.post().uri(cfg.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
        } catch (Exception e) {
            throw new EsignetException("token exchange failed: " + e.getMessage());
        }
        if (token == null || token.get("access_token") == null) {
            throw new EsignetException("token exchange returned no access_token");
        }
        console.tokenExchange(cfg.getTokenUrl(), cfg.getClientId(), token);
        return token.get("access_token").toString();
    }

    /** The first identifier userinfo will give us: individual_id, uin, then sub. */
    private String fetchSubject(String accessToken) {
        String userInfoJwt;
        try {
            userInfoJwt = http.get().uri(props.getEsignet().getUserinfoUrl())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve().body(String.class);
        } catch (Exception e) {
            throw new EsignetException("/userinfo call failed: " + e.getMessage());
        }
        console.userinfo(props.getEsignet().getUserinfoUrl(), RsaSigner.decodeClaims(userInfoJwt));
        for (String claim : List.of("individual_id", "uin", "sub")) {
            String v = RsaSigner.claim(userInfoJwt, claim);
            if (v != null) {
                return v;
            }
        }
        throw new EsignetException("/userinfo carried no usable identifier");
    }

    /** psu_token -> individual_id, read from the identity system's own record. */
    private String resolvePsut(String psuToken) {
        if (psuToken == null || mockIdentity == null) {
            return null;
        }
        List<String> rows = mockIdentity.queryForList(
                "SELECT individual_id FROM mockidentitysystem.kyc_auth "
                + "WHERE partner_specific_user_token = ? "
                + "ORDER BY response_time DESC LIMIT 1",
                String.class, psuToken);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** private_key_jwt: we prove we are the registered client by signing, not by a secret. */
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
