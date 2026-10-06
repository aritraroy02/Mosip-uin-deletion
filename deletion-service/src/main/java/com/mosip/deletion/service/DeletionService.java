package com.mosip.deletion.service;

import com.mosip.deletion.audit.AuditRepository;
import com.mosip.deletion.config.DeletionProperties;
import com.mosip.deletion.console.ConsoleAudit;
import com.mosip.deletion.console.DeletionPlan;
import com.mosip.deletion.hash.UinHashService;
import com.mosip.deletion.model.CheckResult;
import com.mosip.deletion.model.DeletionResult;
import com.mosip.deletion.model.ModuleResult;
import com.mosip.deletion.model.ModuleStatus;
import com.mosip.deletion.steps.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the check-then-consent-then-delete flow.
 *
 * check(uin):
 *   1. already-deleted?  -> ALREADY_DELETED
 *   2. any data present? -> NO_DATA_AVAILABLE / AVAILABLE (+ summary)
 *
 * delete(uin): runs the modules in the design's order (section 7), never
 * stopping on a module failure -- each records its own outcome -- then writes
 * the audit row and returns the aggregated per-module status (section 14).
 */
@Service
public class DeletionService {

    private static final Logger log = LoggerFactory.getLogger(DeletionService.class);

    private final ContextResolver resolver;
    private final UinHashService hash;
    private final AuditRepository audit;
    private final RegistrationStep registration;
    private final IdRepositoryStep idRepository;
    private final IdaStep ida;
    private final ResidentStep resident;
    private final EsignetIdentityStep esignetIdentity;
    private final SelfRegistrationStep selfRegistration;
    private final ConsoleAudit console;
    private final DeletionProperties props;

    public DeletionService(ContextResolver resolver, UinHashService hash,
                           AuditRepository audit, RegistrationStep registration,
                           IdRepositoryStep idRepository, IdaStep ida,
                           ResidentStep resident, EsignetIdentityStep esignetIdentity,
                           SelfRegistrationStep selfRegistration,
                           ConsoleAudit console, DeletionProperties props) {
        this.resolver = resolver;
        this.hash = hash;
        this.audit = audit;
        this.registration = registration;
        this.idRepository = idRepository;
        this.ida = ida;
        this.resident = resident;
        this.esignetIdentity = esignetIdentity;
        this.selfRegistration = selfRegistration;
        this.console = console;
        this.props = props;
    }

    public CheckResult check(String uin) {
        String bare = hash.hashBare(uin);
        Optional<String> deletedAt = audit.findCompletedDeletion(bare);
        if (deletedAt.isPresent()) {
            return CheckResult.alreadyDeleted(deletedAt.get());
        }
        DeletionContext ctx = resolver.resolve(uin);
        if (!ctx.hasAnyData()) {
            return CheckResult.noData();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("registrationIds", ctx.regIds.size());
        summary.put("vids", ctx.vids.size());
        summary.put("uinRefIds", ctx.uinRefIds.size());
        summary.put("authTokens", ctx.tokenIds.size());
        return CheckResult.available(summary);
    }

    /**
     * Entry point for the token-secured REST API used by the CLI and Postman.
     *
     * Consent is never taken here: it was proven at eSignet before the caller
     * ever reached this service. The availability gate still applies, so nothing
     * to delete yields NOT_FOUND and an already-deleted UIN yields DELETED
     * without re-running a single module.
     */
    public DeletionResult executeAuthorized(String uin) {
        return executeAuthorized(uin, "REST  POST /api/deletion/execute   (verified token)");
    }

    /**
     * Same flow, but the caller says how the request arrived so the printed
     * audit names the real source instead of assuming the token API.
     */
    public DeletionResult executeAuthorized(String uin, String source) {
        console.header(uin, saltBucket(uin), bare(uin), prefixed(uin), source);
        CheckResult c = check(uin);
        return switch (c.availability()) {
            case ALREADY_DELETED -> {
                console.shortCircuit("ALREADY DELETED", c.message(),
                        "A completed deletion is already recorded for this UIN, so no "
                        + "table was read or written.");
                yield new DeletionResult(UUID.randomUUID().toString(),
                        hash.hashPrefixed(uin), hash.hashBare(uin), Instant.now(),
                        ModuleStatus.DELETED, List.of());
            }
            case NO_DATA_AVAILABLE -> {
                console.shortCircuit("NO DATA", c.message(),
                        "This UIN resolved to no rows in any module, so there was "
                        + "nothing to delete.");
                yield new DeletionResult(UUID.randomUUID().toString(),
                        hash.hashPrefixed(uin), hash.hashBare(uin), Instant.now(),
                        ModuleStatus.NOT_FOUND, List.of());
            }
            default -> deleteInternal(uin);
        };
    }

    /** Direct deletion, used by the terminal CLI. Prints its own audit header. */
    public DeletionResult delete(String uin) {
        console.header(uin, saltBucket(uin), bare(uin), prefixed(uin), "TERMINAL CLI");
        return deleteInternal(uin);
    }

    private DeletionResult deleteInternal(String uin) {
        long startedNanos = System.nanoTime();

        DeletionContext ctx = resolver.resolve(uin);
        console.resolved(ctx);
        console.plan(DeletionPlan.forContext(ctx,
                props.getDeletion().isEsignetCleanupEnabled(),
                props.getDeletion().isSelfRegistrationEnabled()));
        console.executeHeading();

        List<ModuleResult> modules = new ArrayList<>();

        // Design section 7 order. Each step never throws; a hard failure inside
        // a step is captured as FAILED for that module and the flow continues.
        modules.add(runModule("Registration", () -> registration.run(ctx)));
        modules.add(runModule("ID Repository", () -> idRepository.run(ctx)));
        modules.add(runModule("ID Authentication", () -> ida.run(ctx)));
        modules.add(runModule("Resident Portal", () -> resident.run(ctx)));
        modules.add(runModule("eSignet & Mock Identity", () -> esignetIdentity.run(ctx)));
        modules.add(runModule("Self-Registration", () -> selfRegistration.run(ctx)));

        DeletionResult result = new DeletionResult(
                UUID.randomUUID().toString(),
                ctx.hashPrefixed, ctx.hashBare, Instant.now(),
                DeletionResult.deriveOverall(modules), modules);

        boolean auditWritten = true;
        String auditError = null;
        try {
            audit.record(result);
        } catch (Exception e) {
            auditWritten = false;
            auditError = e.getMessage();
            log.error("failed to write audit record: {}", e.getMessage(), e);
        }

        console.result(result, (System.nanoTime() - startedNanos) / 1_000_000L,
                auditWritten, auditError);
        return result;
    }

    /** Run one module, settle its status, and print its outcome immediately. */
    private ModuleResult runModule(String name, java.util.function.Supplier<ModuleResult> step) {
        ModuleResult m = safe(name, step).finish();
        console.module(m);
        return m;
    }

    // Hash helpers for the console header. A failure here must not stop a
    // deletion, so they degrade to null and the header prints "?" instead.
    private Integer saltBucket(String uin) {
        try { return hash.saltId(uin); } catch (RuntimeException e) { return null; }
    }

    private String bare(String uin) {
        try { return hash.hashBare(uin); } catch (RuntimeException e) { return null; }
    }

    private String prefixed(String uin) {
        try { return hash.hashPrefixed(uin); } catch (RuntimeException e) { return null; }
    }

    private ModuleResult safe(String name, java.util.function.Supplier<ModuleResult> step) {
        try {
            return step.get();
        } catch (Exception e) {
            log.error("module {} failed hard: {}", name, e.getMessage(), e);
            ModuleResult m = new ModuleResult(name);
            m.step("module").failed(e.getMessage());
            return m.finish();
        }
    }
}
