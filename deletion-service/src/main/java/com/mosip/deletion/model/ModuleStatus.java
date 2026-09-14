package com.mosip.deletion.model;

/** Per-module deletion outcome reported to the user (design doc section 14.3). */
public enum ModuleStatus {
    /** All targeted data for this module was removed successfully. */
    DELETED,
    /** Some targets were deleted but at least one sub-step failed. */
    PARTIAL,
    /** Deletion for this module could not be completed at all. */
    FAILED,
    /** Module not applicable (e.g. no RID found, or module disabled). */
    SKIPPED,
    /** No data existed for this UIN in the module. */
    NOT_FOUND
}
