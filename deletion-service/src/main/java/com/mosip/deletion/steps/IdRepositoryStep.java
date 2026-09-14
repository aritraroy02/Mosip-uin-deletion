package com.mosip.deletion.steps;

import com.mosip.deletion.config.Databases;
import com.mosip.deletion.datashare.DatashareUrl;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.model.SubStep;
import com.mosip.deletion.service.DeletionContext;
import com.mosip.deletion.store.ObjectStoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ID Repository deletion (design doc section 9).
 *
 * Two parts: credential issuance cleanup (credential_request_status ->
 * request_id -> credential_transaction + datashare object), and the UIN
 * identity data itself -- biometrics/documents by uin_ref_id, then handle, vid,
 * uin, uin_h, auth-lock and update-count-tracker by UIN hash.
 */
@Component
public class IdRepositoryStep {

    private final JdbcTemplate idrepo;
    private final JdbcTemplate idmap;
    private final JdbcTemplate credential;
    private final ObjectStoreService store;

    public IdRepositoryStep(Databases db, ObjectStoreService store) {
        this.idrepo = db.db("idrepo");
        this.idmap = db.db("idmap");
        this.credential = db.db("credential");
        this.store = store;
    }

    public ModuleResult run(DeletionContext ctx) {
        ModuleResult m = new ModuleResult("ID Repository");
        if (!ctx.hasAnyData()) {
            m.setStatus(com.mosip.deletion.model.ModuleStatus.NOT_FOUND);
            return m;
        }

        // 9.2 Credential issuance: individual_id_hash (UIN + VID bare hashes)
        // -> request_id -> credential_transaction datashare object + row.
        List<String> hashes = new ArrayList<>(ctx.individualHashesBare);
        run(m, "credential datashare objects + credential_transaction", () -> {
            int n = 0;
            for (String h : hashes) {
                List<String> reqIds = idrepo.queryForList(
                        "SELECT request_id FROM idrepo.credential_request_status "
                        + "WHERE individual_id_hash = ? AND request_id IS NOT NULL",
                        String.class, h);
                for (String reqId : reqIds) {
                    for (String url : credential.queryForList(
                            "SELECT datashareurl FROM credential.credential_transaction WHERE id = ?",
                            String.class, reqId)) {
                        DatashareUrl d = DatashareUrl.parse(url);
                        if (d != null) n += store.deleteObject(d.bucket(), d.objectKey());
                    }
                    credential.update(
                            "DELETE FROM credential.credential_transaction WHERE id = ?", reqId);
                }
            }
            return n;
        });
        run(m, "idrepo.credential_request_status", () -> {
            int n = 0;
            for (String h : hashes) {
                n += idrepo.update(
                        "DELETE FROM idrepo.credential_request_status WHERE individual_id_hash = ?", h);
            }
            return n;
        });

        // 9.3.3 Rows by uin_ref_id: biometrics and documents (+ history).
        List<String> refIds = new ArrayList<>(ctx.uinRefIds);
        for (String table : List.of("idrepo.uin_biometric", "idrepo.uin_biometric_h",
                                    "idrepo.uin_document", "idrepo.uin_document_h")) {
            run(m, table, () -> {
                int n = 0;
                for (String ref : refIds) {
                    n += idrepo.update("DELETE FROM " + table + " WHERE uin_ref_id = ?", ref);
                }
                return n;
            });
        }

        // 9.3.4 Rows by UIN hash.
        run(m, "idrepo.uin_auth_lock", () ->
                idrepo.update("DELETE FROM idrepo.uin_auth_lock WHERE uin_hash = ?", ctx.hashBare));
        run(m, "idrepo.identity_update_count_tracker", () ->
                idrepo.update("DELETE FROM idrepo.identity_update_count_tracker WHERE id = ?", ctx.hashPrefixed));
        run(m, "idrepo.handle", () ->
                idrepo.update("DELETE FROM idrepo.handle WHERE uin_hash = ?", ctx.hashPrefixed));
        run(m, "idmap.vid", () ->
                idmap.update("DELETE FROM idmap.vid WHERE uin_hash = ?", ctx.hashPrefixed));
        run(m, "idrepo.uin", () ->
                idrepo.update("DELETE FROM idrepo.uin WHERE uin_hash = ?", ctx.hashPrefixed));
        run(m, "idrepo.uin_h", () ->
                idrepo.update("DELETE FROM idrepo.uin_h WHERE uin_hash = ?", ctx.hashPrefixed));
        return m;
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
