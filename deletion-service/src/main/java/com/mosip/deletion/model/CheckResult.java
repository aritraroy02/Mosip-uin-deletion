package com.mosip.deletion.model;

import java.util.Map;

/**
 * Outcome of the pre-deletion check (the flow before consent):
 *   NO_DATA_AVAILABLE -> nothing to delete
 *   ALREADY_DELETED   -> a completed deletion is already recorded in the audit table
 *   AVAILABLE         -> data exists; proceed to consent
 */
public record CheckResult(Availability availability,
                          String message,
                          Map<String, Object> summary) {

    public enum Availability { NO_DATA_AVAILABLE, ALREADY_DELETED, AVAILABLE }

    public static CheckResult noData() {
        return new CheckResult(Availability.NO_DATA_AVAILABLE,
                "No data available for this UIN.", Map.of());
    }

    public static CheckResult alreadyDeleted(String when) {
        return new CheckResult(Availability.ALREADY_DELETED,
                "User data already deleted (on " + when + ").", Map.of("deletedAt", when));
    }

    public static CheckResult available(Map<String, Object> summary) {
        return new CheckResult(Availability.AVAILABLE,
                "Data available. Consent required to delete.", summary);
    }
}
