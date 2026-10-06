package com.mosip.deletion.service;

import com.mosip.deletion.config.Databases;
import com.mosip.deletion.hash.UinHashService;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves every key the deletion needs, read-only, before anything is deleted.
 *
 * Ordering in the design (RegProc -> IdRepo -> IDA -> Resident) means a later
 * step could otherwise depend on a table an earlier step already removed (e.g.
 * Resident needs a token_id that lives in identity_cache, which IDA deletes).
 * Collecting all keys first removes that hazard entirely.
 */
@Service
public class ContextResolver {

    private final Databases db;
    private final UinHashService hash;

    public ContextResolver(Databases db, UinHashService hash) {
        this.db = db;
        this.hash = hash;
    }

    public DeletionContext resolve(String uin) {
        DeletionContext ctx = new DeletionContext();
        ctx.uin = uin;
        ctx.hashPrefixed = hash.hashPrefixed(uin);
        ctx.hashBare = hash.hashBare(uin);

        // RIDs and uin_ref_ids from idrepo.uin and idrepo.uin_h.
        for (String table : List.of("idrepo.uin", "idrepo.uin_h")) {
            db.db("idrepo").query(
                    "SELECT reg_id, uin_ref_id FROM " + table + " WHERE uin_hash = ?",
                    (RowCallbackHandler) rs -> {
                        ctx.regIds.add(rs.getString("reg_id"));
                        ctx.uinRefIds.add(rs.getString("uin_ref_id"));
                    }, ctx.hashPrefixed);
        }

        // VIDs, and the bare individual hashes (UIN + each VID).
        ctx.individualHashesBare.add(ctx.hashBare);
        ctx.vids.addAll(db.db("idmap").queryForList(
                "SELECT vid FROM idmap.vid WHERE uin_hash = ?", String.class, ctx.hashPrefixed));
        for (String vid : ctx.vids) {
            try {
                ctx.individualHashesBare.add(hash.hashBare(vid));
            } catch (RuntimeException ignore) {
                // non-numeric VID: cannot hash with the modulo scheme, skip
            }
        }

        // Handle hashes (stored prefixed in idrepo.handle).
        ctx.handleHashes.addAll(db.db("idrepo").queryForList(
                "SELECT handle_hash FROM idrepo.handle WHERE uin_hash = ?",
                String.class, ctx.hashPrefixed));

        // Design 9.2 step 3: the individual hash set must cover the UIN, every VID
        // AND every handle. handle_hash is stored in the {saltId}_{hash} form while
        // individual_id_hash is bare, so strip the salt prefix before adding it --
        // otherwise credentials issued against a handle are never found.
        for (String handleHash : ctx.handleHashes) {
            if (handleHash == null || handleHash.isBlank()) {
                continue;
            }
            int sep = handleHash.indexOf('_');
            ctx.individualHashesBare.add(sep >= 0 ? handleHash.substring(sep + 1) : handleHash);
        }

        // token_id(s): identity_cache (id = bare hash) then credential_request_status.
        // This replaces TokenIDGenerator (design 11.1): we do not hold the
        // generator's key material, but the token stored against the bare hash
        // is the same value, so a lookup is equivalent and works on real data.
        for (String h : ctx.individualHashesBare) {
            ctx.tokenIds.addAll(db.db("ida").queryForList(
                    "SELECT token_id FROM ida.identity_cache WHERE id = ?", String.class, h));
            ctx.tokenIds.addAll(db.db("idrepo").queryForList(
                    "SELECT token_id FROM idrepo.credential_request_status "
                    + "WHERE individual_id_hash = ? AND token_id IS NOT NULL",
                    String.class, h));
        }
        return ctx;
    }
}
