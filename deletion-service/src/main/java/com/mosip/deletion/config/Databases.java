package com.mosip.deletion.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds one JdbcTemplate per MOSIP database, keyed by logical name
 * (idmap, idrepo, regprc, credential, ida, resident, audit).
 *
 * Each database is a separate PostgreSQL instance, so there is no distributed
 * transaction across them -- exactly as in real MOSIP. Every deletion step
 * therefore commits per module, and the orchestrator makes each step
 * idempotent and continues past failures (design doc section 14).
 */
@Component
public class Databases implements DisposableBean {

    private final Map<String, HikariDataSource> pools = new HashMap<>();
    private final Map<String, JdbcTemplate> templates = new HashMap<>();

    public Databases(DeletionProperties props) {
        props.getDatasources().forEach((name, ds) -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(ds.getUrl());
            cfg.setUsername(ds.getUsername());
            cfg.setPassword(ds.getPassword());
            cfg.setPoolName("pool-" + name);
            cfg.setMaximumPoolSize(3);
            cfg.setConnectionTimeout(5000);
            HikariDataSource pool = new HikariDataSource(cfg);
            pools.put(name, pool);
            templates.put(name, new JdbcTemplate(pool));
        });
    }

    /** True when a datasource with this logical name was configured. */
    public boolean has(String name) {
        return templates.containsKey(name);
    }

    public JdbcTemplate db(String name) {
        JdbcTemplate t = templates.get(name);
        if (t == null) {
            throw new IllegalArgumentException("unknown database: " + name);
        }
        return t;
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
    }
}
