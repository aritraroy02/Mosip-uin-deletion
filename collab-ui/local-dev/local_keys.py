#!/usr/bin/env python3
"""
Create the LOCAL-DEVELOPMENT keys and the eSignet client registration SQL.

Private keys are never committed, so a fresh clone has none. This script makes
them once, in collab-ui/local-dev/keys/ (git-ignored), and is safe to run again:
existing keys are kept, only the generated SQL is refreshed.

    keys/esignet-rp-private-key.pem     the delete-UIN eSignet client's key; the
                                        deletion service signs its client
                                        assertion with it
    keys/deletion-api-jwt-private.pem   signs test tokens for /api/deletion
                                        (postman/mint_jwt.py)
    keys/deletion-api-jwt-public.pem    verifies them (the deletion service)
    keys/register-client.sql            register-client.sql with this client
                                        key's public JWK filled in

The deletion service reads the keys from here by default (application.yml), so
nothing is copied into the build. start-all.ps1 runs this script, then applies
keys/register-client.sql to the local eSignet.

These keys are for the local mock eSignet only. Every other environment
generates its own (HANDOVER.md, section 5.4).

    python collab-ui/local-dev/local_keys.py

Needs the `cryptography` package (pip install cryptography).
"""

import base64
import json
import os
import sys

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
except ImportError:
    sys.exit("local_keys.py needs the 'cryptography' package: pip install cryptography")

HERE = os.path.dirname(os.path.abspath(__file__))
KEYS = os.path.join(HERE, "keys")
RP_KEY = os.path.join(KEYS, "esignet-rp-private-key.pem")
API_PRIVATE = os.path.join(KEYS, "deletion-api-jwt-private.pem")
API_PUBLIC = os.path.join(KEYS, "deletion-api-jwt-public.pem")
SQL_TEMPLATE = os.path.join(HERE, "register-client.sql")
SQL_OUT = os.path.join(KEYS, "register-client.sql")
JWK_PLACEHOLDER = "__RP_PUBLIC_JWK__"


def new_private_key(path):
    """RSA 2048, unencrypted PKCS#8 PEM: the format the deletion service reads."""
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    with open(path, "wb") as fh:
        fh.write(key.private_bytes(serialization.Encoding.PEM,
                                   serialization.PrivateFormat.PKCS8,
                                   serialization.NoEncryption()))
    return key


def load_private_key(path):
    with open(path, "rb") as fh:
        return serialization.load_pem_private_key(fh.read(), None)


def ensure_key(path):
    if os.path.exists(path):
        return load_private_key(path), False
    return new_private_key(path), True


def public_jwk(key):
    numbers = key.public_key().public_numbers()

    def b64(n):
        return base64.urlsafe_b64encode(
            n.to_bytes((n.bit_length() + 7) // 8, "big")).rstrip(b"=").decode()

    return json.dumps({"kty": "RSA", "e": b64(numbers.e), "use": "sig",
                       "alg": "RS256", "n": b64(numbers.n)}, separators=(",", ":"))


def main():
    os.makedirs(KEYS, exist_ok=True)

    rp_key, rp_new = ensure_key(RP_KEY)
    api_key, api_new = ensure_key(API_PRIVATE)
    if api_new or not os.path.exists(API_PUBLIC):
        with open(API_PUBLIC, "wb") as fh:
            fh.write(api_key.public_key().public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo))

    with open(SQL_TEMPLATE, encoding="utf-8") as fh:
        sql = fh.read()
    if JWK_PLACEHOLDER not in sql:
        sys.exit("register-client.sql has no %s placeholder" % JWK_PLACEHOLDER)
    with open(SQL_OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(sql.replace(JWK_PLACEHOLDER, public_jwk(rp_key)))

    print("local keys in %s" % KEYS)
    print("  eSignet client key     %s" % ("created" if rp_new else "kept"))
    print("  /api/deletion key pair %s" % ("created" if api_new else "kept"))
    print("  register-client.sql    written")


if __name__ == "__main__":
    main()
