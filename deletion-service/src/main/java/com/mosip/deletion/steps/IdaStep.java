package com.mosip.deletion.steps;

import com.mosip.deletion.config.Databases;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.model.ModuleStatus;
import com.mosip.deletion.model.SubStep;
import com.mosip.deletion.service.DeletionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * IDA deletion (design doc section 10).
 *
 * identity_cache is found by id = bare UIN/VID hash; its token_id (already
 * resolved into the context) is the key for deleting the cache rows, the
 * identity binding-certificate store, and the IDA auth-lock. Runs after
 * Registration and ID Repository so authentication data stays available until
 * those steps finish.
 */
@Component
public class IdaStep {

    private final JdbcTemplate ida;

    public IdaStep(Databases db) {
        this.ida = db.db("ida");
    }

    public ModuleResult run(DeletionContext ctx) {
        ModuleResult m = new ModuleResult("ID Authentication");
        if (ctx.tokenIds.isEmpty() && ctx.individualHashesBare.isEmpty()) {
            m.setStatus(ModuleStatus.NOT_FOUND);
            return m;
        }
        List<String> tokens = new ArrayList<>(ctx.tokenIds);
        List<String> hashes = new ArrayList<>(ctx.individualHashesBare);

        // 10.1 identity_cache: delete by token_id (all UIN/VID rows share it);
        // also sweep any residual rows still matching the bare hash directly.
        run(m, "ida.identity_cache", () -> {
            int n = 0;
            for (String t : tokens) {
                n += ida.update("DELETE FROM ida.identity_cache WHERE token_id = ?", t);
            }
            for (String h : hashes) {
                n += ida.update("DELETE FROM ida.identity_cache WHERE id = ?", h);
            }
            return n;
        });

        // 10.2 identity binding certificate store: by token_id.
        run(m, "ida.ident_binding_cert_store", () -> {
            int n = 0;
            for (String t : tokens) {
                n += ida.update("DELETE FROM ida.ident_binding_cert_store WHERE token_id = ?", t);
            }
            return n;
        });

        // IDA auth-lock by token_id (identity auth data keyed by token_id).
        run(m, "ida.uin_auth_lock", () -> {
            int n = 0;
            for (String t : tokens) {
                n += ida.update("DELETE FROM ida.uin_auth_lock WHERE token_id = ?", t);
            }
            return n;
        });
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
