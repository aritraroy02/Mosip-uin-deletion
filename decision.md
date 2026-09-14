# Design decisions

Records the non-obvious choices behind the authenticated deletion flow, so the
"why" survives. Newest section last.

## 1. A separate auth-gateway fronts the deletion service

The delete-uin page is a static HTML page; it cannot do the eSignet
token-exchange, call `/userinfo`, or sign a JWT, because all of those need the
RP private key server-side. So a backend sits between the page and the deletion
service:

- **auth-gateway** (`auth-gateway/`, port **8095**) — the eSignet relying party.
  Receives the authorization code from the page, exchanges it for tokens
  (`private_key_jwt`), calls `/userinfo` to resolve the UIN, mints a 5-minute
  JWT bound to that UIN, and calls the deletion service with it.
- **deletion service** (`deletion-service/`, port **8096**) — does the actual
  cross-module deletion, and now only accepts requests carrying a valid gateway
  JWT.

The gateway takes 8095 because that is the port the page's own config
(`charts/local-dev/values-local.json` → `deleteService.startEndpoint`) already
points at; the deletion service moved from 8095 to 8096. No page change needed.

## 2. The page contract is defined by the page, and the gateway matches it

`charts/delete-uin/delete-uin-index.html` already expects an async
`start` / `status` / `retry` API and consumes
`{transactionId, status, maskedUin, retryExpiresAt}`. The gateway implements
exactly that under `/v1/delete-uin/*`, returning `COMPLETED` / `FAILED`, so the
existing page works unmodified. The page "never sees a token" — it only carries
the code and receives a masked UIN plus status.

## 3. JWT trust: RS256 with a dedicated keypair

The gateway signs the 5-minute UIN token with its own RSA private key
(`auth-gateway/.../gateway-signing-private.pem`); the deletion service verifies
it with the matching public key (`deletion-service/.../gateway-signing-public.pem`),
and additionally checks expiry, issuer (`mosip-collab-auth-gateway`) and
audience (`identity-data-deletion-service`). This keeps the two services
decoupled from eSignet's own token lifetime and gives a clean 5-minute window,
which also bounds the page's retry. (Alternatives considered: validating the
eSignet token directly via JWKS — rejected because it couples the deletion
service to eSignet and the lifetime is not a clean 5 minutes; HMAC shared secret
— rejected as less clean than asymmetric keys.)

## 4. No consent flag on the deletion service

Consent is obtained at eSignet (the resident approves the claims and enters the
OTP) before the gateway mints the token. A valid, unexpired gateway JWT is
therefore the sole authorisation to delete — the deletion service takes the UIN
from the token and deletes, with no consent parameter and no request body. All
`/api/deletion/**` endpoints require the token (`JwtAuthFilter`); missing or
invalid tokens get 401. The interactive CLI is kept for local testing and calls
the service layer directly (no HTTP, no JWT).

## 5. Mock eSignet is loaded with the seeded UINs

The real dump UINs are HSM-encrypted and unrecoverable, so no one can log in as
them. The only identities with a known plaintext UIN are the 50 seeded ones
(`seed/manifest.json`), and those are exactly the identities that have deletable
data in the docker databases. `seed/load_mock_identities.py` registers those 50
UINs in mock-identity-system (the eSignet OTP/userinfo backend, port 8082) with
deterministic demographic claims. After loading, a resident logs in with a
seeded UIN, OTP `111111`, and `/userinfo` returns that UIN — which is the one
the deletion service can act on. mock-identity-system keeps its own database
(`mosip_mockidentitysystem`), separate from the deletion databases; the UIN is
the bridge between them.

## 6. token_id is resolved by lookup, not TokenIDGenerator (unchanged)

As before: the deletion service reads `token_id` from `identity_cache` /
`credential_request_status` (keyed by the bare UIN hash) rather than
regenerating it, because the generator's key material is not available. Same
value per (identity, partner); works on real and seeded data.

## 7. Digital card and self-registration remain SKIPPED

Neither module is deployed in this Collab environment (no `mosip_digitalcard`,
no `inji_certify_tan.self_registration`), so both report SKIPPED.

## 9. Resolving the UIN from eSignet's pairwise pseudonym

The design (§5.2) assumes eSignet `/userinfo` returns the UIN in the
`individual_id` claim. It does not, in this environment. Verified by running the
full mock OIDC flow: eSignet uses `subject_types_supported: pairwise`, so
`/userinfo` returns only a per-client pseudonym in `sub`; eSignet core never
emits an `individual_id` claim (only the IdP plugin can), and the
mock-identity-system does not emit it even when the claim is requested, essential,
and consented. So the gateway receives a pseudonym like
`pNXY6NCtpK3c89jXOrsHHhql-MLOu7pzhwB9mlRKJfg`, not the UIN — and the deletion
service could not parse it as a number.

Fix: the mock's `mockidentitysystem.kyc_auth` table records
`partner_specific_user_token -> individual_id`. The gateway's `PsutResolver`
looks the pseudonym up there to recover the real UIN before minting the token.
This is a **mock-only bridge**; a production IDA plugin returns `individual_id`
in `/userinfo` directly, and the gateway already prefers a numeric subject when
one is present, so the resolver is bypassed there. The gateway therefore has a
read-only connection to the mock identity DB (localhost:5455), configured under
`app.mock-identity`.

## 8. UTC timezone required for the JVM

The machine's default zone is the legacy alias `Asia/Calcutta`, which the
PostgreSQL containers reject at connect time. The deletion service's run scripts
pass `-Duser.timezone=UTC`; any manual launch must too. The gateway does not
touch PostgreSQL, so it does not need the flag.
