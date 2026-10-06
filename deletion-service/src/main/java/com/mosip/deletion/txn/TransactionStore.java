package com.mosip.deletion.txn;

import com.mosip.deletion.model.DeletionResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory record of deletions started from the page, keyed by transactionId.
 *
 * It holds the resolved UIN so a retry can re-run without a second eSignet
 * login, which is why the retry window is bounded: once it passes, the entry is
 * refused and the resident must authenticate again. The page never receives the
 * UIN, only the masked form. One instance is enough for this single-process
 * deployment; several would need a shared store.
 */
@Component
public class TransactionStore {

    public static final class Txn {
        public String id;
        public String status;            // page status: COMPLETED | FAILED
        public String maskedUin;
        public String uin;               // never serialised to the page
        public long retryExpiresEpoch;
        public DeletionResult lastResult;
        public Map<String, Object> summary;
    }

    private final Map<String, Txn> byId = new ConcurrentHashMap<>();

    public void put(Txn t) { byId.put(t.id, t); }

    public Txn get(String id) { return byId.get(id); }

    public boolean expired(Txn t) {
        return System.currentTimeMillis() / 1000L >= t.retryExpiresEpoch;
    }
}
