package com.mosip.deletion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * All environment-specific configuration for the deletion service
 * (design doc section 16 -- nothing hard-coded).
 */
@ConfigurationProperties(prefix = "app")
public class DeletionProperties {

    private Map<String, Ds> datasources;
    private Minio minio = new Minio();
    private Deletion deletion = new Deletion();
    private Security security = new Security();
    private Esignet esignet = new Esignet();
    private java.util.List<String> allowedOrigins;

    public Esignet getEsignet() { return esignet; }
    public void setEsignet(Esignet esignet) { this.esignet = esignet; }
    public java.util.List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(java.util.List<String> v) { this.allowedOrigins = v; }

    /** This service acting as the eSignet relying party (design sections 5.2, 16). */
    public static class Esignet {
        private String tokenUrl;
        private String userinfoUrl;
        private String clientId;
        private String rpPrivateKey;
        private int retryWindowSeconds = 300;
        public String getTokenUrl() { return tokenUrl; }
        public void setTokenUrl(String v) { this.tokenUrl = v; }
        public String getUserinfoUrl() { return userinfoUrl; }
        public void setUserinfoUrl(String v) { this.userinfoUrl = v; }
        public String getClientId() { return clientId; }
        public void setClientId(String v) { this.clientId = v; }
        public String getRpPrivateKey() { return rpPrivateKey; }
        public void setRpPrivateKey(String v) { this.rpPrivateKey = v; }
        public int getRetryWindowSeconds() { return retryWindowSeconds; }
        public void setRetryWindowSeconds(int v) { this.retryWindowSeconds = v; }
    }

    public Map<String, Ds> getDatasources() { return datasources; }
    public void setDatasources(Map<String, Ds> datasources) { this.datasources = datasources; }
    public Minio getMinio() { return minio; }
    public void setMinio(Minio minio) { this.minio = minio; }
    public Deletion getDeletion() { return deletion; }
    public void setDeletion(Deletion deletion) { this.deletion = deletion; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }

    public static class Security {
        private Jwt jwt = new Jwt();
        public Jwt getJwt() { return jwt; }
        public void setJwt(Jwt jwt) { this.jwt = jwt; }

        public static class Jwt {
            private String publicKey;
            private String issuer;
            private String audience;
            public String getPublicKey() { return publicKey; }
            public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
            public String getIssuer() { return issuer; }
            public void setIssuer(String issuer) { this.issuer = issuer; }
            public String getAudience() { return audience; }
            public void setAudience(String audience) { this.audience = audience; }
        }
    }

    public static class Ds {
        private String url;
        private String username;
        private String password;
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }

    public static class Deletion {
        private int saltModulo = 1000;
        private String authPartnerId = "mpartner-default-auth";
        private String packetManagerBucket = "packet-manager";
        private String landingZoneBucket = "landing-zone";
        /** Design 8.3: ObjectStore (bucket) or DMZServer (mounted NFS path). */
        private String landingZoneType = "ObjectStore";
        /** Root of the mounted landing-zone NFS share, used only for DMZServer. */
        private String landingZoneNfsPath = "";
        /** Design 9.3.2: bucket holding idrepo biometric and document objects. */
        private String idrepoObjectBucket = "idrepo";
        private boolean digitalCardEnabled = false;
        private boolean selfRegistrationEnabled = false;
        private boolean esignetCleanupEnabled = true;
        private ConsoleAudit consoleAudit = new ConsoleAudit();
        public ConsoleAudit getConsoleAudit() { return consoleAudit; }
        public void setConsoleAudit(ConsoleAudit consoleAudit) { this.consoleAudit = consoleAudit; }
        public int getSaltModulo() { return saltModulo; }
        public void setSaltModulo(int saltModulo) { this.saltModulo = saltModulo; }
        public String getAuthPartnerId() { return authPartnerId; }
        public void setAuthPartnerId(String authPartnerId) { this.authPartnerId = authPartnerId; }
        public String getPacketManagerBucket() { return packetManagerBucket; }
        public void setPacketManagerBucket(String v) { this.packetManagerBucket = v; }
        public String getLandingZoneBucket() { return landingZoneBucket; }
        public void setLandingZoneBucket(String v) { this.landingZoneBucket = v; }
        public String getLandingZoneType() { return landingZoneType; }
        public void setLandingZoneType(String v) { this.landingZoneType = v; }
        public String getLandingZoneNfsPath() { return landingZoneNfsPath; }
        public void setLandingZoneNfsPath(String v) { this.landingZoneNfsPath = v; }
        public String getIdrepoObjectBucket() { return idrepoObjectBucket; }
        public void setIdrepoObjectBucket(String v) { this.idrepoObjectBucket = v; }
        public boolean isDigitalCardEnabled() { return digitalCardEnabled; }
        public void setDigitalCardEnabled(boolean v) { this.digitalCardEnabled = v; }
        public boolean isSelfRegistrationEnabled() { return selfRegistrationEnabled; }
        public void setSelfRegistrationEnabled(boolean v) { this.selfRegistrationEnabled = v; }
        public boolean isEsignetCleanupEnabled() { return esignetCleanupEnabled; }
        public void setEsignetCleanupEnabled(boolean v) { this.esignetCleanupEnabled = v; }

        /**
         * The terminal audit trail printed by the running service.
         *
         * show-plain-uin prints the UIN exactly as it was submitted. That is
         * useful when operating this local environment against synthetic seed
         * data, and it is the one place in the whole system where a plaintext
         * UIN is written anywhere other than memory. Turn it off for any
         * deployment holding real identities.
         */
        public static class ConsoleAudit {
            private boolean enabled = true;
            private boolean showPlainUin = true;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public boolean isShowPlainUin() { return showPlainUin; }
            public void setShowPlainUin(boolean showPlainUin) { this.showPlainUin = showPlainUin; }
        }
    }
}
