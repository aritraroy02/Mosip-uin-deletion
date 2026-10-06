package com.mosip.deletion.console;

import com.mosip.deletion.service.DeletionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The declared, human-readable plan of what a deletion is about to touch.
 *
 * Every {@link Target} name here is the exact sub-step name the matching class
 * in {@code steps/} registers at run time, so the "PLAN" block printed before a
 * deletion and the "EXECUTE" block printed after it line up row for row. If a
 * step ever changes a sub-step name without changing it here, the two blocks
 * stop matching and the mismatch is visible in the terminal.
 *
 * Whether a module runs at all depends on what {@code ContextResolver} found,
 * so the plan is built per request rather than being a fixed list.
 */
public final class DeletionPlan {

    private DeletionPlan() {}

    /**
     * One targeted deletion: the sub-step name, and a compact statement of
     * which store it hits and which key it is looked up by.
     */
    public record Target(String name, String where) {}

    /** One module's plan. A non-null skipReason means nothing will be attempted. */
    public record Module(String name, String stores, List<Target> targets, String skipReason) {
        public boolean willRun() { return skipReason == null; }
    }

    public static List<Module> forContext(DeletionContext ctx, boolean esignetCleanupEnabled,
                                          boolean selfRegistrationEnabled) {
        List<Module> modules = new ArrayList<>();
        modules.add(registration(ctx));
        modules.add(idRepository(ctx));
        modules.add(ida(ctx));
        modules.add(resident(ctx));
        modules.add(esignet(esignetCleanupEnabled));
        modules.add(selfRegistration(selfRegistrationEnabled));
        return modules;
    }

    private static Module selfRegistration(boolean enabled) {
        String skip = enabled ? null : "self-registration-enabled = false";
        return new Module("Self-Registration", "selfreg datasource",
                List.of(new Target("inji_certify_tan.self_registration",
                        "selfreg  by plain UIN")),
                skip);
    }

    /** Total number of targets that will actually be attempted. */
    public static int targetCount(List<Module> modules) {
        return modules.stream().filter(Module::willRun).mapToInt(m -> m.targets().size()).sum();
    }

    // ---------------------------------------------------------------- modules

    private static Module registration(DeletionContext ctx) {
        String skip = ctx.regIds.isEmpty() ? "no registration id for this UIN" : null;
        return new Module("Registration", "regprc:5443  credential:5445  MinIO",
                List.of(
                        new Target("packet-manager objects", "MinIO  by rid prefix"),
                        new Target("landing-zone objects", "MinIO  by rid prefix"),
                        new Target("ABIS datashare objects", "MinIO  via abis_request"),
                        new Target("print-service credential + datashare", "MinIO + credential:5445"),
                        new Target("regprc.reg_demo_dedupe_list", "regprc:5443  by reg_id"),
                        new Target("regprc.registration_transaction", "regprc:5443  by reg_id"),
                        new Target("regprc.abis_response_det", "regprc:5443  via abis_request"),
                        new Target("regprc.abis_response", "regprc:5443  via abis_request"),
                        new Target("regprc.abis_request", "regprc:5443  by bio_ref"),
                        new Target("regprc.individual_demographic_dedup", "regprc:5443  by reg_id"),
                        new Target("regprc.reg_bio_ref", "regprc:5443  by reg_id"),
                        new Target("regprc.reg_lost_uin_det", "regprc:5443  by reg_id"),
                        new Target("regprc.reg_manual_verification", "regprc:5443  by reg_id"),
                        new Target("regprc.registration_list", "regprc:5443  by reg_id"),
                        new Target("regprc.registration", "regprc:5443  by reg_id")),
                skip);
    }

    private static Module idRepository(DeletionContext ctx) {
        String skip = ctx.hasAnyData() ? null : "no identity data resolved";
        return new Module("ID Repository", "idrepo:5448  idmap:5442  credential:5445  MinIO",
                List.of(
                        new Target("credential datashare objects + credential_transaction",
                                "MinIO + credential:5445"),
                        new Target("idrepo.credential_request_status", "idrepo:5448  by id_hash"),
                        new Target("idrepo biometric + document objects", "MinIO  by uinHash/fileRef"),
                        new Target("idrepo.uin_biometric", "idrepo:5448  by uin_ref_id"),
                        new Target("idrepo.uin_biometric_h", "idrepo:5448  by uin_ref_id"),
                        new Target("idrepo.uin_document", "idrepo:5448  by uin_ref_id"),
                        new Target("idrepo.uin_document_h", "idrepo:5448  by uin_ref_id"),
                        new Target("idrepo.uin_auth_lock", "idrepo:5448  by uin_hash"),
                        new Target("idrepo.identity_update_count_tracker", "idrepo:5448  by uin_hash"),
                        new Target("idrepo.handle", "idrepo:5448  by uin_hash"),
                        new Target("idmap.vid", "idmap:5442  by uin_hash"),
                        new Target("idrepo.uin", "idrepo:5448  by uin_hash"),
                        new Target("idrepo.uin_h", "idrepo:5448  by uin_hash")),
                skip);
    }

    private static Module ida(DeletionContext ctx) {
        String skip = (ctx.tokenIds.isEmpty() && ctx.individualHashesBare.isEmpty())
                ? "no auth token or identity hash" : null;
        return new Module("ID Authentication", "ida:5446",
                List.of(
                        new Target("ida.identity_cache", "ida:5446  by token_id"),
                        new Target("ida.ident_binding_cert_store", "ida:5446  by token_id"),
                        new Target("ida.uin_auth_lock", "ida:5446  by token_id")),
                skip);
    }

    private static Module resident(DeletionContext ctx) {
        String skip = ctx.tokenIds.isEmpty() ? "no auth token id for this UIN" : null;
        return new Module("Resident Portal", "resident:5444  credential:5445  MinIO",
                List.of(new Target("resident VID_CARD_DOWNLOAD datashare + credential + row",
                        "resident:5444  by token_id")),
                skip);
    }

    private static Module esignet(boolean enabled) {
        String skip = enabled ? null : "esignet-cleanup-enabled = false";
        return new Module("eSignet & Mock Identity", "esignet:5455  mockidentity:5455",
                List.of(
                        new Target("esignet.consent_detail", "esignet:5455  by psu_token"),
                        new Target("esignet.consent_history", "esignet:5455  by psu_token"),
                        new Target("esignet.public_key_registry", "esignet:5455  by psu_token"),
                        new Target("mockidentitysystem.verified_claim", "mockidentity:5455  by UIN"),
                        new Target("mockidentitysystem.kyc_auth", "mockidentity:5455  by UIN"),
                        new Target("mockidentitysystem.mock_identity", "mockidentity:5455  by UIN")),
                skip);
    }
}
