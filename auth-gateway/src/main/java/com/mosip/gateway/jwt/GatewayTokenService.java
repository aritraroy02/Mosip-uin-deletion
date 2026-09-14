package com.mosip.gateway.jwt;

import com.mosip.gateway.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mints the short-lived JWT the deletion service trusts. The UIN travels only
 * inside this token; it is signed with the gateway's private key (RS256) and
 * expires after ttl-seconds (5 minutes), which also bounds the retry window.
 */
@Service
public class GatewayTokenService {

    private final GatewayProperties props;
    private final ResourceLoader resourceLoader;
    private PrivateKey signingKey;

    public GatewayTokenService(GatewayProperties props, ResourceLoader resourceLoader) {
        this.props = props;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() throws Exception {
        signingKey = RsaSigner.loadPrivateKey(
                resourceLoader.getResource(props.getGatewayJwt().getPrivateKey()));
    }

    public record MintedToken(String jwt, long expiresAtEpochSeconds) {}

    public MintedToken mintForUin(String uin) throws Exception {
        var cfg = props.getGatewayJwt();
        long now = System.currentTimeMillis() / 1000L;
        long exp = now + cfg.getTtlSeconds();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", cfg.getIssuer());
        payload.put("aud", cfg.getAudience());
        payload.put("sub", uin);
        payload.put("uin", uin);
        payload.put("iat", now);
        payload.put("exp", exp);
        payload.put("jti", UUID.randomUUID().toString());

        return new MintedToken(RsaSigner.signRs256(header, payload, signingKey), exp);
    }
}
