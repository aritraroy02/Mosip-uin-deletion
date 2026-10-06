#!/usr/bin/env python3
"""
Print a 5-minute gateway JWT for a UIN, for testing the deletion service in
Postman (Postman can't sign RS256 in-app, so paste this into the `jwt`
collection variable).

    python mint_jwt.py 8617031759
"""
import base64, json, sys, time
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import load_pem_private_key
import os


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


# Settings come from postman/.env (template: .env.example) or the environment;
# without either, the local-development values apply.
HERE = os.path.dirname(os.path.abspath(__file__))
_load_dotenv(os.path.join(HERE, ".env"))

# Private half of the key the deletion service verifies /api/deletion tokens
# with (its DELETION_API_JWT_PUBLIC_KEY). Git-ignored; see README.
KEY = os.environ.get("DELETION_API_JWT_PRIVATE_KEY_PATH",
                     os.path.join(HERE, "gateway-signing-private.pem"))
ISSUER = os.environ.get("DELETION_API_JWT_ISSUER", "mosip-collab-auth-gateway")
AUDIENCE = os.environ.get("DELETION_API_JWT_AUDIENCE", "identity-data-deletion-service")
uin = sys.argv[1] if len(sys.argv) > 1 else "8617031759"

key = load_pem_private_key(open(KEY, "rb").read(), None)
b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=").decode()
now = int(time.time())
header = {"alg": "RS256", "typ": "JWT"}
payload = {"iss": ISSUER, "aud": AUDIENCE,
           "sub": uin, "uin": uin, "iat": now, "exp": now + 300, "jti": "postman-" + str(now)}
si = b(json.dumps(header).encode()) + "." + b(json.dumps(payload).encode())
jwt = si + "." + b(key.sign(si.encode(), padding.PKCS1v15(), hashes.SHA256()))
print(jwt)
