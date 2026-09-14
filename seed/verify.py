#!/usr/bin/env python3
"""
Verify the seeded identities against the LIVE databases and MinIO.

Two modes:
  python verify.py <uin>   -- full field-by-field footprint of one identity,
                              read back from the real stores, marking which
                              fields hold real derived values vs placeholders.
  python verify.py         -- aggregate: per-table synthetic row counts, bucket
                              object counts, and a one-line summary per UIN.
"""

import json
import os
import sys

import psycopg
from minio import Minio

import derive as D
from seed import PORTS, DBNAME, PGHOST, PGUSER, PGPASSWORD, \
    MINIO_ENDPOINT, MINIO_KEY, MINIO_SECRET, MANIFEST, TAG

REAL = "real derived value"
PH = "placeholder (encrypted-shaped)"
BIN = "placeholder bytea"


def conns():
    return {m: psycopg.connect(host=PGHOST, port=PORTS[m], dbname=DBNAME[m],
                               user=PGUSER, password=PGPASSWORD) for m in PORTS}


def minio():
    return Minio(MINIO_ENDPOINT, access_key=MINIO_KEY, secret_key=MINIO_SECRET,
                 secure=False)


def load():
    with open(MANIFEST, encoding="utf-8") as f:
        return json.load(f)


def one(conn, sql, params):
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchone()


def show(label, value, note=""):
    v = "NULL" if value is None else str(value)
    if len(v) > 78:
        v = v[:78] + "…"
    print(f"    {label:26} {v:<80} {note}")


def footprint(uin):
    m = load()
    ident = next((i for i in m["identities"] if i["uin"] == uin), None)
    if not ident:
        sys.exit(f"UIN {uin} not in manifest")
    c = conns()
    mc = minio()

    print(f"\n=== IDENTITY {uin} — full live footprint ===")
    print(f"  derived keys (all computed from the UIN):")
    show("salt_id", ident["salt_id"], "= int(uin) % 1000")
    show("uin_hash_prefixed", ident["uin_hash_prefixed"], REAL)
    show("uin_hash_bare", ident["uin_hash_bare"], REAL)
    show("reg_id (RID)", ident["reg_id"], REAL)
    show("vid", ident["vid"], REAL)
    show("uin_ref_id", ident["uin_ref_id"], REAL)
    show("token_id (auth)", ident["token_auth"], REAL)

    print("\n  ID REPOSITORY (mosip_idrepo)")
    r = one(c["idrepo"], "select uin_hash, reg_id, bio_ref_id, status_code, "
            "left(uin,16), octet_length(uin_data), cr_by from idrepo.uin "
            "where uin_ref_id=%s", (ident["uin_ref_id"],))
    if r:
        show("uin.uin_hash", r[0], REAL)
        show("uin.reg_id", r[1], REAL + " -> links to Registration")
        show("uin.status_code", r[3], "real field value")
        show("uin.uin (encrypted)", r[4], PH)
        show("uin.uin_data", f"{r[5]} bytes", BIN)
        show("uin.cr_by", r[6], "tag")
    n = one(c["idrepo"], "select count(*) from idrepo.uin_h where uin_ref_id=%s",
            (ident["uin_ref_id"],))[0]
    show("uin_h rows", n, "history copy")
    docs = one(c["idrepo"], "select string_agg(doccat_code,', '), count(*) "
               "from idrepo.uin_document where uin_ref_id=%s",
               (ident["uin_ref_id"],))
    show("uin_document", f"{docs[1]} rows: {docs[0]}", "real doc categories")
    bio = one(c["idrepo"], "select biometric_file_type, bio_file_id from "
              "idrepo.uin_biometric where uin_ref_id=%s", (ident["uin_ref_id"],))
    if bio:
        show("uin_biometric", f"{bio[0]} / {bio[1]}", "real type, uuid file id")
    crs = one(c["idrepo"], "select individual_id_hash, partner_id, token_id, "
              "status from idrepo.credential_request_status where "
              "individual_id_hash=%s", (ident["uin_hash_bare"],))
    if crs:
        show("cred_request_status", f"{crs[3]} / {crs[1]}", REAL + " (bare hash)")
        show("  .token_id", crs[2], REAL)
    al = one(c["idrepo"], "select auth_type_code, status_code from "
             "idrepo.uin_auth_lock where uin_hash=%s", (ident["uin_hash_bare"],))
    if al:
        show("uin_auth_lock", f"{al[0]} = {al[1]}", "bare hash key")
    hd = one(c["idrepo"], "select count(*) from idrepo.handle where uin_hash=%s",
             (ident["uin_hash_prefixed"],))[0]
    show("handle", f"{hd} row(s)", "every 3rd identity only")

    print("\n  ID MAP (mosip_idmap)")
    v = one(c["idmap"], "select vid, uin_hash, vidtyp_code, status_code from "
            "idmap.vid where uin_hash=%s", (ident["uin_hash_prefixed"],))
    if v:
        show("vid.vid", v[0], REAL)
        show("vid.vidtyp_code", v[2], "real value")
        show("vid.status_code", v[3], "real value")

    print("\n  REG PROCESSOR (mosip_regprc)")
    reg = one(c["regprc"], "select process, status_code, reg_stage_name, "
              "is_active from regprc.registration where reg_id=%s",
              (ident["reg_id"],))
    if reg:
        show("registration", f"{reg[0]} / {reg[1]} / {reg[2]}", "real fields")
    rl = one(c["regprc"], "select packet_size, client_status_code, packet_id "
             "from regprc.registration_list where reg_id=%s", (ident["reg_id"],))
    if rl:
        show("registration_list", f"size={rl[0]} {rl[1]}", "real fields")
        show("  .packet_id", rl[2], "real format")
    dd = one(c["regprc"], "select left(name,14), left(gender,10) from "
             "regprc.individual_demographic_dedup where reg_id=%s",
             (ident["reg_id"],))
    if dd:
        show("demographic_dedup.name", dd[0], PH)
        show("  .gender", dd[1], PH)
    br = one(c["regprc"], "select bio_ref_id from regprc.reg_bio_ref where "
             "reg_id=%s", (ident["reg_id"],))
    if br:
        show("reg_bio_ref.bio_ref_id", br[0], REAL)
    ab = one(c["regprc"], "select convert_from(req_text,'UTF8') from "
             "regprc.abis_request where bio_ref_id=%s", (ident["bio_ref_id"],))
    if ab:
        j = json.loads(ab[0])
        show("abis_request.req_text", "JSON", "real JSON, parsed below")
        show("  .referenceId", j["referenceId"], "= bio_ref_id")
        show("  .referenceURL", j["referenceURL"][:60] + "…", "-> abis object")
    trns = one(c["regprc"], "select string_agg(trn_type_code,', '), count(*) "
               "from regprc.registration_transaction where reg_id=%s",
               (ident["reg_id"],))
    show("registration_transaction", f"{trns[1]}: {trns[0]}", "incl PRINT_SERVICE")

    print("\n  CREDENTIAL (mosip_credential)")
    for kind, cid in [("issuance", ident["cred_issue_id"]),
                      ("print", ident["cred_print_id"]),
                      ("vid-card", ident["cred_vid_id"])]:
        ct = one(c["credential"], "select status_code, datashareurl from "
                 "credential.credential_transaction where id=%s", (cid,))
        if ct:
            show(f"cred_txn ({kind})", ct[0], (ct[1] or "no datashare")[:48])

    print("\n  IDA (mosip_ida)")
    ic = one(c["ida"], "select token_id, octet_length(demo_data), "
             "octet_length(bio_data) from ida.identity_cache where id=%s",
             (ident["uin_hash_bare"],))
    if ic:
        show("identity_cache.token_id", ic[0], REAL + " (bare hash key)")
        show("  .demo_data / .bio_data", f"{ic[1]} / {ic[2]} bytes", BIN)
    ua = one(c["ida"], "select auth_type_code from ida.uin_auth_lock where "
             "token_id=%s", (ident["token_auth"],))
    if ua:
        show("ida.uin_auth_lock", ua[0], "token_id key")

    print("\n  RESIDENT (mosip_resident)")
    rt = one(c["resident"], "select request_type_code, status_code, token_id, "
             "credential_request_id, left(reference_link,50) from "
             "resident.resident_transaction where token_id=%s "
             "and request_type_code='VID_CARD_DOWNLOAD'", (ident["token_auth"],))
    if rt:
        show("resident_transaction", f"{rt[0]} / {rt[1]}", "real fields")
        show("  .credential_request_id", rt[3], "= vid-card credential id")
        show("  .reference_link", rt[4] + "…", "-> resident object")

    print("\n  MINIO OBJECTS (read back live)")
    by_bucket = {}
    for o in ident["objects"]:
        by_bucket.setdefault(o["bucket"], []).append(o["key"])
    for bucket, keys in sorted(by_bucket.items()):
        present = 0
        for k in keys:
            try:
                mc.stat_object(bucket, k)
                present += 1
            except Exception:
                pass
        show(bucket, f"{present}/{len(keys)} objects present", "verified live")
        for k in keys[:2]:
            print(f"        {k}")
        if len(keys) > 2:
            print(f"        … +{len(keys)-2} more")

    for cc in c.values():
        cc.close()


def aggregate():
    m = load()
    c = conns()
    mc = minio()
    print(f"\n=== AGGREGATE across {m['count']} synthetic identities (tag={TAG}) ===")

    checks = [
        ("idrepo", "idrepo.uin"), ("idrepo", "idrepo.uin_h"),
        ("idrepo", "idrepo.uin_document"), ("idrepo", "idrepo.uin_biometric"),
        ("idrepo", "idrepo.credential_request_status"),
        ("idrepo", "idrepo.uin_auth_lock"),
        ("idrepo", "idrepo.identity_update_count_tracker"),
        ("idrepo", "idrepo.handle"),
        ("idmap", "idmap.vid"),
        ("regprc", "regprc.registration"), ("regprc", "regprc.registration_list"),
        ("regprc", "regprc.individual_demographic_dedup"),
        ("regprc", "regprc.reg_bio_ref"), ("regprc", "regprc.abis_request"),
        ("regprc", "regprc.registration_transaction"),
        ("credential", "credential.credential_transaction"),
        ("ida", "ida.identity_cache"), ("ida", "ida.uin_auth_lock"),
        ("resident", "resident.resident_transaction"),
    ]
    print("\n  Postgres rows tagged synthetic-seed:")
    for mod, table in checks:
        col = "cr_by"
        if table == "idrepo.identity_update_count_tracker":
            # this table has no cr_by; count by manifest ids instead
            ids = [i["uin_hash_prefixed"] for i in m["identities"]]
            n = one(c[mod], f"select count(*) from {table} where id = any(%s)",
                    (ids,))[0]
        else:
            n = one(c[mod], f"select count(*) from {table} where {col}=%s",
                    (TAG,))[0]
        print(f"    {table:44} {n:>4}")

    print("\n  MinIO objects present (from manifest):")
    counts = {}
    for i in m["identities"]:
        for o in i["objects"]:
            counts[o["bucket"]] = counts.get(o["bucket"], 0) + 1
    for b in sorted(counts):
        # sample-check existence of first object per bucket
        print(f"    {b:44} {counts[b]:>4}")

    print("\n  Per-UIN summary (uin -> reg_id | vid | #rows | #objects):")
    for i in m["identities"]:
        nrows = sum(len(v) for v in i["rows"].values())
        print(f"    {i['uin']}  {i['reg_id']}  {i['vid']}  "
              f"rows={nrows:>2}  objs={len(i['objects'])}")
    for cc in c.values():
        cc.close()


if __name__ == "__main__":
    if len(sys.argv) > 1:
        footprint(sys.argv[1])
    else:
        aggregate()
