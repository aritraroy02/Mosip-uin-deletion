package com.example.mosip.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Objects;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        entityManagerFactoryRef = "basicEntityManagerFactory",
        transactionManagerRef = "basicTransactionManager",
        basePackages = {"com.example.mosip.repository.basic"}
)
public class BasicDbConfig {

    @Primary
    @Bean(name = "basicDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.basic")
    public DataSource basicDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Primary
    @Bean(name = "basicEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean basicEntityManagerFactory(
            @Qualifier("basicDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.example.mosip.entity.basic");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        HashMap<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.show_sql", "true");
        em.setJpaPropertyMap(properties);

        return em;
    }

    @Primary
    @Bean(name = "basicTransactionManager")
    public PlatformTransactionManager basicTransactionManager(
            @Qualifier("basicEntityManagerFactory") LocalContainerEntityManagerFactoryBean basicEntityManagerFactory) {
        return new JpaTransactionManager(Objects.requireNonNull(basicEntityManagerFactory.getObject()));
    }
}
