#!/usr/bin/env python3
"""
Print an eSignet private_key_jwt client assertion (valid 5 min), for the
eSignet Postman collection's /token request (Postman can't sign RS256 in-app,
so paste this into the `clientAssertion` collection variable).

    python mint_client_assertion.py
"""
import base64, json, time, uuid
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

KEY = os.environ.get("ESIGNET_RP_PRIVATE_KEY_PATH",
                     os.path.join(HERE, "..", "collab-ui", "local-dev", "keys",
                                  "esignet-rp-private-key.pem"))
CLIENT = os.environ.get("ESIGNET_CLIENT_ID", "mosip-collab-delete-uin-client")
AUD = os.environ.get("ESIGNET_TOKEN_URL", "http://localhost:8088/v1/esignet/oauth/v2/token")

key = load_pem_private_key(open(KEY, "rb").read(), None)
b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=").decode()
now = int(time.time())
header = {"alg": "RS256", "typ": "JWT"}
payload = {"iss": CLIENT, "sub": CLIENT, "aud": AUD, "iat": now, "exp": now + 300,
           "jti": str(uuid.uuid4())}
si = b(json.dumps(header).encode()) + "." + b(json.dumps(payload).encode())
print(si + "." + b(key.sign(si.encode(), padding.PKCS1v15(), hashes.SHA256())))
