#!/usr/bin/env python3
"""
Remove everything seed.py created, using the manifest as the authority.

Every row is deleted by its recorded primary key and every object by its
recorded bucket+key, so this can only ever remove synthetic data -- it never
runs a broad DELETE against real rows. As a secondary check it reports (and can
optionally sweep) anything still tagged cr_by='synthetic-seed'.

Usage:
    python teardown.py                 # delete exactly what the manifest lists
    python teardown.py --verify        # report what remains, delete nothing
    python teardown.py --sweep-tag     # also DELETE rows where cr_by=synthetic-seed
"""

import argparse
import json
import os
import sys

import psycopg
from minio import Minio
from minio.error import S3Error

from seed import PORTS, DBNAME, PGHOST, PGUSER, PGPASSWORD, \
    MINIO_ENDPOINT, MINIO_KEY, MINIO_SECRET, MANIFEST, TAG

# module.schema.table -> (delete-by columns). Matches the pk dicts seed.py records.
TABLE_MODULE = {
    "idrepo.uin": "idrepo",
    "idrepo.uin_h": "idrepo",
    "idrepo.uin_document": "idrepo",
    "idrepo.uin_biometric": "idrepo",
    "idrepo.credential_request_status": "idrepo",
    "idrepo.uin_auth_lock": "idrepo",
    "idrepo.identity_update_count_tracker": "idrepo",
    "idrepo.handle": "idrepo",
    "idmap.vid": "idmap",
    "regprc.registration": "regprc",
    "regprc.registration_list": "regprc",
    "regprc.individual_demographic_dedup": "regprc",
    "regprc.reg_bio_ref": "regprc",
    "regprc.abis_request": "regprc",
    "regprc.registration_transaction": "regprc",
    "credential.credential_transaction": "credential",
    "ida.identity_cache": "ida",
    "ida.uin_auth_lock": "ida",
    "resident.resident_transaction": "resident",
}

# child-before-parent order so FK constraints never block a delete
DELETE_ORDER = [
    "regprc.individual_demographic_dedup",
    "regprc.reg_bio_ref",
    "regprc.registration_transaction",
    "regprc.abis_request",
    "regprc.registration_list",
    "regprc.registration",
    "idrepo.uin_document",
    "idrepo.uin_biometric",
    "idrepo.uin_h",
    "idrepo.uin",
    "idrepo.credential_request_status",
    "idrepo.uin_auth_lock",
    "idrepo.identity_update_count_tracker",
    "idrepo.handle",
    "idmap.vid",
    "credential.credential_transaction",
    "ida.identity_cache",
    "ida.uin_auth_lock",
    "resident.resident_transaction",
]

TAG_COLUMN_TABLES = {  # tables carrying cr_by, for the secondary sweep/report
    "idrepo": ["idrepo.uin", "idrepo.uin_h", "idrepo.uin_document",
               "idrepo.uin_biometric", "idrepo.credential_request_status",
               "idrepo.uin_auth_lock", "idrepo.handle"],
    "idmap": ["idmap.vid"],
    "regprc": ["regprc.registration", "regprc.registration_list",
               "regprc.individual_demographic_dedup", "regprc.reg_bio_ref",
               "regprc.abis_request", "regprc.registration_transaction"],
    "credential": ["credential.credential_transaction"],
    "ida": ["ida.identity_cache", "ida.uin_auth_lock"],
    "resident": ["resident.resident_transaction"],
}


def connect(module):
    c = psycopg.connect(host=PGHOST, port=PORTS[module], dbname=DBNAME[module],
                        user=PGUSER, password=PGPASSWORD, connect_timeout=5)
    c.autocommit = False
    return c


def load_manifest():
    if not os.path.exists(MANIFEST):
        sys.exit(f"no manifest at {MANIFEST} -- nothing to tear down")
    with open(MANIFEST, encoding="utf-8") as f:
        return json.load(f)


def delete_rows(conns, manifest):
    deleted = {}
    for table in DELETE_ORDER:
        module = TABLE_MODULE[table]
        conn = conns[module]
        for ident in manifest["identities"]:
            for pk in ident["rows"].get(f"{module}.{table}", []):
                where = " AND ".join(f"{c} = %s" for c in pk)
                with conn.cursor() as cur:
                    cur.execute(f"DELETE FROM {table} WHERE {where}",
                                list(pk.values()))
                    deleted[table] = deleted.get(table, 0) + cur.rowcount
    for c in conns.values():
        c.commit()
    return deleted


def delete_objects(minio, manifest):
    removed = {}
    for ident in manifest["identities"]:
        for obj in ident["objects"]:
            try:
                minio.remove_object(obj["bucket"], obj["key"])
                removed[obj["bucket"]] = removed.get(obj["bucket"], 0) + 1
            except S3Error:
                pass
    return removed


def report_tag(conns, do_delete):
    print(f"\nrows still tagged cr_by='{TAG}':")
    total = 0
    for module, tables in TAG_COLUMN_TABLES.items():
        conn = conns[module]
        for table in tables:
            with conn.cursor() as cur:
                cur.execute(f"SELECT count(*) FROM {table} WHERE cr_by = %s", (TAG,))
                n = cur.fetchone()[0]
                if n and do_delete:
                    cur.execute(f"DELETE FROM {table} WHERE cr_by = %s", (TAG,))
                    conn.commit()
                if n:
                    print(f"  {table:48} {n:>5}{'  (deleted)' if do_delete else ''}")
                    total += n
    if not total:
        print("  none")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true", help="report only, delete nothing")
    ap.add_argument("--sweep-tag", action="store_true",
                    help="also DELETE any row tagged cr_by=synthetic-seed")
    args = ap.parse_args()

    manifest = load_manifest()
    conns = {m: connect(m) for m in PORTS}
    minio = Minio(MINIO_ENDPOINT, access_key=MINIO_KEY, secret_key=MINIO_SECRET,
                  secure=False)
    try:
        if args.verify:
            report_tag(conns, do_delete=False)
            return
        rows = delete_rows(conns, manifest)
        objs = delete_objects(minio, manifest)
        print("deleted rows:")
        for t in sorted(rows):
            print(f"  {t:48} {rows[t]:>5}")
        print("deleted objects:")
        for b in sorted(objs):
            print(f"  {b:48} {objs[b]:>5}")
        report_tag(conns, do_delete=args.sweep_tag)
    finally:
        for c in conns.values():
            c.close()


if __name__ == "__main__":
    main()
