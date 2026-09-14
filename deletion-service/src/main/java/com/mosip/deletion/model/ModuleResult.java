package com.mosip.deletion.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated outcome for one module (Registration, ID Repository, ...), built
 * from its sub-steps. The module status is derived from the sub-steps per the
 * mapping in design doc section 14.4:
 *   - every sub-step failed              -> FAILED
 *   - some failed, some deleted          -> PARTIAL
 *   - all succeeded, nothing was present -> NOT_FOUND
 *   - all succeeded, something removed   -> DELETED
 * SKIPPED is set explicitly by the step when the module does not apply.
 */
public class ModuleResult {

    private final String module;
    private final List<SubStep> subSteps = new ArrayList<>();
    private ModuleStatus status;

    public ModuleResult(String module) { this.module = module; }

    public SubStep step(String name) {
        SubStep s = new SubStep(name);
        subSteps.add(s);
        return s;
    }

    public void markSkipped() { this.status = ModuleStatus.SKIPPED; }

    public void setStatus(ModuleStatus status) { this.status = status; }

    /** Derive status from sub-steps unless already set (e.g. SKIPPED). */
    public ModuleResult finish() {
        if (status != null) {
            return this;
        }
        if (subSteps.isEmpty()) {
            status = ModuleStatus.NOT_FOUND;
            return this;
        }
        boolean anyFailed = subSteps.stream().anyMatch(s -> !s.isOk());
        boolean anyDeleted = subSteps.stream().anyMatch(s -> s.isOk() && s.getCount() > 0);
        boolean allFailed = subSteps.stream().noneMatch(SubStep::isOk);
        if (allFailed) {
            status = ModuleStatus.FAILED;
        } else if (anyFailed) {
            status = ModuleStatus.PARTIAL;
        } else if (anyDeleted) {
            status = ModuleStatus.DELETED;
        } else {
            status = ModuleStatus.NOT_FOUND;
        }
        return this;
    }

    public int totalDeleted() {
        return subSteps.stream().filter(SubStep::isOk).mapToInt(SubStep::getCount).sum();
    }

    public String getModule() { return module; }
    public List<SubStep> getSubSteps() { return subSteps; }
    public ModuleStatus getStatus() { return status; }
}
