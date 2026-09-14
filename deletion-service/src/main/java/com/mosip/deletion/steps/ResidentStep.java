package com.mosip.deletion.steps;

import com.mosip.deletion.config.Databases;
import com.mosip.deletion.datashare.DatashareUrl;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.model.ModuleStatus;
import com.mosip.deletion.model.SubStep;
import com.mosip.deletion.service.DeletionContext;
import com.mosip.deletion.store.ObjectStoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resident Portal deletion (design doc section 11).
 *
 * VID_CARD_DOWNLOAD transactions for the identity's token_id: delete the
 * datashare object (reference_link), the linked credential_transaction
 * (credential_request_id), then the resident_transaction row itself.
 */
@Component
public class ResidentStep {

    private final JdbcTemplate resident;
    private final JdbcTemplate credential;
    private final ObjectStoreService store;

    public ResidentStep(Databases db, ObjectStoreService store) {
        this.resident = db.db("resident");
        this.credential = db.db("credential");
        this.store = store;
    }

    public ModuleResult run(DeletionContext ctx) {
        ModuleResult m = new ModuleResult("Resident Portal");
        if (ctx.tokenIds.isEmpty()) {
            m.setStatus(ModuleStatus.NOT_FOUND);
            return m;
        }
        List<String> tokens = new ArrayList<>(ctx.tokenIds);

        run(m, "resident VID_CARD_DOWNLOAD datashare + credential + row", () -> {
            int affected = 0;
            for (String token : tokens) {
                List<EventRow> rows = resident.query(
                        "SELECT event_id, reference_link, credential_request_id "
                        + "FROM resident.resident_transaction "
                        + "WHERE token_id = ? AND request_type_code = 'VID_CARD_DOWNLOAD'",
                        (rs, i) -> new EventRow(rs.getString("event_id"),
                                rs.getString("reference_link"),
                                rs.getString("credential_request_id")),
                        token);
                for (EventRow r : rows) {
                    DatashareUrl d = DatashareUrl.parse(r.referenceLink());
                    if (d != null) {
                        store.deleteObject(d.bucket(), d.objectKey());
                    }
                    if (r.credentialRequestId() != null) {
                        credential.update(
                                "DELETE FROM credential.credential_transaction WHERE id = ?",
                                r.credentialRequestId());
                    }
                    affected += resident.update(
                            "DELETE FROM resident.resident_transaction WHERE event_id = ?",
                            r.eventId());
                }
            }
            return affected;
        });
        return m;
    }

    private record EventRow(String eventId, String referenceLink, String credentialRequestId) {}

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
