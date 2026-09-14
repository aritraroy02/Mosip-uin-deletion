package com.mosip.deletion.steps;

import com.mosip.deletion.config.Databases;
import com.mosip.deletion.config.DeletionProperties;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.model.SubStep;
import com.mosip.deletion.service.DeletionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * eSignet identity erasure (extends the deletion beyond the 7 deletion DBs).
 *
 * The other modules remove the resident's data from the deletion databases.
 * This one removes the identity from the eSignet stack so the person can no
 * longer authenticate and no consent trail remains:
 *
 *   mock-identity (mosip_mockidentitysystem), keyed by the PLAINTEXT UIN:
 *     - mock_identity   : the identity eSignet logs in against
 *     - kyc_auth        : UIN <-> partner_specific_user_token history
 *     - verified_claim  : any verified claims for the UIN
 *
 *   eSignet (mosip_esignet), keyed by the partner_specific_user_token (psu_token)
 *   which is resolved from kyc_auth BEFORE kyc_auth is deleted:
 *     - consent_detail       : active consent
 *     - consent_history      : consent audit trail
 *     - public_key_registry  : wallet-binding public keys
 *
 * psu_token is pairwise (per identity+partner), so deleting by it removes only
 * this identity's rows. Runs only when app.deletion.esignet-cleanup-enabled is
 * true; otherwise the module reports SKIPPED. Like every step it never throws --
 * a failure is captured against its sub-step and the overall flow continues.
 */
@Component
public class EsignetIdentityStep {

    private final JdbcTemplate mockIdentity;
    private final JdbcTemplate esignet;
    private final DeletionProperties props;

    public EsignetIdentityStep(Databases db, DeletionProperties props) {
        this.mockIdentity = db.db("mockidentity");
        this.esignet = db.db("esignet");
        this.props = props;
    }

    public ModuleResult run(DeletionContext ctx) {
        ModuleResult m = new ModuleResult("eSignet & Mock Identity");
        if (!props.getDeletion().isEsignetCleanupEnabled()) {
            m.markSkipped();
            return m;
        }
        String uin = ctx.uin;

        // Resolve the psu_token(s) for this UIN before kyc_auth is removed; the
        // eSignet consent/binding rows can only be found through them.
        List<String> psuTokens = new ArrayList<>(new LinkedHashSet<>(
                mockIdentity.queryForList(
                        "SELECT partner_specific_user_token FROM mockidentitysystem.kyc_auth "
                        + "WHERE individual_id = ? AND partner_specific_user_token IS NOT NULL",
                        String.class, uin)));

        // eSignet rows, keyed by psu_token.
        run(m, "esignet.consent_detail", () -> deleteByPsu(
                "DELETE FROM esignet.consent_detail WHERE psu_token = ?", psuTokens));
        run(m, "esignet.consent_history", () -> deleteByPsu(
                "DELETE FROM esignet.consent_history WHERE psu_token = ?", psuTokens));
        run(m, "esignet.public_key_registry", () -> deleteByPsu(
                "DELETE FROM esignet.public_key_registry WHERE psu_token = ?", psuTokens));

        // mock-identity rows, keyed by the plaintext UIN.
        run(m, "mockidentitysystem.verified_claim", () -> mockIdentity.update(
                "DELETE FROM mockidentitysystem.verified_claim WHERE individual_id = ?", uin));
        run(m, "mockidentitysystem.kyc_auth", () -> mockIdentity.update(
                "DELETE FROM mockidentitysystem.kyc_auth WHERE individual_id = ?", uin));
        run(m, "mockidentitysystem.mock_identity", () -> mockIdentity.update(
                "DELETE FROM mockidentitysystem.mock_identity WHERE individual_id = ?", uin));
        return m;
    }

    private int deleteByPsu(String sql, List<String> psuTokens) {
        int n = 0;
        for (String psu : psuTokens) {
            n += esignet.update(sql, psu);
        }
        return n;
    }

    private void run(ModuleResult m, String name, CountingStep step) {
        SubStep s = m.step(name);
        try {
            s.ok(step.run());
        } catch (Exception e) {
            s.failed(e.getMessage());
        }
    }

    @FunctionalInterface
    private interface CountingStep { int run() throws Exception; }
}
