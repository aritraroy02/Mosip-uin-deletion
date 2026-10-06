package com.mosip.deletion.steps;

import com.mosip.deletion.config.Databases;
import com.mosip.deletion.config.DeletionProperties;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.model.SubStep;
import com.mosip.deletion.service.DeletionContext;
import org.springframework.stereotype.Component;

/**
 * Self-registration portal deletion (design doc section 12).
 *
 * Unlike every other module this one keys on the PLAIN UIN: the portal stores it
 * unhashed in inji_certify_tan.self_registration.id_number.
 *
 * The module is gated twice, and reports SKIPPED with the reason for either:
 *   - app.deletion.self-registration-enabled is false, or
 *   - the flag is on but no "selfreg" datasource is configured, which is the
 *     case in this Collab environment because the portal is not deployed.
 *
 * Configure it by adding a selfreg entry under app.datasources pointing at the
 * inji_certify_tan database and setting the flag to true.
 */
@Component
public class SelfRegistrationStep {

    private final DeletionProperties props;
    private final Databases db;

    public SelfRegistrationStep(DeletionProperties props, Databases db) {
        this.props = props;
        this.db = db;
    }

    public ModuleResult run(DeletionContext ctx) {
        ModuleResult m = new ModuleResult("Self-Registration");
        if (!enabled()) {
            m.markSkipped();
            return m;
        }
        if (!db.has("selfreg")) {
            // Enabled but unusable: say so through a failed sub-step rather than
            // silently reporting success for data that was never touched.
            m.step("inji_certify_tan.self_registration")
             .failed("self-registration enabled but no 'selfreg' datasource is configured");
            return m;
        }

        SubStep s = m.step("inji_certify_tan.self_registration");
        try {
            s.ok(db.db("selfreg").update(
                    "DELETE FROM inji_certify_tan.self_registration WHERE id_number = ?",
                    ctx.uin));
        } catch (Exception e) {
            s.failed(e.getMessage());
        }
        return m;
    }

    public boolean enabled() {
        return props.getDeletion().isSelfRegistrationEnabled();
    }
}
