# identity-data-deletion-service

Cross-module UIN deletion per the *Collab Self-Service UIN and Personal Data
Deletion Process*. Standalone Spring Boot app on **port 8096**, behind the
[auth-gateway](../auth-gateway). It accepts **only** requests carrying a valid
JWT minted by the gateway after a successful eSignet authentication — a valid,
unexpired token is the sole authorisation to delete; there is no consent flag
(consent was given at eSignet).

## Run

```powershell
./run.ps1            # REST API on http://127.0.0.1:8096  (add -Build to rebuild)
./run-cli.ps1        # interactive terminal flow (local testing, no JWT)
```

Both pass `-Duser.timezone=UTC`, which is required — the machine's default
`Asia/Calcutta` is rejected by the Postgres containers.

## API (JWT-secured)

All `/api/deletion/**` endpoints require `Authorization: Bearer <gateway JWT>`.
The UIN comes from the token, never the request body. Missing/invalid/expired
token → 401.

```
POST /api/deletion/check     -> availability for the token's UIN
POST /api/deletion/execute   -> delete the token's UIN, return per-module status
```

`execute` returns overall status plus each module (Registration, ID Repository,
ID Authentication, Resident Portal, Self-Registration) with sub-steps and
counts. It is idempotent: already-deleted → overall `DELETED` with no modules;
no data → `NOT_FOUND`.

In normal operation the gateway calls this; you don't call it directly. To test
it in isolation, mint an RS256 JWT with
`../auth-gateway/src/main/resources/gateway-signing-private.pem`
(claims `iss=mosip-collab-auth-gateway`, `aud=identity-data-deletion-service`,
`uin=<seeded uin>`, `exp=now+300`) and send it as the Bearer token.

## What gets deleted

| Module | Targets | §  |
|---|---|---|
| Registration | packet-manager + landing-zone objects by RID; ABIS datashare object; print-service credential; `registration`, `registration_list`, `registration_transaction`, `individual_demographic_dedup`, `reg_bio_ref`, `abis_request` | 8 |
| ID Repository | credential issuance + object; `uin_biometric(_h)`, `uin_document(_h)` by `uin_ref_id`; `handle`, `vid`, `uin`, `uin_h`, `uin_auth_lock`, `identity_update_count_tracker` by hash | 9 |
| ID Authentication | `identity_cache`, `ident_binding_cert_store`, `ida.uin_auth_lock` by token_id | 10 |
| Resident Portal | `resident_transaction` (VID_CARD_DOWNLOAD) → datashare object + credential | 11 |
| Self-Registration | SKIPPED (not deployed here) | 12 |

## Audit and deviations

The audit table (`mosip_deletion_audit`, `deletion.uin_deletion_audit`, created
on startup) stores only the **hashed** UIN, per-module status, and time; it
drives the "already deleted" check. Deviations from the PDF (no eSignet in this
service, token_id by lookup, digital-card/self-registration skipped) and the
security model are documented in [../decision.md](../decision.md). Config is in
`src/main/resources/application.yml` (7 datasources, MinIO, JWT public key).
