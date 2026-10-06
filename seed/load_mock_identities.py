#!/usr/bin/env python3
"""
Load the seeded UINs into the mock eSignet identity system so a resident can log
in with a UIN that actually has data to delete.

The real dump UINs are HSM-encrypted and unrecoverable, so only the seeded
identities (seed/manifest.json) have known plaintext UINs. This registers each
of them in mock-identity-system (the eSignet OTP/userinfo backend) with
deterministic demographic claims, keyed by the same UIN that exists in the
deletion databases. After this, eSignet OTP login + fetchuserinfo resolves the
UIN, and that UIN is exactly the one the deletion service can act on.

A UIN that has already been deleted is NEVER re-created. The deletion flow
removes the resident's mock-identity record precisely so they can no longer
authenticate, and start-all.ps1 runs this loader on every startup. Without the
guard below, each restart would hand a deleted resident their login back while
their data stayed deleted, which breaks the "no further action is possible with
this UIN" requirement. The audit database is the source of truth for what has
been deleted, because the identity rows themselves are gone by then.

Usage:  python load_mock_identities.py            # all UINs from manifest.json
        python load_mock_identities.py 6743558386 # specific UIN(s)
        python load_mock_identities.py --force    # ignore the deletion guard
"""

import datetime as dt
import hashlib
import json
import os
import sys
import urllib.request

try:
    import psycopg
except ImportError:                                  # guard degrades, never blocks
    psycopg = None

PGHOST = os.environ.get("PGHOST", "127.0.0.1")
PGUSER = os.environ.get("PGUSER", "postgres")
PGPASSWORD = os.environ.get("PGPASSWORD", "postgres")
AUDIT_PORT = int(os.environ.get("AUDIT_PORT", "5447"))
AUDIT_DB = os.environ.get("AUDIT_DB", "mosip_deletion_audit")
IDREPO_PORT = int(os.environ.get("IDREPO_PORT", "5448"))
IDREPO_DB = os.environ.get("IDREPO_DB", "mosip_idrepo")
SALT_MODULO = 1000

MOCK_BASE = os.environ.get("MOCK_IDENTITY_BASE",
                           "http://localhost:8082/v1/mock-identity-system")
HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "manifest.json")

# 1x1 transparent PNG; the mock requires a non-empty encodedPhoto.
PHOTO = ("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0"
         "lEQVR42mNk+M8AAAMBAQAY3Y2wAAAAAElFTkSuQmCC")


def lang(value):
    return [{"language": "eng", "value": value}]


def identity_request(uin):
    last4 = uin[-4:]
    female = int(uin) % 2 == 0
    return {
        "individualId": uin,
        "pin": "111111",
        "fullName": lang(("Rita" if female else "Ravi") + " Resident " + last4),
        "givenName": lang("Rita" if female else "Ravi"),
        "familyName": lang("Resident " + last4),
        "gender": lang("Female" if female else "Male"),
        "dateOfBirth": "1990/01/01",
        "phone": "+91" + uin,                       # 10-digit UIN -> valid-ish E.164
        "email": "resident" + uin + "@example.test",
        "streetAddress": lang(last4 + " MOSIP Lane"),
        "locality": lang("Bengaluru"),
        "region": lang("Karnataka"),
        "country": lang("IND"),
        "postalCode": "560001",
        "preferredLang": "eng",
        "encodedPhoto": PHOTO,
    }


def post(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(MOCK_BASE + path, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read())


def put(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(MOCK_BASE + path, data=data, method="PUT",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read())


def upsert(uin):
    ts = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    body = {"requestTime": ts, "request": identity_request(uin)}
    resp = post("/identity", body)
    errs = resp.get("errors") or []
    if any(e.get("errorCode") == "duplicate_individual_id" for e in errs):
        resp = put("/identity", body)          # already there -> update
        errs = resp.get("errors") or []
    return (not errs), errs


def _connect(port, dbname):
    return psycopg.connect(host=PGHOST, port=port, dbname=dbname,
                           user=PGUSER, password=PGPASSWORD, connect_timeout=4)


def deleted_hashes():
    """
    Bare UIN hashes that already have a deletion on record.

    Any audit row counts, not only a fully completed one: a PARTIAL deletion
    still removed the resident's login, so restoring it would be wrong too.

    A missing database or table means this environment has never run a deletion,
    so the guard simply has nothing to block and returns an empty set.
    """
    if psycopg is None:
        print("  ! psycopg not installed, deletion guard disabled")
        return set()
    try:
        with _connect(AUDIT_PORT, AUDIT_DB) as conn, conn.cursor() as cur:
            cur.execute("SELECT uin_hash_bare FROM deletion.uin_deletion_audit")
            return {r[0] for r in cur.fetchall() if r[0]}
    except Exception as exc:                          # noqa: BLE001
        print(f"  ! audit database unreachable ({str(exc).strip()[:60]}), guard disabled")
        return set()


def hashes_for(uins):
    """
    uin -> bare hash. Taken from the manifest where possible, because that is
    free; any UIN not in the manifest (a real one passed on the command line)
    is hashed with the salt read from idrepo, the same way the service does it.
    """
    out = {}
    try:
        m = json.load(open(MANIFEST, encoding="utf-8"))
        out = {i["uin"]: i["uin_hash_bare"] for i in m["identities"] if "uin_hash_bare" in i}
    except Exception:                                 # noqa: BLE001
        pass

    missing = [u for u in uins if u not in out and u.isdigit()]
    if missing and psycopg is not None:
        try:
            with _connect(IDREPO_PORT, IDREPO_DB) as conn, conn.cursor() as cur:
                for uin in missing:
                    cur.execute("SELECT salt FROM idrepo.uin_hash_salt WHERE id = %s",
                                (int(uin) % SALT_MODULO,))
                    row = cur.fetchone()
                    if row:
                        out[uin] = hashlib.sha256((uin + row[0]).encode()).hexdigest().upper()
        except Exception:                             # noqa: BLE001
            pass
    return out


def main():
    args = [a for a in sys.argv[1:] if a != "--force"]
    force = "--force" in sys.argv[1:]

    uins = args
    if not uins:
        m = json.load(open(MANIFEST, encoding="utf-8"))
        uins = [i["uin"] for i in m["identities"]]

    skipped = []
    if not force:
        gone = deleted_hashes()
        if gone:
            hashed = hashes_for(uins)
            keep = []
            for uin in uins:
                if hashed.get(uin) in gone:
                    skipped.append(uin)
                else:
                    keep.append(uin)
            uins = keep

    ok = 0
    for uin in uins:
        success, errs = upsert(uin)
        if success:
            ok += 1
            print(f"  {uin}  OK  (phone +91{uin}, pin 111111)")
        else:
            print(f"  {uin}  FAILED: {errs}")

    for uin in skipped:
        print(f"  {uin}  SKIPPED - already deleted, login must stay revoked")

    print(f"\nloaded {ok}/{len(uins)} identities into mock-identity-system")
    if skipped:
        print(f"skipped {len(skipped)} deleted identities (use --force to override)")
    print("residents can now log in with these UINs (mock OTP is 111111)")


if __name__ == "__main__":
    main()
