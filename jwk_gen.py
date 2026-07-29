"""Build the RP public JWK JSON from the RSA modulus hex emitted by openssl."""
import base64
import json


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


with open("mod.hex") as fh:
    modulus = bytes.fromhex(fh.read().strip())

jwk = {
    "kty": "RSA",
    "e": b64url((65537).to_bytes(3, "big")),
    "use": "sig",
    "alg": "RS256",
    "n": b64url(modulus),
}

print(json.dumps(jwk, separators=(",", ":")))
