package com.mosip.deletion;

import com.mosip.deletion.config.DeletionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Self-service UIN and personal-data deletion service.
 *
 * Implements the deletion orchestration in "Collab Self-Service UIN and Personal
 * Data Deletion Process" v1.0 against the dockerised MOSIP databases and MinIO.
 * Datasource auto-configuration is excluded because this service owns seven
 * datasources of its own (see {@link com.mosip.deletion.config.Databases}).
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(DeletionProperties.class)
public class DeletionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeletionServiceApplication.class, args);
    }
}
