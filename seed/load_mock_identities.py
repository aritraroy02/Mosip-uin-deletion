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

Usage:  python load_mock_identities.py            # all UINs from manifest.json
        python load_mock_identities.py 6743558386 # specific UIN(s)
"""

import datetime as dt
import json
import os
import sys
import urllib.request

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


def main():
    uins = sys.argv[1:]
    if not uins:
        m = json.load(open(MANIFEST, encoding="utf-8"))
        uins = [i["uin"] for i in m["identities"]]

    ok = 0
    for uin in uins:
        success, errs = upsert(uin)
        if success:
            ok += 1
            print(f"  {uin}  OK  (phone +91{uin}, pin 111111)")
        else:
            print(f"  {uin}  FAILED: {errs}")
    print(f"\nloaded {ok}/{len(uins)} identities into mock-identity-system")
    print("residents can now log in with these UINs (mock OTP is 111111)")


if __name__ == "__main__":
    main()
