package com.mosip.deletion.api;

import com.mosip.deletion.config.DeletionProperties;
import com.mosip.deletion.console.ConsoleAudit;
import com.mosip.deletion.esignet.EsignetClient;
import com.mosip.deletion.model.DeletionResult;
import com.mosip.deletion.model.ModuleStatus;
import com.mosip.deletion.service.DeletionService;
import com.mosip.deletion.txn.TransactionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The page-facing API. The delete-uin page talks to this service directly:
 * there is no gateway in front any more, and no token is minted or verified
 * between two of our own processes.
 *
 *   POST /v1/delete-uin/start   {code, state, redirectUri, codeVerifier}
 *   GET  /v1/delete-uin/status  ?transactionId=...
 *   POST /v1/delete-uin/retry   {transactionId}
 *
 * The authorization code IS the proof of identity and consent: it is redeemed
 * here against eSignet using our own client credentials, and the UIN that comes
 * back never leaves this process. The page only ever sees a masked UIN.
 *
 * Response shape is unchanged from the old gateway, so the page needed nothing
 * but a new base URL. eSignet failures return 401 (the page restarts), an
 * unknown transaction 404, and a lapsed retry window 410.
 */
@RestController
@RequestMapping("/v1/delete-uin")
public class DeleteUinController {

    private static final Logger log = LoggerFactory.getLogger(DeleteUinController.class);

    private final EsignetClient esignet;
    private final DeletionService deletion;
    private final TransactionStore store;
    private final DeletionProperties props;
    private final ConsoleAudit console;

    public DeleteUinController(EsignetClient esignet, DeletionService deletion,
                               TransactionStore store, DeletionProperties props,
                               ConsoleAudit console) {
        this.esignet = esignet;
        this.deletion = deletion;
        this.store = store;
        this.props = props;
        this.console = console;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing code");
        }

        String uin;
        try {
            uin = esignet.resolveUin(code, body.get("redirectUri"), body.get("codeVerifier"));
        } catch (EsignetClient.EsignetException e) {
            // Authentication could not be established -> nothing is deleted.
            console.authFailed(e.getMessage());
            log.warn("eSignet verification failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        log.info("resolved UIN {} from the authorization code, running deletion", mask(uin));
        TransactionStore.Txn t = new TransactionStore.Txn();
        t.id = UUID.randomUUID().toString();
        t.uin = uin;
        t.maskedUin = mask(uin);
        t.retryExpiresEpoch = System.currentTimeMillis() / 1000L
                + props.getEsignet().getRetryWindowSeconds();
        runInto(t);
        store.put(t);
        return job(t);
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String transactionId) {
        return job(require(transactionId));
    }

    @PostMapping("/retry")
    public Map<String, Object> retry(@RequestBody Map<String, String> body) {
        TransactionStore.Txn t = require(body.get("transactionId"));
        if (store.expired(t)) {
            throw new ResponseStatusException(HttpStatus.GONE, "retry window expired");
        }
        runInto(t);
        store.put(t);
        return job(t);
    }

    /** Runs the deletion for this transaction and records the page-facing status. */
    private void runInto(TransactionStore.Txn t) {
        try {
            DeletionResult r = deletion.executeAuthorized(t.uin,
                    "PAGE  POST /v1/delete-uin/start   (eSignet authorization code)");
            t.lastResult = r;
            // DELETED and NOT_FOUND both mean nothing is left for this resident.
            t.status = (r.overall() == ModuleStatus.DELETED
                    || r.overall() == ModuleStatus.NOT_FOUND) ? "COMPLETED" : "FAILED";
        } catch (Exception e) {
            log.error("deletion failed for {}: {}", t.maskedUin, e.getMessage(), e);
            t.status = "FAILED";
        }
    }

    private TransactionStore.Txn require(String id) {
        TransactionStore.Txn t = id == null ? null : store.get(id);
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown transaction");
        }
        return t;
    }

    private Map<String, Object> job(TransactionStore.Txn t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionId", t.id);
        m.put("status", t.status);
        m.put("maskedUin", t.maskedUin);
        m.put("retryExpiresAt", Instant.ofEpochSecond(t.retryExpiresEpoch).toString());
        return m;
    }

    private String mask(String uin) {
        String last4 = uin.length() >= 4 ? uin.substring(uin.length() - 4) : uin;
        return "UIN •••• •••• " + last4;
    }
}
