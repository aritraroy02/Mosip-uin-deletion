package com.mosip.deletion.hash;

import com.mosip.deletion.config.Databases;
import com.mosip.deletion.config.DeletionProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Computes the MOSIP UIN/VID/handle hashes the deletion flow keys on
 * (design doc section 6).
 *
 * Algorithm, confirmed empirically against the real data by matching plaintext
 * VIDs to their stored hashes:
 *
 *     saltId   = int(identifier) % saltModulo
 *     salt     = uin_hash_salt[saltId]           (base64 string, used verbatim)
 *     hashBare = SHA-256(identifier + salt) as uppercase hex
 *     hashPrefixed = saltId + "_" + hashBare
 *
 * Some tables store the bare hash (identity_cache, credential_request_status,
 * uin_auth_lock); others the {saltId}_ prefixed form (idrepo.uin/uin_h, vid,
 * handle, identity_update_count_tracker). Callers pick the form per target.
 *
 * The salt table is identical across idmap/idrepo/ida in this estate; it is
 * loaded once from idrepo and cached.
 */
@Service
public class UinHashService {

    private final Databases db;
    private final int modulo;
    private volatile Map<Integer, String> saltCache;

    public UinHashService(Databases db, DeletionProperties props) {
        this.db = db;
        this.modulo = props.getDeletion().getSaltModulo();
    }

    private Map<Integer, String> salts() {
        if (saltCache == null) {
            synchronized (this) {
                if (saltCache == null) {
                    Map<Integer, String> m = new HashMap<>();
                    db.db("idrepo").query("SELECT id, salt FROM idrepo.uin_hash_salt",
                            (org.springframework.jdbc.core.RowCallbackHandler)
                            rs -> m.put(rs.getInt("id"), rs.getString("salt")));
                    saltCache = m;
                }
            }
        }
        return saltCache;
    }

    public int saltId(String identifier) {
        return (int) (Long.parseLong(identifier) % modulo);
    }

    private String saltFor(String identifier) {
        int id = saltId(identifier);
        String salt = salts().get(id);
        if (salt == null) {
            throw new IllegalStateException("no salt for bucket " + id);
        }
        return salt;
    }

    /** Bare uppercase-hex SHA-256(identifier + salt). */
    public String hashBare(String identifier) {
        return sha256Upper(identifier + saltFor(identifier));
    }

    /** {saltId}_{hashBare}. */
    public String hashPrefixed(String identifier) {
        return saltId(identifier) + "_" + hashBare(identifier);
    }

    private static String sha256Upper(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
