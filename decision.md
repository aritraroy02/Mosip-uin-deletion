# Design decisions

Records the non-obvious choices behind the authenticated deletion flow, so the
"why" survives. Newest section last.

## 1. The deletion service is the eSignet relying party

The delete-uin page is a static HTML page; it cannot do the eSignet token
exchange or call `/userinfo`, because both need the client's private key
server-side. The deletion service (`deletion-service/`, port **8096**) does it
itself: it receives the authorization code from the page, redeems it at
eSignet's `/token` with a `private_key_jwt` client assertion, calls `/userinfo`,
reads the UIN, and runs the deletion in the same process.

An earlier version put a separate auth-gateway (port 8095) in front, which did
the eSignet half and then minted a 5-minute JWT carrying the UIN for the
deletion service. It was removed: it added a second service, a second key pair
and a token passed between two of our own processes, without adding any
protection the authorization code does not already give.

## 2. The page contract is defined by the page

`collab-ui/delete-uin/delete-uin-index.html` expects an async `start` / `status`
/ `retry` API and consumes `{transactionId, status, maskedUin, retryExpiresAt}`.
`DeleteUinController` implements exactly that under `/v1/delete-uin/*`,
returning `COMPLETED` / `FAILED`. The page never sees a token or the UIN: it
carries only the one-time code and receives a masked UIN plus status. A retry
re-runs the deletion with the UIN held in memory for a bounded window
(`RETRY_WINDOW_SECONDS`, default 300); after it, the resident starts again.

## 3. The direct API keeps a signed token

`/api/deletion/check` and `/api/deletion/execute` remain for the CLI-style and
Postman testing path. They take no code; instead they require an RS256 token
verified against a configured public key, with expiry, issuer and audience
checks (`JwtAuthFilter`, `JwtVerifier`). The UIN comes from the token, never the
body. The page flow does not use this API.

## 4. No consent flag on the deletion service

Consent is obtained at eSignet (the resident enters the OTP and approves the
claims) before an authorization code exists. A valid code — or, on the direct
API, a valid token — is therefore the sole authorisation to delete; there is no
consent parameter. The interactive CLI is kept for local testing and calls the
service layer directly.

## 5. Mock eSignet is loaded with the seeded UINs

The real dump UINs are HSM-encrypted and unrecoverable, so no one can log in as
them. The only identities with a known plaintext UIN are the 50 seeded ones
(`seed/manifest.json`), and those are exactly the identities that have deletable
data in the docker databases. `seed/load_mock_identities.py` registers those
UINs in mock-identity-system (port 8082) with deterministic demographic claims,
and skips any UIN that already has a deletion on record, so a restart never
gives a deleted resident their login back.

## 6. token_id is resolved by lookup, not TokenIDGenerator

The deletion service reads `token_id` from `identity_cache` /
`credential_request_status` (keyed by the bare UIN hash) rather than
regenerating it, because the generator's key material is not available. Same
value per (identity, partner); works on real and seeded data.

## 7. Digital card is not covered; self-registration is optional

There is no `mosip_digitalcard` in this environment. Self-registration deletion
is implemented but switched off (`SELF_REGISTRATION_ENABLED=false`) because the
portal is not deployed here; enabled without its datasource, it reports a
FAILED sub-step rather than a silent success.

## 8. UTC timezone required for the JVM

The development machine's default zone is the legacy alias `Asia/Calcutta`,
which the PostgreSQL containers reject at connect time. The run scripts and the
Docker image pass `-Duser.timezone=UTC`; any manual launch must too.

## 9. The UIN arrives as the individual_id claim

eSignet uses pairwise subjects, so `sub` in `/userinfo` is a per-client
pseudonym, never the UIN. The UIN is delivered as a separate, consented claim:
the identity system maps `individualId` to `individual_id`
(`MOSIP_MOCK_IDA_IDENTITY_OPENID_CLAIMS_MAPPING` in `esignet/docker-compose`),
the client is allowed that claim (`register-client.sql`), and the page requests
it as essential. All four must line up; the QA Helm values template sets the
page's part, because the chart default asks only for `name`.

If only a pseudonym comes back, `EsignetClient` falls back to the mock's
`kyc_auth` table (`partner_specific_user_token -> individual_id`). That bridge
exists only with the mock identity system; with a production identity plugin
the claim is present and the fallback is never used.

## 10. Configuration through environment variables with local defaults

Every environment-specific value is `${VARIABLE:local default}` (Spring) or
`${VARIABLE:-default}` (docker compose), or read from the environment by the
Python tools. Each component has a committed `.env.example` and reads a
git-ignored `.env` beside it. The defaults are the local docker values, so
local development needs no configuration, while QA supplies everything —
including its own key pairs — without code changes. Private keys and `.env`
files are never committed and are kept out of the Docker image.

## 11. Production blockers are handed over unresolved

At handover to the production team (6 October 2026), the blockers found while
preparing it are handed over as they stand rather than fixed first: the
dependency on the mock identity system, the unverified `individual_id` claim,
the default key on `/api/deletion`, personal data in the console trail, the
retry limitation, and the deployment prerequisites. Each is described, with
its fix or workaround, in [HANDOVER.md](HANDOVER.md) section 4, and resolving
them is the receiving team's responsibility before go-live. No code was
changed to address them, so the behaviour described there is the behaviour of
the handed-over code.
