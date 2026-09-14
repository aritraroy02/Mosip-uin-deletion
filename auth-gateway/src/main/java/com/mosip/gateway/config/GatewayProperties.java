package com.mosip.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public class GatewayProperties {

    private List<String> allowedOrigins;
    private Esignet esignet = new Esignet();
    private GatewayJwt gatewayJwt = new GatewayJwt();
    private DeletionService deletionService = new DeletionService();
    private MockIdentity mockIdentity = new MockIdentity();

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v; }
    public Esignet getEsignet() { return esignet; }
    public void setEsignet(Esignet v) { this.esignet = v; }
    public GatewayJwt getGatewayJwt() { return gatewayJwt; }
    public void setGatewayJwt(GatewayJwt v) { this.gatewayJwt = v; }
    public DeletionService getDeletionService() { return deletionService; }
    public void setDeletionService(DeletionService v) { this.deletionService = v; }
    public MockIdentity getMockIdentity() { return mockIdentity; }
    public void setMockIdentity(MockIdentity v) { this.mockIdentity = v; }

    public static class MockIdentity {
        private String url;
        private String username;
        private String password;
        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
    }

    public static class Esignet {
        private String tokenUrl;
        private String userinfoUrl;
        private String clientId;
        private String rpPrivateKey;
        public String getTokenUrl() { return tokenUrl; }
        public void setTokenUrl(String v) { this.tokenUrl = v; }
        public String getUserinfoUrl() { return userinfoUrl; }
        public void setUserinfoUrl(String v) { this.userinfoUrl = v; }
        public String getClientId() { return clientId; }
        public void setClientId(String v) { this.clientId = v; }
        public String getRpPrivateKey() { return rpPrivateKey; }
        public void setRpPrivateKey(String v) { this.rpPrivateKey = v; }
    }

    public static class GatewayJwt {
        private String privateKey;
        private String issuer;
        private String audience;
        private long ttlSeconds = 300;
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String v) { this.privateKey = v; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String v) { this.issuer = v; }
        public String getAudience() { return audience; }
        public void setAudience(String v) { this.audience = v; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long v) { this.ttlSeconds = v; }
    }

    public static class DeletionService {
        private String executeUrl;
        public String getExecuteUrl() { return executeUrl; }
        public void setExecuteUrl(String v) { this.executeUrl = v; }
    }
}
