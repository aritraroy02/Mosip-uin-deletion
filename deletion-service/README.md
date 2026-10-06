# identity-data-deletion-service

Cross-module UIN deletion per the *Collab Self-Service UIN and Personal Data
Deletion Process*. Standalone Spring Boot app on **port 8096**. It is also the
eSignet relying party: the delete-UIN page hands it an authorization code, and
the service resolves the UIN from eSignet itself, so the UIN never reaches the
browser. Consent is given at eSignet; there is no consent flag here.

## Run

```powershell
./run.ps1            # REST API on http://127.0.0.1:8096  (add -Build to rebuild)
./run-cli.ps1        # interactive terminal flow (local testing, no token)
```

Both pass `-Duser.timezone=UTC`, which is required — the machine's default
`Asia/Calcutta` is rejected by the Postgres containers.

## Configuration

Every setting in `src/main/resources/application.yml` is
`${ENV_VARIABLE:local default}`. Locally nothing needs setting. For any other
environment copy [`.env.example`](.env.example) to `.env` in this folder (the
service reads it on start) or pass it to Docker with `--env-file`. `.env` and
private keys are git-ignored and excluded from the image; mount the keys and
point `ESIGNET_RP_PRIVATE_KEY` / `DELETION_API_JWT_PUBLIC_KEY` at them. See the
[Dockerfile](Dockerfile) header for the `docker run` line.

## API

**Page API** — what the delete-UIN page calls (CORS limited to `ALLOWED_ORIGINS`):

```
POST /v1/delete-uin/start    {code, state, redirectUri, codeVerifier}
GET  /v1/delete-uin/status   ?transactionId=...
POST /v1/delete-uin/retry    {transactionId}
```

`start` redeems the code at eSignet (401 if it is refused; nothing is deleted),
runs the deletion and returns `{transactionId, status, maskedUin,
retryExpiresAt}`. `retry` re-runs within `RETRY_WINDOW_SECONDS` (410 after).

**Direct API** — for the CLI-style and Postman path. Requires
`Authorization: Bearer <RS256 token>` verified against
`DELETION_API_JWT_PUBLIC_KEY`; the UIN comes from the token, never the body.
Missing/invalid/expired token → 401.

```
POST /api/deletion/check     -> availability for the token's UIN
POST /api/deletion/execute   -> delete the token's UIN, return per-module status
```

Mint a local test token with `python ../postman/mint_jwt.py <uin>`.

Both APIs are idempotent: already deleted → overall `DELETED` with no modules
run; no data → `NOT_FOUND`.

## What gets deleted

| Module | Targets | §  |
|---|---|---|
| Registration | packet-manager + landing-zone objects by RID; ABIS datashare object; print-service credential; 11 `regprc` tables in foreign-key order | 8 |
| ID Repository | credential issuance + object; biometric and document objects, then `uin_biometric(_h)`, `uin_document(_h)` by `uin_ref_id`; `uin_auth_lock`, `identity_update_count_tracker`, `handle`, `vid`, `uin`, `uin_h` by hash | 9 |
| ID Authentication | `identity_cache`, `ident_binding_cert_store`, `ida.uin_auth_lock` by token_id | 10 |
| Resident Portal | `resident_transaction` (VID_CARD_DOWNLOAD) → datashare object + credential | 11 |
| eSignet + Mock identity | consent, key bindings, `kyc_auth`, `verified_claim`, `mock_identity`: the login is erased | — |
| Self-Registration | `self_registration` by plain UIN; off unless `SELF_REGISTRATION_ENABLED` | 12 |

## Audit

The audit table (`mosip_deletion_audit`, `deletion.uin_deletion_audit`, created
on startup) stores only the **hashed** UIN, per-module status, and time; it
drives the "already deleted" check. The terminal also prints a readable trail
of each request (`CONSOLE_AUDIT_ENABLED`); `CONSOLE_SHOW_PLAIN_UIN` must be
`false` wherever real identities are processed. Design choices are in
[../decision.md](../decision.md).
