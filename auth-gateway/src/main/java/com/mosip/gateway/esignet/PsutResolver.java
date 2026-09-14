package com.mosip.gateway.esignet;

import com.mosip.gateway.config.GatewayProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves eSignet's pairwise pseudonym (the userinfo `sub`, a
 * partner_specific_user_token) back to the real UIN.
 *
 * The mock identity system does not emit the individual_id claim, so userinfo
 * carries only the pseudonym. Its kyc_auth table records
 * partner_specific_user_token -> individual_id, which is the reverse map used
 * here. This is a mock-only bridge; a production IDA plugin returns
 * individual_id in userinfo directly and this resolver is not needed.
 */
@Component
public class PsutResolver implements DisposableBean {

    private final HikariDataSource ds;
    private final JdbcTemplate jdbc;

    public PsutResolver(GatewayProperties props) {
        var cfg = props.getMockIdentity();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.getUrl());
        hc.setUsername(cfg.getUsername());
        hc.setPassword(cfg.getPassword());
        hc.setPoolName("mock-identity");
        hc.setMaximumPoolSize(2);
        hc.setConnectionTimeout(5000);
        this.ds = new HikariDataSource(hc);
        this.jdbc = new JdbcTemplate(ds);
    }

    /** Returns the UIN for a pseudonym, or null if it is not known. */
    public String toUin(String partnerSpecificUserToken) {
        List<String> rows = jdbc.queryForList(
                "SELECT individual_id FROM mockidentitysystem.kyc_auth "
                + "WHERE partner_specific_user_token = ? "
                + "ORDER BY response_time DESC LIMIT 1",
                String.class, partnerSpecificUserToken);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void destroy() {
        ds.close();
    }
}
