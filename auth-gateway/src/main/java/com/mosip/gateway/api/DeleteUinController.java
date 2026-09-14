package com.mosip.gateway.api;

import com.mosip.gateway.deletion.DeletionClient;
import com.mosip.gateway.esignet.EsignetClient;
import com.mosip.gateway.esignet.PsutResolver;
import com.mosip.gateway.jwt.GatewayTokenService;
import com.mosip.gateway.txn.TransactionStore;
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
 * Front-facing API the delete-uin page calls (contract defined by the page):
 *
 *   POST /v1/delete-uin/start   {code, state, redirectUri, codeVerifier}
 *   GET  /v1/delete-uin/status  ?transactionId=...
 *   POST /v1/delete-uin/retry   {transactionId}
 *
 * The page only ever sees {transactionId, status, maskedUin, retryExpiresAt}.
 * eSignet failures return 401 (page restarts), a lost transaction 404, and an
 * expired retry window 410, matching the page's error handling.
 */
@RestController
@RequestMapping("/v1/delete-uin")
public class DeleteUinController {

    private static final Logger log = LoggerFactory.getLogger(DeleteUinController.class);

    private final EsignetClient esignet;
    private final PsutResolver psutResolver;
    private final GatewayTokenService tokens;
    private final DeletionClient deletion;
    private final TransactionStore store;

    public DeleteUinController(EsignetClient esignet, PsutResolver psutResolver,
                              GatewayTokenService tokens, DeletionClient deletion,
                              TransactionStore store) {
        this.esignet = esignet;
        this.psutResolver = psutResolver;
        this.tokens = tokens;
        this.deletion = deletion;
        this.store = store;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        String redirectUri = body.get("redirectUri");
        String codeVerifier = body.get("codeVerifier");
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing code");
        }

        String subject;
        try {
            subject = esignet.resolveUin(code, redirectUri, codeVerifier);
        } catch (EsignetClient.EsignetException e) {
            // eSignet verification failed -> page shows "verification no longer valid"
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        // userinfo may give the UIN directly (real IDA plugin) or only a pairwise
        // pseudonym (mock). If it is not the numeric UIN, map the pseudonym back
        // to the real UIN via the mock identity system's kyc_auth table.
        String uin = subject;
        if (!subject.matches("\\d+")) {
            uin = psutResolver.toUin(subject);
            if (uin == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "could not resolve UIN from authenticated subject");
            }
        }

        GatewayTokenService.MintedToken minted;
        try {
            minted = tokens.mintForUin(uin);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "could not mint token");
        }

        log.info("resolved UIN {} -> minted 5-min JWT, calling deletion service", mask(uin));
        DeletionClient.Outcome outcome;
        try {
            outcome = deletion.execute(minted.jwt());
        } catch (DeletionClient.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token rejected");
        }
        log.info("deletion service returned status={} for UIN {}", outcome.pageStatus(), mask(uin));

        TransactionStore.Txn t = new TransactionStore.Txn();
        t.id = UUID.randomUUID().toString();
        t.status = outcome.pageStatus();
        t.maskedUin = mask(uin);
        t.gatewayJwt = minted.jwt();
        t.jwtExpEpoch = minted.expiresAtEpochSeconds();
        t.lastResult = outcome.raw();
        store.put(t);
        return job(t);
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String transactionId) {
        TransactionStore.Txn t = store.get(transactionId);
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown transaction");
        }
        return job(t);
    }

    @PostMapping("/retry")
    public Map<String, Object> retry(@RequestBody Map<String, String> body) {
        String id = body.get("transactionId");
        TransactionStore.Txn t = id == null ? null : store.get(id);
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown transaction");
        }
        if (System.currentTimeMillis() / 1000L >= t.jwtExpEpoch) {
            throw new ResponseStatusException(HttpStatus.GONE, "retry window expired");
        }
        try {
            DeletionClient.Outcome outcome = deletion.execute(t.gatewayJwt);
            t.status = outcome.pageStatus();
            t.lastResult = outcome.raw();
        } catch (DeletionClient.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.GONE, "token no longer valid");
        }
        store.put(t);
        return job(t);
    }

    private Map<String, Object> job(TransactionStore.Txn t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionId", t.id);
        m.put("status", t.status);
        m.put("maskedUin", t.maskedUin);
        m.put("retryExpiresAt", Instant.ofEpochSecond(t.jwtExpEpoch).toString());
        return m;
    }

    private String mask(String uin) {
        String last4 = uin.length() >= 4 ? uin.substring(uin.length() - 4) : uin;
        return "UIN •••• •••• " + last4;
    }
}
