#!/usr/bin/env python3
"""
Seed the restored MOSIP estate with synthetic identities that have KNOWN UINs.

The real dumps contain no plaintext UIN -- every UIN column is HSM-encrypted --
so the deletion service cannot be exercised end to end against them. This script
inserts N synthetic identities whose plaintext UIN we choose, each with a full
cross-module footprint (Postgres rows in all six databases plus MinIO objects),
wired together with the same keys and derivations the real data uses. It then
writes a manifest mapping every UIN to all of its derived keys and to every row
and object created, which is both the test oracle and the teardown source.

Safety:
  * Every synthetic RID starts 99001 (no real RID does), every cr_by is
    'synthetic-seed', and every row/object is recorded in the manifest.
  * teardown.py deletes exactly what the manifest lists, so it can never touch
    real data.

Usage:
    python seed.py                 # create 50 identities, write manifest
    python seed.py --count 10      # create 10
    python seed.py --uins 3512806452,7788990011   # use specific UINs

Requires the docker stack (../docker) up. Reads the real uin_hash_salt table so
the hashes it computes match MOSIP's own.
"""

import argparse
import datetime as dt
import io
import json
import os
import random
import sys

import psycopg
from minio import Minio

import derive as D

PORTS = {
    "idmap": 5442, "idrepo": 5448, "regprc": 5443,
    "credential": 5445, "ida": 5446, "resident": 5444,
}
DBNAME = {
    "idmap": "mosip_idmap", "idrepo": "mosip_idrepo", "regprc": "mosip_regprc",
    "credential": "mosip_credential", "ida": "mosip_ida",
    "resident": "mosip_resident",
}


def _load_dotenv(path):
    """Fill os.environ from a KEY=VALUE .env file; real variables take precedence."""
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    os.environ.setdefault(key.strip(), value.strip())
    except FileNotFoundError:
        pass


# Settings come from seed/.env (template: .env.example) or the environment;
# teardown.py and verify.py import them from here. Without either, the local
# docker ports and credentials apply.
_load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))

# One PostgreSQL port for every database. Unset keeps the per-module ports.
if os.environ.get("PGPORT"):
    PORTS = {m: int(os.environ["PGPORT"]) for m in PORTS}

PGHOST = os.environ.get("PGHOST", "127.0.0.1")
PGUSER = os.environ.get("PGUSER", "postgres")
PGPASSWORD = os.environ.get("PGPASSWORD", "postgres")

MINIO_ENDPOINT = os.environ.get("MINIO_ENDPOINT", "127.0.0.1:9000")
MINIO_KEY = os.environ.get("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET = os.environ.get("MINIO_SECRET_KEY", "minioadmin")

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "manifest.json")

TAG = D.TAG


def connect(module):
    conn = psycopg.connect(host=PGHOST, port=PORTS[module], dbname=DBNAME[module],
                           user=PGUSER, password=PGPASSWORD, connect_timeout=5)
    conn.autocommit = False
    return conn


def load_salts(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT id, salt FROM idmap.uin_hash_salt")
        return {int(i): s for i, s in cur.fetchall()}


def gen_uins(count, explicit):
    if explicit:
        uins = [u.strip() for u in explicit.split(",") if u.strip()]
        for u in uins:
            if not (u.isdigit() and len(u) == 10):
                sys.exit(f"UIN {u!r} is not 10 digits")
        return uins
    rng = random.Random(20260830)          # fixed seed -> reproducible set
    seen, out = set(), []
    while len(out) < count:
        u = str(rng.randint(2_000_000_000, 9_999_999_999))   # 10 digits, no lead 0/1
        if u not in seen:
            seen.add(u)
            out.append(u)
    return out


def build_identity(uin, salts, when):
    """All derived keys for one identity -- the manifest's per-UIN record."""
    sid = D.salt_id_for(uin)
    salt = salts[sid]
    rid = D.reg_id(uin, when)
    return {
        "uin": uin,
        "salt_id": sid,
        "uin_hash_prefixed": D.uin_hash_prefixed(uin, salt),
        "uin_hash_bare": D.uin_hash_bare(uin, salt),
        "uin_ref_id": D.deterministic_uuid("uinref", uin),
        "workflow_instance_id": D.deterministic_uuid("wf", uin),
        "reg_id": rid,
        "vid": D.vid_for(uin, salt),
        "bio_ref_id": D.deterministic_uuid("bio", uin),
        "token_auth": D.token_id(uin, D.PARTNER_AUTH),
        "cred_issue_id": D.deterministic_uuid("credissue", uin),
        "cred_print_id": D.deterministic_uuid("credprint", uin),
        "cred_vid_id": D.deterministic_uuid("credvid", uin),
        "handle_id": D.deterministic_uuid("handle", uin),
        "rows": {},          # "module.schema.table" -> [pk dicts]
        "objects": [],       # [{bucket, key}]
    }


class Loader:
    """Collects inserts per module, plus row/object bookkeeping for the manifest."""

    def __init__(self, conns, minio):
        self.conns = conns
        self.minio = minio
        self.counts = {}

    def insert(self, ident, module, table, cols, vals, pk):
        placeholders = ", ".join(["%s"] * len(vals))
        collist = ", ".join(cols)
        sql = (f"INSERT INTO {table} ({collist}) VALUES ({placeholders}) "
               f"ON CONFLICT DO NOTHING")
        with self.conns[module].cursor() as cur:
            cur.execute(sql, vals)
        ident["rows"].setdefault(f"{module}.{table}", []).append(pk)
        self.counts[f"{module}.{table}"] = self.counts.get(f"{module}.{table}", 0) + 1

    def put(self, ident, bucket, key, data, content_type="application/octet-stream"):
        self.minio.put_object(bucket, key, io.BytesIO(data), length=len(data),
                              content_type=content_type)
        ident["objects"].append({"bucket": bucket, "key": key})
        self.counts[f"minio.{bucket}"] = self.counts.get(f"minio.{bucket}", 0) + 1


def seed_identity(ld, ident, salts, when):
    uin = ident["uin"]
    sid = ident["salt_id"]
    salt = salts[sid]
    Hp = ident["uin_hash_prefixed"]
    Hb = ident["uin_hash_bare"]
    refid = ident["uin_ref_id"]
    wfid = ident["workflow_instance_id"]
    rid = ident["reg_id"]
    vid = ident["vid"]
    bioref = ident["bio_ref_id"]
    tok = ident["token_auth"]
    now = when

    # ---- idrepo -----------------------------------------------------------
    ld.insert(ident, "idrepo", "idrepo.uin",
              ["uin_ref_id", "uin", "uin_hash", "uin_data", "uin_data_hash",
               "reg_id", "bio_ref_id", "status_code", "cr_by", "cr_dtimes",
               "is_deleted"],
              [refid, D.enc_placeholder(uin, sid, "uin"), Hp,
               D.enc_bytes(uin, "uindata", 512),
               D.uin_hash_bare(uin + "data", salt), rid, bioref,
               "ACTIVATED", TAG, now, False],
              {"uin_ref_id": refid})

    ld.insert(ident, "idrepo", "idrepo.uin_h",
              ["uin_ref_id", "eff_dtimes", "uin", "uin_hash", "uin_data",
               "uin_data_hash", "reg_id", "status_code", "cr_by", "cr_dtimes",
               "is_deleted"],
              [refid, now, D.enc_placeholder(uin, sid, "uin"), Hp,
               D.enc_bytes(uin, "uindata", 512),
               D.uin_hash_bare(uin + "data", salt), rid, "ACTIVATED", TAG,
               now, False],
              {"uin_ref_id": refid, "eff_dtimes": now.isoformat()})

    for cat, typ, fmt in [("proofOfAddress", "RNC", "pdf"),
                          ("proofOfIdentity", "CIN", "pdf")]:
        docid = D.deterministic_uuid(f"doc:{cat}", uin) + "." + fmt
        ld.insert(ident, "idrepo", "idrepo.uin_document",
                  ["uin_ref_id", "doccat_code", "doctyp_code", "doc_id",
                   "doc_name", "docfmt_code", "doc_hash", "cr_by", "cr_dtimes",
                   "is_deleted"],
                  [refid, cat, typ, docid, cat, fmt,
                   D.uin_hash_bare(uin + cat, salt), TAG, now, False],
                  {"uin_ref_id": refid, "doccat_code": cat})

    biofile = D.deterministic_uuid("biofile", uin) + ".cbeff"
    ld.insert(ident, "idrepo", "idrepo.uin_biometric",
              ["uin_ref_id", "biometric_file_type", "bio_file_id",
               "biometric_file_name", "biometric_file_hash", "cr_by",
               "cr_dtimes", "is_deleted"],
              [refid, "individualBiometrics", biofile,
               "individualBiometrics_bio_CBEFF",
               D.uin_hash_bare(uin + "bio", salt), TAG, now, False],
              {"uin_ref_id": refid, "bio_file_id": biofile})

    ld.insert(ident, "idrepo", "idrepo.credential_request_status",
              ["individual_id", "individual_id_hash", "partner_id",
               "request_id", "token_id", "status", "cr_by", "cr_dtimes",
               "is_deleted"],
              [D.enc_placeholder(uin, sid, "indid"), Hb, D.PARTNER_AUTH,
               ident["cred_issue_id"], tok, "STORED", TAG, now, False],
              {"individual_id_hash": Hb, "partner_id": D.PARTNER_AUTH})

    ld.insert(ident, "idrepo", "idrepo.uin_auth_lock",
              ["uin_hash", "auth_type_code", "lock_request_datetime",
               "lock_start_datetime", "status_code", "cr_by", "cr_dtimes",
               "is_deleted"],
              [Hb, "demo", now, now, "true", TAG, now, False],
              {"uin_hash": Hb, "auth_type_code": "demo"})

    ld.insert(ident, "idrepo", "idrepo.identity_update_count_tracker",
              ["id", "identity_update_count"],
              [Hp, D.enc_bytes(uin, "updcount", 8)],
              {"id": Hp})

    # handle only for every 3rd identity (they are rare in the real data)
    if int(uin) % 3 == 0:
        handle_val = D.enc_placeholder(uin, sid, "handle")
        ld.insert(ident, "idrepo", "idrepo.handle",
                  ["id", "uin_hash", "handle", "handle_hash", "cr_by",
                   "cr_dtimes"],
                  [ident["handle_id"], Hp, handle_val,
                   D.generic_hash_prefixed(uin + "handle", sid, salt), TAG, now],
                  {"id": ident["handle_id"]})

    # ---- idmap ------------------------------------------------------------
    vid_pk = D.deterministic_uuid("vidpk", uin)
    ld.insert(ident, "idmap", "idmap.vid",
              ["id", "vid", "uin_hash", "uin", "vidtyp_code",
               "generated_dtimes", "expiry_dtimes", "status_code", "cr_by",
               "cr_dtimes", "is_deleted"],
              [vid_pk, vid, Hp, D.enc_placeholder(uin, sid, "uin"),
               "PERPETUAL", now, dt.datetime(9999, 12, 31), "ACTIVE", TAG,
               now, False],
              {"id": vid_pk})

    # ---- regprc -----------------------------------------------------------
    ld.insert(ident, "regprc", "regprc.registration",
              ["reg_id", "process", "status_code", "lang_code",
               "latest_trn_type_code", "latest_trn_status_code",
               "reg_stage_name", "is_active", "cr_by", "cr_dtimes",
               "workflow_instance_id", "is_deleted"],
              [rid, "NEW", "PROCESSED", "eng", "PRINT_SERVICE", "PROCESSED",
               "PrintStage", True, TAG, now, wfid, False],
              {"workflow_instance_id": wfid})

    ld.insert(ident, "regprc", "regprc.registration_list",
              ["workflow_instance_id", "reg_id", "process", "packet_checksum",
               "packet_size", "client_status_code", "lang_code", "cr_by",
               "cr_dtimes", "packet_id", "ref_id", "is_deleted"],
              [wfid, rid, "NEW", D.uin_hash_bare(uin + "pkt", salt), 171793,
               "APPROVED", "eng", TAG, now,
               f"{rid}-{D.REF_ID}-{now.strftime('%Y%m%d%H%M%S')}", D.REF_ID,
               False],
              {"workflow_instance_id": wfid})

    ld.insert(ident, "regprc", "regprc.individual_demographic_dedup",
              ["reg_id", "name", "dob", "gender", "mobile_number", "email",
               "lang_code", "is_active", "cr_by", "cr_dtimes",
               "workflow_instance_id", "process", "iteration"],
              [rid, D.enc_placeholder(uin, sid, "name", 120),
               D.enc_placeholder(uin, sid, "dob", 60),
               D.enc_placeholder(uin, sid, "gender", 60),
               D.enc_placeholder(uin, sid, "mob", 60),
               D.enc_placeholder(uin, sid, "email", 120), "eng", True, TAG, now,
               wfid, "NEW", 1],
              {"reg_id": rid, "workflow_instance_id": wfid})

    ld.insert(ident, "regprc", "regprc.reg_bio_ref",
              ["reg_id", "bio_ref_id", "is_active", "cr_by", "cr_dtimes",
               "workflow_instance_id", "process", "iteration"],
              [rid, bioref, True, TAG, now, wfid, "NEW", 1],
              {"reg_id": rid, "bio_ref_id": bioref})

    # ABIS request whose req_text JSON points at an ABIS datashare object
    abis_key, abis_url = D.datashare(D.POLICY_ABIS, D.PARTNER_ABIS, now,
                                     D.rand_suffix(uin, "abis"))
    abis_req = {
        "id": "mosip.abis.insert", "version": "1.1",
        "requestId": D.deterministic_uuid("abisreq", uin),
        "requesttime": now.isoformat() + "Z", "referenceId": bioref,
        "referenceURL": abis_url,
    }
    abis_id = D.deterministic_uuid("abis", uin)
    ld.insert(ident, "regprc", "regprc.abis_request",
              ["id", "req_batch_id", "abis_app_code", "request_type",
               "request_dtimes", "bio_ref_id", "req_text", "status_code",
               "lang_code", "cr_by", "cr_dtimes", "is_deleted"],
              [abis_id, D.deterministic_uuid("abisbatch", uin), "ABIS1",
               "INSERT", now, bioref,
               json.dumps(abis_req).encode(), "PROCESSED", "eng", TAG, now,
               False],
              {"id": abis_id})
    ld.put(ident, D.POLICY_ABIS, abis_key, D.enc_bytes(uin, "abisobj", 4096))

    # PRINT_SERVICE transaction -> credential_transaction via ref_id
    for trn, ref in [("PACKET_RECEIVER", None),
                     ("PRINT_SERVICE", ident["cred_print_id"])]:
        trn_id = D.deterministic_uuid(f"trn:{trn}", uin)
        ld.insert(ident, "regprc", "regprc.registration_transaction",
                  ["id", "reg_id", "trn_type_code", "ref_id", "ref_id_type",
                   "status_code", "sub_status_code", "lang_code", "cr_by",
                   "cr_dtimes", "is_deleted"],
                  [trn_id, rid, trn, ref,
                   "updated registration record" if ref else None,
                   "PROCESSED", "RPR-SUCCESS", "eng", TAG, now, False],
                  {"id": trn_id})

    # ---- credential -------------------------------------------------------
    # issuance credential (found via credential_request_status.request_id)
    issue_key, issue_url = D.datashare(D.POLICY_AUTH, D.PARTNER_AUTH, now,
                                       D.rand_suffix(uin, "issue"))
    ld.insert(ident, "credential", "credential.credential_transaction",
              ["id", "credential_id", "status_code", "datashareurl", "cr_by",
               "cr_dtimes", "is_deleted"],
              [ident["cred_issue_id"], D.deterministic_uuid("credidI", uin),
               "STORED", issue_url, TAG, now, False],
              {"id": ident["cred_issue_id"]})
    ld.put(ident, D.POLICY_AUTH, issue_key, D.enc_bytes(uin, "issueobj", 4096))

    # print credential (found via registration_transaction PRINT_SERVICE ref_id)
    print_key, print_url = D.datashare(D.POLICY_AUTH, D.PARTNER_AUTH, now,
                                       D.rand_suffix(uin, "print"))
    ld.insert(ident, "credential", "credential.credential_transaction",
              ["id", "credential_id", "status_code", "datashareurl", "cr_by",
               "cr_dtimes", "is_deleted"],
              [ident["cred_print_id"], D.deterministic_uuid("credidP", uin),
               "STORED", print_url, TAG, now, False],
              {"id": ident["cred_print_id"]})
    ld.put(ident, D.POLICY_AUTH, print_key, D.enc_bytes(uin, "printobj", 4096))

    # vid-card credential (found via resident_transaction.credential_request_id)
    ld.insert(ident, "credential", "credential.credential_transaction",
              ["id", "credential_id", "status_code", "cr_by", "cr_dtimes",
               "is_deleted"],
              [ident["cred_vid_id"], D.deterministic_uuid("credidV", uin),
               "STORED", TAG, now, False],
              {"id": ident["cred_vid_id"]})

    # ---- ida --------------------------------------------------------------
    ld.insert(ident, "ida", "ida.identity_cache",
              ["id", "token_id", "demo_data", "bio_data", "cr_by", "cr_dtimes",
               "is_deleted"],
              [Hb, tok, D.enc_bytes(uin, "demo", 256),
               D.enc_bytes(uin, "bio", 512), TAG, now, False],
              {"id": Hb})

    ld.insert(ident, "ida", "ida.uin_auth_lock",
              ["token_id", "auth_type_code", "lock_request_datetime",
               "lock_start_datetime", "status_code", "lang_code", "cr_by",
               "cr_dtimes", "is_deleted"],
              [tok, "Bio-Iris", now, now, "true", "NA", TAG, now, False],
              {"token_id": tok, "auth_type_code": "Bio-Iris"})

    # ---- resident ---------------------------------------------------------
    res_key, res_url = D.datashare(D.POLICY_RESIDENT, D.PARTNER_RESIDENT, now,
                                   D.rand_suffix(uin, "vidcard"))
    event_id = f"99{int(D.token_id(uin, 'event')[:14]):014d}"
    ld.insert(ident, "resident", "resident.resident_transaction",
              ["event_id", "request_dtimes", "response_dtime",
               "request_type_code", "request_summary", "status_code",
               "token_id", "reference_link", "credential_request_id",
               "individual_id", "cr_by", "cr_dtimes", "is_deleted",
               "read_status", "pinned_status"],
              [event_id, now, now, "VID_CARD_DOWNLOAD", "VID card download",
               "CARD_DOWNLOADED", tok, res_url, ident["cred_vid_id"],
               D.enc_placeholder(uin, sid, "indid"), TAG, now, False, True,
               False],
              {"event_id": event_id})
    ld.put(ident, D.POLICY_RESIDENT, res_key, D.enc_bytes(uin, "vidcardobj", 4096))

    # ---- packet-manager + landing-zone objects (keyed by RID) -------------
    for proc_key in [f"{rid}/REGISTRATION_CLIENT/NEW/{rid}_id",
                     f"{rid}/REGISTRATION_CLIENT/NEW/{rid}_evidence",
                     f"{rid}/REGISTRATION_CLIENT/NEW/{rid}_optional"]:
        ld.put(ident, "packet-manager", proc_key, D.enc_bytes(uin, proc_key, 2048))
    for tag_key, val in [("Tags/AGE_GROUP", b"ADULT"),
                         ("Tags/ID_OBJECT-gender", b"MALE")]:
        ld.put(ident, "packet-manager", f"{rid}/{tag_key}", val)

    lz_key = f"{rid}/{rid}-{D.REF_ID}-{now.strftime('%Y%m%d%H%M%S')}"
    ld.put(ident, "landing-zone", lz_key, D.enc_bytes(uin, "landingzone", 4096))


def main():
    ap = argparse.ArgumentParser(description="Seed synthetic MOSIP identities.")
    ap.add_argument("--count", type=int, default=50)
    ap.add_argument("--uins", help="comma-separated 10-digit UINs to use")
    args = ap.parse_args()

    uins = gen_uins(args.count, args.uins)
    conns = {m: connect(m) for m in PORTS}
    minio = Minio(MINIO_ENDPOINT, access_key=MINIO_KEY, secret_key=MINIO_SECRET,
                  secure=False)
    for b in ("packet-manager", "landing-zone", D.POLICY_AUTH, D.POLICY_ABIS,
              D.POLICY_RESIDENT):
        if not minio.bucket_exists(b):
            minio.make_bucket(b)

    salts = load_salts(conns["idmap"])
    ld = Loader(conns, minio)
    base = dt.datetime(2026, 8, 30, 12, 0, 0)

    identities = []
    try:
        for i, uin in enumerate(uins):
            when = base + dt.timedelta(seconds=i)
            ident = build_identity(uin, salts, when)
            seed_identity(ld, ident, salts, when)
            identities.append(ident)
        for c in conns.values():
            c.commit()
    except Exception:
        for c in conns.values():
            c.rollback()
        raise
    finally:
        for c in conns.values():
            c.close()

    manifest = {
        "generated": dt.datetime.now().isoformat(),
        "tag": TAG,
        "count": len(identities),
        "identities": identities,
    }
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, default=str)

    print(f"seeded {len(identities)} identities")
    print(f"manifest: {MANIFEST}")
    print("\nrows/objects created:")
    for k in sorted(ld.counts):
        print(f"  {k:48} {ld.counts[k]:>5}")
    print("\nsample identities (UIN -> RID):")
    for ident in identities[:5]:
        print(f"  {ident['uin']}  ->  reg_id {ident['reg_id']}  vid {ident['vid']}")


if __name__ == "__main__":
    main()
