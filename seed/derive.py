"""
Key derivation for synthetic MOSIP identities.

Every identity in the seed starts from a known 10-digit plaintext UIN. Every
other key an identity needs -- hashes, RID, VID, uin_ref_id, token ids, object
keys -- is derived deterministically from that UIN here, so the same UIN always
produces the same estate footprint and the deletion service can be driven from
the UIN alone.

The hash algorithm is the one confirmed empirically against the real data:
    uin_hash = SHA256(identifier + salt).hexdigest().upper()
    salt     = uin_hash_salt[int(identifier) % 1000]
stored bare in ida.identity_cache / credential_request_status, and prefixed
with "{saltId}_" in idrepo.uin / idmap.vid. Seven exact matches were found
probing plaintext VIDs against the real tables, so this is MOSIP's real scheme,
not a guess -- which means the service's hashing logic is genuinely exercised.

The encrypted `uin` / `uin_data` columns are NOT real MOSIP ciphertext: that
needs the Keymanager HSM key, which is not in the dumps. They get a
realistic-looking {saltId}_...#KEY_SPLITTER#... placeholder. Nothing in the
deletion flow reads them -- it keys on the hash -- so the placeholder is
sufficient, and the true UIN is recorded in the manifest instead.
"""

import base64
import hashlib
import os
import uuid

# Fixed namespace so uin -> uuid mappings are stable across runs.
NS = uuid.UUID("6f4d0515-9d3e-5f2a-b7c1-4d0a2e6b8c11")

# Reserved center id: every synthetic RID starts 99001, which no real RID in
# this estate does (they all start 10xxx), so synthetic packets are instantly
# distinguishable and can never collide with real ones.
CENTER = "99001"
MACHINE = "10000"
REF_ID = CENTER + "_" + MACHINE          # e.g. 99001_10000

KEY_SPLITTER = "#KEY_SPLITTER#"
TAG = "synthetic-seed"                    # written to cr_by for easy teardown

PARTNER_AUTH = "mpartner-default-auth"
POLICY_AUTH = "mpolicy-default-auth"
PARTNER_ABIS = "mpartner-default-abis"
POLICY_ABIS = "mpolicy-default-abis"
PARTNER_RESIDENT = "mpartner-default-resident"
POLICY_RESIDENT = "mpolicy-default-resident"

DATASHARE_BASE = "http://datashare.datashare/v1/datashare/get"


def _sha_upper(text):
    return hashlib.sha256(text.encode()).hexdigest().upper()


def _b64u(raw):
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def salt_id_for(uin):
    return int(uin) % 1000


def uin_hash_bare(uin, salt):
    return _sha_upper(uin + salt)


def uin_hash_prefixed(uin, salt):
    return f"{salt_id_for(uin)}_{uin_hash_bare(uin, salt)}"


def generic_hash_prefixed(value, salt_id, salt):
    return f"{salt_id}_{_sha_upper(value + salt)}"


def deterministic_uuid(kind, uin):
    return str(uuid.uuid5(NS, f"{kind}:{uin}"))


def token_id(uin, partner):
    """36-digit numeric token, matching TokenIDGenerator's stored shape."""
    digest = hashlib.sha256(f"{uin}|{partner}".encode()).hexdigest()
    return f"{int(digest, 16) % (10 ** 36):036d}"


def reg_id(uin, when):
    """29-digit RID: center(5) machine(5) seq(5) timestamp(14)."""
    seq = f"{int(uin) % 100000:05d}"
    return CENTER + MACHINE + seq + when.strftime("%Y%m%d%H%M%S")


def vid_for(uin, salt):
    """16-digit VID, prefixed 99 so it is visibly synthetic."""
    tail = int(hashlib.sha256((uin + salt + "vid").encode()).hexdigest()[:14], 16)
    return f"99{tail % (10 ** 14):014d}"


def enc_placeholder(uin, salt_id, kind="uin", maxlen=None):
    """
    A {saltId}_...#KEY_SPLITTER#... value shaped like MOSIP ciphertext.

    maxlen caps the result for tight columns (e.g. the varchar(64) demographic
    fields). Truncating a placeholder is harmless -- nothing decrypts it.
    """
    left = _b64u(os.urandom(24))
    mid = _b64u(KEY_SPLITTER.encode())
    right = _b64u(hashlib.sha256(f"{uin}|{kind}".encode()).digest())
    out = f"{salt_id}_{left}{mid}{right}"
    return out[:maxlen] if maxlen else out


def enc_bytes(uin, kind, size=256):
    """Deterministic placeholder bytea for uin_data / demo_data / bio_data."""
    out = bytearray()
    seed = f"{uin}|{kind}".encode()
    while len(out) < size:
        seed = hashlib.sha256(seed).digest()
        out.extend(seed)
    return bytes(out[:size])


def datashare(policy, partner, when, suffix):
    key = f"{partner}{policy}{when.strftime('%Y%m%d%H%M%S')}{suffix}"
    url = f"{DATASHARE_BASE}/{policy}/{partner}/{key}"
    return key, url


def rand_suffix(uin, kind):
    return _b64u(hashlib.sha256(f"{uin}|{kind}|suffix".encode()).digest())[:11]
