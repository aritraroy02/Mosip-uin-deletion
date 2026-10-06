package com.mosip.deletion.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The delete-uin page is served from :5501 and now calls this service directly
 * on :8096, so the browser needs CORS permission for that origin.
 *
 * Only the page-facing path is opened. /api/deletion/** stays closed to
 * browsers: it is the token-secured API used by the CLI and Postman.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final DeletionProperties props;

    public CorsConfig(DeletionProperties props) {
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
