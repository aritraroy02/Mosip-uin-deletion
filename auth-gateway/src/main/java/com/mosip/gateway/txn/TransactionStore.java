package com.mosip.gateway.txn;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory record of in-flight deletions, keyed by transactionId. Holds the
 * minted JWT (not the plaintext UIN) so a retry can re-call the deletion service
 * within the token's 5-minute life; the page only ever receives the masked UIN.
 * In-memory is sufficient for this single-instance local flow; a shared store
 * would be needed for multiple gateway instances.
 */
@Component
public class TransactionStore {

    public static final class Txn {
        public String id;
        public String status;          // page status: COMPLETED | FAILED
        public String maskedUin;
        public String gatewayJwt;
        public long jwtExpEpoch;
        public Map<?, ?> lastResult;
    }

    private final Map<String, Txn> byId = new ConcurrentHashMap<>();

    public void put(Txn t) { byId.put(t.id, t); }
    public Txn get(String id) { return byId.get(id); }
    public boolean exists(String id) { return byId.containsKey(id); }
}
