package com.mosip.deletion.steps;

import com.mosip.deletion.config.DeletionProperties;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.service.DeletionContext;
import org.springframework.stereotype.Component;

/**
 * Self-registration portal deletion (design doc section 12), configurable.
 *
 * This Collab environment has no self-registration module (no
 * inji_certify_tan.self_registration table), so the feature flag is off and the
 * module reports SKIPPED. If enabled later, this is where the plain-UIN
 * id_number row deletion would run.
 */
@Component
public class SelfRegistrationStep {

    private final DeletionProperties props;

    public SelfRegistrationStep(DeletionProperties props) {
        this.props = props;
    }

    public ModuleResult run(DeletionContext ctx) {
        ModuleResult m = new ModuleResult("Self-Registration");
        m.markSkipped();
        return m;
    }

    public boolean enabled() {
        return props.getDeletion().isSelfRegistrationEnabled();
    }
}
