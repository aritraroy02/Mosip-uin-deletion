package com.mosip.deletion.service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * All keys the deletion steps need, resolved read-only up front so that a step
 * which deletes a source table cannot rob a later step of a lookup key. Built
 * once by {@link ContextResolver} before any deletion runs.
 */
public class DeletionContext {

    public String uin;
    public String hashPrefixed;
    public String hashBare;

    /** RIDs for this identity (idrepo.uin + uin_h). */
    public final Set<String> regIds = new LinkedHashSet<>();
    /** uin_ref_id(s) (idrepo.uin + uin_h). */
    public final Set<String> uinRefIds = new LinkedHashSet<>();
    /** Plaintext VIDs for this UIN (idmap.vid). */
    public final Set<String> vids = new LinkedHashSet<>();
    /** Bare hashes of the UIN and each VID -- the individual_id_hash forms. */
    public final Set<String> individualHashesBare = new LinkedHashSet<>();
    /** Prefixed handle hashes (idrepo.handle). */
    public final Set<String> handleHashes = new LinkedHashSet<>();
    /** token_id(s) resolved from identity_cache / credential_request_status. */
    public final Set<String> tokenIds = new LinkedHashSet<>();

    /**
     * True only when the identity actually resolved to data in some store.
     * individualHashesBare is excluded on purpose -- it always contains the
     * UIN's own computed bare hash, which is not evidence that any row exists.
     */
    public boolean hasAnyData() {
        return !regIds.isEmpty() || !uinRefIds.isEmpty() || !vids.isEmpty()
                || !tokenIds.isEmpty() || !handleHashes.isEmpty();
    }
}
