package com.mosip.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The delete-uin page is served from :5501 and calls this gateway on :8095, so
 * the browser needs CORS permission for those origins.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final GatewayProperties props;

    public CorsConfig(GatewayProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = props.getAllowedOrigins() == null
                ? new String[0]
                : props.getAllowedOrigins().toArray(new String[0]);
        registry.addMapping("/v1/delete-uin/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
