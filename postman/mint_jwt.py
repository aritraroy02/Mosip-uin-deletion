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

KEY = r"c:/Users/Harsh/Documents/GitHub/Mosip-uin-deletion/auth-gateway/src/main/resources/gateway-signing-private.pem"
uin = sys.argv[1] if len(sys.argv) > 1 else "8617031759"

key = load_pem_private_key(open(KEY, "rb").read(), None)
b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=").decode()
now = int(time.time())
header = {"alg": "RS256", "typ": "JWT"}
payload = {"iss": "mosip-collab-auth-gateway", "aud": "identity-data-deletion-service",
           "sub": uin, "uin": uin, "iat": now, "exp": now + 300, "jti": "postman-" + str(now)}
si = b(json.dumps(header).encode()) + "." + b(json.dumps(payload).encode())
jwt = si + "." + b(key.sign(si.encode(), padding.PKCS1v15(), hashes.SHA256()))
print(jwt)
