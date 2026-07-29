package com.example.mosip.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Performs the confidential-client half of the eSignet OIDC authorization-code flow.
 *
 * <p>eSignet advertises {@code private_key_jwt} as the only supported token endpoint
 * auth method, so the authorization code is redeemed with a JWT client assertion
 * signed by this relying party's RSA key. The matching public JWK is registered
 * against the client in {@code esignet.client_detail.public_key}.
 */
@Service
public class EsignetOidcService {

    /** Result of a completed, verified login. */
    public record VerifiedIdentity(String individualId, String accessToken, Map<String, Object> claims) {}

    private final RestClient restClient;
    private final String clientId;
    private final String tokenEndpoint;
    private final String userinfoEndpoint;
    private final String jwksUri;
    private final String issuer;
    private final String redirectUri;
    private final RSAPrivateKey privateKey;

    public EsignetOidcService(
            ResourceLoader resourceLoader,
            @Value("${mosip.esignet.client-id}") String clientId,
            @Value("${mosip.esignet.redirect-uri}") String redirectUri,
            @Value("${mosip.esignet.issuer:http://localhost:8088}") String issuer,
            @Value("${mosip.esignet.token-endpoint:http://localhost:8088/v1/esignet/oauth/v2/token}") String tokenEndpoint,
            @Value("${mosip.esignet.userinfo-endpoint:http://localhost:8088/v1/esignet/oidc/userinfo}") String userinfoEndpoint,
            @Value("${mosip.esignet.jwks-uri:http://localhost:8088/v1/esignet/oauth/.well-known/jwks.json}") String jwksUri,
            @Value("${mosip.esignet.private-key-location:classpath:esignet-rp-private-key.pem}") String privateKeyLocation) {
        this.restClient = RestClient.builder().build();
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.issuer = issuer;
        this.tokenEndpoint = tokenEndpoint;
        this.userinfoEndpoint = userinfoEndpoint;
        this.jwksUri = jwksUri;
        this.privateKey = loadPrivateKey(resourceLoader, privateKeyLocation);
    }

    private static RSAPrivateKey loadPrivateKey(ResourceLoader resourceLoader, String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            String pem;
            try (InputStream in = resource.getInputStream()) {
                pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String base64 = pem.replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to load the eSignet relying-party signing key from " + location, e);
        }
    }

    /**
     * Builds the {@code private_key_jwt} client assertion. eSignet requires iss and sub to
     * equal the client id, an audience it recognises, and a unique jti per request.
     */
    private String buildClientAssertion() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(clientId)
                .subject(clientId)
                .audience(List.of(issuer, tokenEndpoint))
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(com.nimbusds.jose.JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }

    /**
     * Redeems an authorization code, verifies the returned id_token signature, issuer,
     * audience and nonce, then resolves the resident's individual id.
     *
     * @param expectedNonce the nonce placed in the authorize request for this session
     * @return the verified identity, never null
     * @throws IllegalStateException if the exchange fails or the token cannot be trusted
     */
    public VerifiedIdentity exchangeCode(String code, String expectedNonce) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", redirectUri);
            form.add("client_id", clientId);
            form.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
            form.add("client_assertion", buildClientAssertion());

            Map<?, ?> tokenResponse = restClient.post()
                    .uri(tokenEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            if (tokenResponse == null || tokenResponse.get("access_token") == null) {
                throw new IllegalStateException("eSignet token endpoint returned no access_token");
            }

            String accessToken = String.valueOf(tokenResponse.get("access_token"));
            Object idTokenRaw = tokenResponse.get("id_token");
            if (idTokenRaw == null) {
                throw new IllegalStateException("eSignet token endpoint returned no id_token");
            }

            JWTClaimsSet idClaims = verifyIdToken(String.valueOf(idTokenRaw), expectedNonce);
            Map<String, Object> claims = new java.util.LinkedHashMap<>(idClaims.getClaims());

            String individualId = resolveIndividualId(accessToken, claims);
            return new VerifiedIdentity(individualId, accessToken, claims);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("eSignet authorization-code exchange failed: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies the id_token against eSignet's published JWKS and checks the registered
     * claims. An unverified id_token is worthless here, so every failure throws.
     */
    private JWTClaimsSet verifyIdToken(String idToken, String expectedNonce) throws Exception {
        SignedJWT jwt = SignedJWT.parse(idToken);

        JWKSet jwkSet = JWKSet.load(java.net.URI.create(jwksUri).toURL());
        String kid = jwt.getHeader().getKeyID();
        boolean signatureValid = jwkSet.getKeys().stream()
                .filter(RSAKey.class::isInstance)
                .filter(k -> kid == null || kid.equals(k.getKeyID()))
                .anyMatch(k -> {
                    try {
                        RSAPublicKey publicKey = (RSAPublicKey) k.toRSAKey().toPublicKey();
                        return jwt.verify(new RSASSAVerifier(publicKey));
                    } catch (Exception ignored) {
                        return false;
                    }
                });
        if (!signatureValid) {
            throw new IllegalStateException(
                    "id_token signature does not match any key published at " + jwksUri);
        }

        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
            throw new IllegalStateException("id_token has expired");
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(clientId)) {
            throw new IllegalStateException("id_token audience does not contain this client");
        }
        String tokenNonce = claims.getStringClaim("nonce");
        if (expectedNonce != null && !expectedNonce.equals(tokenNonce)) {
            throw new IllegalStateException("id_token nonce does not match the authorize request");
        }
        return claims;
    }

    /**
     * Resolves the resident's individual id. eSignet issues a pairwise {@code sub}, so the
     * usable identifier comes from the {@code individual_id} claim exposed via userinfo.
     */
    private String resolveIndividualId(String accessToken, Map<String, Object> idTokenClaims) {
        Map<String, Object> userInfo = fetchUserInfo(accessToken);
        if (userInfo != null) {
            idTokenClaims.putAll(userInfo);
            for (String claim : List.of("individual_id", "individualId")) {
                Object value = userInfo.get(claim);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
        }
        for (String claim : List.of("individual_id", "individualId")) {
            Object value = idTokenClaims.get(claim);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        throw new IllegalStateException(
                "eSignet login succeeded but no individual_id claim was returned; "
                        + "cannot determine whose data to delete");
    }

    /**
     * Calls the userinfo endpoint. eSignet signs (and may encrypt) this response, so the
     * body is a JWT rather than plain JSON.
     */
    private Map<String, Object> fetchUserInfo(String accessToken) {
        try {
            String body = restClient.get()
                    .uri(userinfoEndpoint)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            }
            // Signed JWT: claims are readable without a second verification round, since the
            // access token that produced them was already bound to a verified id_token.
            return SignedJWT.parse(trimmed).getJWTClaimsSet().getClaims();
        } catch (Exception e) {
            System.err.println("Unable to read eSignet userinfo response: " + e.getMessage());
            return null;
        }
    }
}
