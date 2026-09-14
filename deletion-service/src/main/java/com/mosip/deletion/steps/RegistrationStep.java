package com.mosip.deletion.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mosip.deletion.config.Databases;
import com.mosip.deletion.config.DeletionProperties;
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
 * Registration Processor deletion (design doc section 8).
 *
 * Keyed by the RIDs resolved from idrepo.uin/uin_h. Removes the registration
 * rows, the packet-manager and landing-zone objects, demographic/biometric
 * dedupe data, the ABIS datashare object (via abis_request.req_text ->
 * referenceURL), and the print-service credential + its datashare object.
 * Digital-card cleanup (8.7) is skipped -- that module is not deployed here.
 */
@Component
public class RegistrationStep {

    private final JdbcTemplate regprc;
    private final JdbcTemplate credential;
    private final ObjectStoreService store;
    private final DeletionProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistrationStep(Databases db, ObjectStoreService store, DeletionProperties props) {
        this.regprc = db.db("regprc");
        this.credential = db.db("credential");
        this.store = store;
        this.props = props;
    }

    public ModuleResult run(DeletionContext ctx) {
        ModuleResult m = new ModuleResult("Registration");
        if (ctx.regIds.isEmpty()) {
            m.markSkipped();               // no RID found (design 14.3)
            return m;
        }
        List<String> rids = new ArrayList<>(ctx.regIds);

        // Object store: whole packet + landing-zone tree per RID.
        run(m, "packet-manager objects", () -> {
            int n = 0;
            for (String rid : rids) {
                n += store.deletePrefix(props.getDeletion().getPacketManagerBucket(), rid + "/");
            }
            return n;
        });
        run(m, "landing-zone objects", () -> {
            int n = 0;
            for (String rid : rids) {
                n += store.deletePrefix(props.getDeletion().getLandingZoneBucket(), rid + "/");
            }
            return n;
        });

        // ABIS datashare objects (8.6): reg_bio_ref -> bio_ref_id -> abis_request
        // req_text JSON -> referenceURL -> object.
        run(m, "ABIS datashare objects", () -> deleteAbisObjects(rids));

        // Print-service credential (8.8): PRINT_SERVICE ref_id -> credential_transaction.
        run(m, "print-service credential + datashare", () -> deletePrintService(rids));

        // Database rows, strictly children before parents so no foreign key is
        // ever violated. The regprc dependency chains this order satisfies:
        //   reg_demo_dedupe_list      -> registration_transaction
        //   abis_response_det -> abis_response -> abis_request
        //   individual_demographic_dedup, reg_bio_ref, reg_lost_uin_det,
        //   reg_manual_verification   -> registration
        // reg_bio_ref is deleted only AFTER the ABIS rows, because the ABIS
        // deletes look up bio_ref_id from it.
        run(m, "regprc.reg_demo_dedupe_list", () ->
                deleteByRid("DELETE FROM regprc.reg_demo_dedupe_list WHERE reg_id = ?", rids));
        run(m, "regprc.registration_transaction", () ->
                deleteByRid("DELETE FROM regprc.registration_transaction WHERE reg_id = ?", rids));
        run(m, "regprc.abis_response_det", () -> deleteAbisResponseDet(rids));
        run(m, "regprc.abis_response", () -> deleteAbisResponses(rids));
        run(m, "regprc.abis_request", () -> deleteAbisRequests(rids));
        run(m, "regprc.individual_demographic_dedup", () ->
                deleteByRid("DELETE FROM regprc.individual_demographic_dedup WHERE reg_id = ?", rids));
        run(m, "regprc.reg_bio_ref", () ->
                deleteByRid("DELETE FROM regprc.reg_bio_ref WHERE reg_id = ?", rids));
        run(m, "regprc.reg_lost_uin_det", () ->
                deleteByRid("DELETE FROM regprc.reg_lost_uin_det WHERE reg_id = ?", rids));
        run(m, "regprc.reg_manual_verification", () ->
                deleteByRid("DELETE FROM regprc.reg_manual_verification WHERE reg_id = ?", rids));
        run(m, "regprc.registration_list", () ->
                deleteByRid("DELETE FROM regprc.registration_list WHERE reg_id = ?", rids));
        run(m, "regprc.registration", () ->
                deleteByRid("DELETE FROM regprc.registration WHERE reg_id = ?", rids));
        return m;
    }

    private int deleteAbisObjects(List<String> rids) {
        int n = 0;
        for (String rid : rids) {
            List<String> bioRefs = regprc.queryForList(
                    "SELECT bio_ref_id FROM regprc.reg_bio_ref WHERE reg_id = ?",
                    String.class, rid);
            for (String bioRef : bioRefs) {
                List<byte[]> texts = regprc.query(
                        "SELECT req_text FROM regprc.abis_request WHERE bio_ref_id = ?",
                        (rs, i) -> rs.getBytes("req_text"), bioRef);
                for (byte[] t : texts) {
                    if (t == null) continue;
                    try {
                        String url = mapper.readTree(t).path("referenceURL").asText(null);
                        DatashareUrl d = DatashareUrl.parse(url);
                        if (d != null) {
                            n += store.deleteObject(d.bucket(), d.objectKey());
                        }
                    } catch (Exception ignore) {
                        // malformed req_text -> skip this object, keep going
                    }
                }
            }
        }
        return n;
    }

    /** Grandchildren of abis_request: abis_response_det -> abis_response. */
    private int deleteAbisResponseDet(List<String> rids) {
        int n = 0;
        for (String rid : rids) {
            for (String bioRef : bioRefs(rid)) {
                n += regprc.update(
                        "DELETE FROM regprc.abis_response_det WHERE abis_resp_id IN ("
                        + "SELECT ar.id FROM regprc.abis_response ar "
                        + "JOIN regprc.abis_request req ON ar.abis_req_id = req.id "
                        + "WHERE req.bio_ref_id = ?)", bioRef);
            }
        }
        return n;
    }

    /** Children of abis_request: abis_response (must go before abis_request). */
    private int deleteAbisResponses(List<String> rids) {
        int n = 0;
        for (String rid : rids) {
            for (String bioRef : bioRefs(rid)) {
                n += regprc.update(
                        "DELETE FROM regprc.abis_response WHERE abis_req_id IN ("
                        + "SELECT id FROM regprc.abis_request WHERE bio_ref_id = ?)", bioRef);
            }
        }
        return n;
    }

    private int deleteAbisRequests(List<String> rids) {
        int n = 0;
        for (String rid : rids) {
            for (String bioRef : bioRefs(rid)) {
                n += regprc.update("DELETE FROM regprc.abis_request WHERE bio_ref_id = ?", bioRef);
            }
        }
        return n;
    }

    private List<String> bioRefs(String rid) {
        return regprc.queryForList(
                "SELECT bio_ref_id FROM regprc.reg_bio_ref WHERE reg_id = ?",
                String.class, rid);
    }

    private int deletePrintService(List<String> rids) {
        int n = 0;
        for (String rid : rids) {
            List<String> refIds = regprc.queryForList(
                    "SELECT ref_id FROM regprc.registration_transaction "
                    + "WHERE reg_id = ? AND trn_type_code = 'PRINT_SERVICE' AND ref_id IS NOT NULL",
                    String.class, rid);
            for (String refId : refIds) {
                List<String> urls = credential.queryForList(
                        "SELECT datashareurl FROM credential.credential_transaction WHERE id = ?",
                        String.class, refId);
                for (String url : urls) {
                    DatashareUrl d = DatashareUrl.parse(url);
                    if (d != null) {
                        n += store.deleteObject(d.bucket(), d.objectKey());
                    }
                }
                credential.update("DELETE FROM credential.credential_transaction WHERE id = ?", refId);
            }
        }
        return n;
    }

    private int deleteByRid(String sql, List<String> rids) {
        int n = 0;
        for (String rid : rids) {
            n += regprc.update(sql, rid);
        }
        return n;
    }

    /** Run one sub-step, recording its count or its failure without throwing. */
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
