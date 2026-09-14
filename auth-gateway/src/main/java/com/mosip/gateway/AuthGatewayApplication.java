package com.mosip.gateway;

import com.mosip.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * eSignet relying-party backend for the "Delete my UIN" flow.
 *
 * The static delete-uin page carries only the eSignet authorization code. This
 * gateway exchanges it for tokens, calls /userinfo to resolve the resident's
 * UIN, mints a 5-minute JWT bound to that UIN, and calls the deletion service
 * with it. The page never sees a token or the UIN -- only a masked UIN and the
 * deletion status.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(GatewayProperties.class)
public class AuthGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthGatewayApplication.class, args);
    }
}
