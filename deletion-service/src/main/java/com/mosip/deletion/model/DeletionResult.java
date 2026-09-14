package com.mosip.deletion.model;

import java.time.Instant;
import java.util.List;

/**
 * Full outcome returned to the caller and persisted to the audit table.
 * `overall` is DELETED only if every applicable module is DELETED/NOT_FOUND/
 * SKIPPED; PARTIAL if any module is PARTIAL/FAILED (design doc section 14.3 --
 * the request can succeed at HTTP level while modules report partial failure).
 */
public record DeletionResult(String requestId,
                             String uinHashPrefixed,
                             String uinHashBare,
                             Instant completedAt,
                             ModuleStatus overall,
                             List<ModuleResult> modules) {

    public static ModuleStatus deriveOverall(List<ModuleResult> modules) {
        boolean anyBad = modules.stream()
                .anyMatch(m -> m.getStatus() == ModuleStatus.PARTIAL
                        || m.getStatus() == ModuleStatus.FAILED);
        if (anyBad) {
            return ModuleStatus.PARTIAL;
        }
        return ModuleStatus.DELETED;
    }
}
