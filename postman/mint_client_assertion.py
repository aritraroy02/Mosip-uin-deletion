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

KEY = r"c:/Users/Harsh/Documents/GitHub/Mosip-uin-deletion/collab-ui/local-dev/esignet-rp-private-key.pem"
CLIENT = "mosip-collab-delete-uin-client"
AUD = "http://localhost:8088/v1/esignet/oauth/v2/token"

key = load_pem_private_key(open(KEY, "rb").read(), None)
b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=").decode()
now = int(time.time())
header = {"alg": "RS256", "typ": "JWT"}
payload = {"iss": CLIENT, "sub": CLIENT, "aud": AUD, "iat": now, "exp": now + 300,
           "jti": str(uuid.uuid4())}
si = b(json.dumps(header).encode()) + "." + b(json.dumps(payload).encode())
print(si + "." + b(key.sign(si.encode(), padding.PKCS1v15(), hashes.SHA256())))
