# Handover: MOSIP Collab Self-Service UIN Deletion

**For:** the team taking this repository to production.
**State:** `main` on 6 October 2026. Tested end to end in the local
environment only (synthetic data, mock eSignet). **Not yet run against real
MOSIP, real eSignet, or production data.**

Read this file top to bottom before deploying. [Section 4](#4-must-resolve-before-production)
lists what must be resolved first; nothing in it is optional.

> **Decision at handover: the production blockers in section 4 are handed over
> unresolved.** None of them has been fixed or started in code. Resolving them,
> or formally accepting a documented workaround, is the receiving team's
> responsibility before go-live. Each item says what is wrong, why it matters
> and what to do.

| | |
|---|---|
| Handed over by | [name, email] |
| Product owner | [name, email] |
| Receiving team contact | [name, email] |

---

## Contents

1. [What this is](#1-what-this-is)
2. [What you are receiving](#2-what-you-are-receiving)
3. [How it works](#3-how-it-works)
4. [Must resolve before production](#4-must-resolve-before-production)
5. [Production prerequisites](#5-production-prerequisites)
6. [Deployment steps](#6-deployment-steps)
7. [Operations](#7-operations)
8. [Known gaps against the requirements](#8-known-gaps-against-the-requirements)
9. [What was tested, and what was not](#9-what-was-tested-and-what-was-not)
10. [History you may run into](#10-history-you-may-run-into)

---

## 1. What this is

A resident who registered on MOSIP Collab can delete their identity and all
linked personal data themselves:

1. On the Collab landing page they click **Delete my UIN**.
2. They sign in at eSignet with their UIN and an OTP.
3. They consent to share their UIN with the deletion service.
4. A status page shows the deletion running, then the result, with a retry
   option if something fails.

The deletion covers six MOSIP modules (Registration Processor, ID Repository,
ID Authentication, Resident Portal, eSignet/identity login, and optionally
Self-Registration) across their databases and the object store. Every deletion
is recorded in an audit table, keyed by a salted hash of the UIN, never the UIN
itself.

## 2. What you are receiving

Only three things are deployed to production. Everything else is local
development and test tooling.

| Path | What it is | Goes to production? |
|---|---|---|
| `deletion-service/` | Spring Boot 3.3 service (Java 21), port 8096. The only backend. Ships as a container image (`Dockerfile`). | **Yes** (container) |
| `collab-ui/delete-uin/` | Helm chart: the delete-UIN status page (static HTML). | **Yes** (Helm) |
| `collab-ui/landing-page/` | Helm chart: the Collab landing page carrying the "Delete my UIN" button. | **Yes** (Helm) |
| `collab-ui/local-dev/` | Renders and serves the two pages locally; local eSignet client registration SQL. | No |
| `esignet/docker-compose/` | Local eSignet + **mock** identity system. Production uses the real eSignet. | No |
| `docker/` | Local copies of the MOSIP databases + MinIO, restored from dumps (not in the repo). | No |
| `seed/` | Creates and removes synthetic test residents with known UINs. | No (never against production) |
| `admin/` | Read-only database/bucket browser (port 8090). | No. Internal use only, never public. |
| `postman/` | Postman collections + token helpers. | No |
| `start-all.ps1`, `stop-all.ps1`, `esignet-logs.ps1` | Start/stop the local environment on Windows. | No |
| `README.md`, `decision.md`, `structure.md` | How to run it, why it is built this way, file map. | — |

**Not provided:** Kubernetes manifests or a Helm chart for the deletion
service, and a CI/CD pipeline. You need to create those (section 6).

## 3. How it works

```
 landing page ── "Delete my UIN" ──► delete-uin page
                                         │ 1. redirect to eSignet /authorize
                                         ▼
                       eSignet: UIN + OTP + consent ──► back to the page with ?code
                                         │ 2. POST {code} to the deletion service
                                         ▼
 deletion-service  POST /v1/delete-uin/start
   3. POST eSignet /oauth/v2/token   (code + client assertion signed with the
                                      client's private key: private_key_jwt)
   4. GET  eSignet /oidc/userinfo    → claim individual_id = the plain UIN
   5. already deleted? (audit table) → resolve every linked key (hashes, VIDs,
      handles, RIDs, token ids) → delete in 6 modules → write one audit row
   6. answer the page: {transactionId, status, maskedUin, retryExpiresAt}
```

Key properties:

- **The page never holds the UIN or a token.** It holds the one-time
  authorization code and, afterwards, a masked UIN (`UIN •••• •••• 1234`).
- **The authorization code is the authorisation.** It is single-use; reusing
  one returns 401. No other credential is involved in the page flow.
- **Deletion runs synchronously inside `POST /start`.** The page shows a
  progress screen while that request is open, then polls `/status`.
- **Retry:** if any module fails, the page offers a retry for 5 minutes
  (`RETRY_WINDOW_SECONDS`). The service keeps the UIN in memory for that window,
  so no second login is needed. After it, `/retry` returns 410 and the page
  sends the resident back to the landing page. A retry cannot always reach
  everything the first attempt missed: read section 7.3.
- **Already deleted:** if an earlier deletion of this UIN finished fully
  (audit row with `DELETED`), the service returns without touching any table.
  If no data is found at all, it returns `NOT_FOUND`. The page shows both as
  completed (see section 8, AC4).
- **No rollback.** Nine databases commit independently. Instead every delete
  targets specific keys, so running it again removes nothing extra, and a
  failing step is recorded while the remaining steps still run.

The service also exposes a second API, **`/api/deletion/check` and
`/api/deletion/execute`**, authorised by an RS256 token instead of an eSignet
code. It exists for testing (Postman, CLI). The page does not use it. See
section 4, item 3.

Details: [decision.md](decision.md) (why), [structure.md](structure.md) (where),
[deletion-service/README.md](deletion-service/README.md) (API and module table).

## 4. Must resolve before production

Each item below blocks go-live. They are listed in the order they will hit you.
All of them are **open at handover**, and all are owned by the receiving team.

| # | Blocker | Status at handover | Way forward |
|---|---|---|---|
| 4.1 | Service needs the mock identity database to start; module 5 is mock-only | Open, not started | Code fix, or the tested no-code workaround in 4.1 |
| 4.2 | `individual_id` from production eSignet must be the UIN (VID sign-in risk) | Open, not verified | Test against production eSignet; code fix if it can be a VID |
| 4.3 | `/api/deletion/**` trusts a publicly known key by default | Open | Configuration: block at ingress and set your own key |
| 4.4 | Console trail writes personal data, including the UIN, to logs | Open | Configuration: `CONSOLE_AUDIT_ENABLED=false` |
| 4.5 | No production keys or eSignet client yet | Open | Generate and register (5.3, 5.4) |
| 4.6 | Single replica only; synchronous request; no eviction | Open | Configuration now; shared store later to scale |
| 4.7 | Chart defaults break the flow | Open | Use the provided value templates |
| 4.8 | Retry after a partial failure can miss data and report completion | Open, not started | Code fix, or accept with the manual review in 7.3 |
| 4.9 | Secrets already public in git history | Open | Rotate |

Items 4.1, 4.2 and 4.8 need code changes or verification against production
systems. The others are configuration and setup you do during deployment.

### 4.1 The service depends on the mock identity system

**Problem.** Module 5 ("eSignet & Mock Identity") was built for the local mock
eSignet. It finds the resident's eSignet records through the mock identity
system's `kyc_auth` table, then deletes the mock identity itself. Production
eSignet runs on MOSIP ID Authentication, not the mock, so:

- The service opens a connection pool to **every** configured database at
  startup, including `mockidentity`. If that database is unreachable, **the
  service does not start.**
- Even if it starts, module 5 cannot find the production eSignet records, and
  fails on every deletion. That makes every deletion `PARTIAL`, and the page
  shows "Something went wrong" each time.

**Effect on the requirement.** The resident's ability to sign in is still
removed in production: modules 2 and 3 delete the identity from ID Repository
and ID Authentication, which eSignet authenticates against. What module 5 would
additionally remove in production, eSignet's own consent and key-binding rows,
is keyed by a per-client pseudonym rather than the UIN, and the service has no
way to find those rows in production today.

**What to do.**

- **Proper fix (code change, recommended):** make the `mockidentity`
  datasource optional (create the pool only when configured) and either remove
  module 5 for production or give it a production way to find the resident's
  eSignet pseudonym. Then decide, with your data-protection owner, whether the
  remaining eSignet consent rows are acceptable.
- **Interim workaround (no code change, tested 6 October 2026):** set
  `ESIGNET_CLEANUP_ENABLED=false` (module 5 then reports `SKIPPED`), and point
  both `MOCKIDENTITY_DB_URL` and `ESIGNET_DB_URL` at a database the service can
  reach, for example the audit database. The pools only need to connect; with
  the module off, nothing is read from or deleted in them.

### 4.2 Confirm production eSignet returns the UIN as `individual_id`

**Problem.** The service takes the UIN from the `individual_id` claim of
`/userinfo`. Locally this works because the mock identity system is configured
to emit it. Production behaviour depends on the eSignet/IDA plugin
configuration, and **has not been verified.** Two failure modes:

- **The claim is missing.** The service then tries the mock-only fallback and
  the deletion fails with an error. Visible, but broken.
- **The claim carries a VID instead of the UIN** (for example, when a resident
  signs in with a VID). A VID is all digits, so the service treats it as a UIN,
  finds no data for it, and returns `NOT_FOUND`. **The page then reports the
  deletion as complete, although nothing was deleted.** This failure is silent.

**What to do.**

1. Register the client (section 5.3) on production eSignet and run the Postman
   eSignet collection (`postman/README.md`) with a test identity, once signing
   in with its UIN and once with its VID. Step 7 shows the decoded claims.
2. If `individual_id` holds the UIN in both cases, nothing more is needed.
3. If it can hold a VID, add a VID-to-UIN lookup before deletion (code change;
   `idmap.vid` links a VID to its UIN hash), or restrict sign-in for this client
   to UIN. Do not go live until a VID sign-in deletes the right data.

### 4.3 Do not expose `/api/deletion/**`

**Problem.** The direct API trusts tokens signed by the key in
`DELETION_API_JWT_PUBLIC_KEY`. Its default is the local-development public key,
which **is included in the container image**, and the matching private key is
public in this repository's git history. With the default, anyone who can reach
`/api/deletion/execute` can delete any UIN.

**What to do — both:**

1. Route only `/v1/delete-uin/**` from the ingress to the service. Never route
   `/api/deletion/**` publicly.
2. Set `DELETION_API_JWT_PUBLIC_KEY` to the public half of a key pair generated
   for production (section 5.4), with the private half held only by operators
   who need to run deletions without the page.

### 4.4 Turn off the terminal audit trail

**Problem.** The service prints a readable trail of every request to stdout.
It includes **every claim eSignet returns** (name, contact details and
`individual_id`, the plain UIN), whatever `CONSOLE_SHOW_PLAIN_UIN` is set to.
In production that puts personal data into your log system.

**What to do.** Set `CONSOLE_AUDIT_ENABLED=false`. The audit **database** is not
affected and is the record of every deletion. The service's normal application
log only ever contains the masked UIN.

### 4.5 New keys and a production eSignet client

The local-development keys are public (git history) and must not be used
anywhere else. Generate new ones and register a new eSignet client: sections
5.3 and 5.4.

### 4.6 Run exactly one replica, with a long enough request timeout

- **One replica.** Transactions for `/status` and `/retry` are held in memory
  (`TransactionStore`). With two replicas, a status poll or retry that lands on
  the other replica gets 404 and the page tells the resident to start again.
  Scaling out needs a shared store (for example Redis) first.
- **Restarts** lose in-flight transactions: a resident mid-retry has to sign in
  again. The deletion itself is safe to repeat.
- **Memory:** finished transactions are never evicted. Restart periodically, or
  add eviction after the retry window, before high volume.
- **Timeouts:** the whole deletion runs inside `POST /v1/delete-uin/start`.
  Locally it completes in seconds, but it has not been measured against
  production-sized data. Set the ingress/proxy timeout for that path to at least
  120 seconds, then measure in staging.

### 4.7 Use the provided Helm value templates, not the chart defaults

The charts' own `values.yaml` defaults are wrong for this flow:

- `collab-ui/delete-uin/values.yaml` requests only the `name` claim, so eSignet
  never sends `individual_id`, and **every deletion fails** with 401.
- `collab-ui/landing-page/values.yaml` serves `default-index.html`, which **has
  no "Delete my UIN" button**. The page with the button is `collab-index.html`.

Start from `values-qa.example.yaml` in each chart; both set these correctly.

### 4.8 Decide on the retry limitation

A retry after a partial failure cannot always reach the data the first attempt
missed, and can then report completion (details and the proposed fix in 7.3).
Either fix it before go-live, or accept it explicitly and put the manual
`PARTIAL` review from 7.3 into your operating procedures.

### 4.9 Rotate secrets that are already public

Treat these as compromised, whatever you deploy:

- the local-development keys (in history before commit `1e4c481`);
- two cloud PostgreSQL passwords (Aiven) in commit `259ddfe`, from an older
  version of the project. If those databases still exist, change the passwords.

## 5. Production prerequisites

### 5.1 Databases

The service connects to each MOSIP module database with its own JDBC URL
(`*_DB_URL`), and shared or per-database credentials (`DB_USERNAME` /
`DB_PASSWORD`, overridable per database). It needs exactly these privileges:

| Database (setting) | SELECT | DELETE |
|---|---|---|
| `mosip_idrepo` (`IDREPO_DB_URL`) | `uin`, `uin_h`, `uin_hash_salt`, `handle`, `credential_request_status`, `uin_biometric`, `uin_biometric_h`, `uin_document`, `uin_document_h` | `uin`, `uin_h`, `handle`, `credential_request_status`, `uin_biometric`, `uin_biometric_h`, `uin_document`, `uin_document_h`, `uin_auth_lock`, `identity_update_count_tracker` |
| `mosip_idmap` (`IDMAP_DB_URL`) | `vid` | `vid` |
| `mosip_regprc` (`REGPRC_DB_URL`) | `registration_transaction`, `reg_bio_ref`, `abis_request`, `abis_response` | `registration`, `registration_list`, `registration_transaction`, `abis_request`, `abis_response`, `abis_response_det`, `individual_demographic_dedup`, `reg_bio_ref`, `reg_demo_dedupe_list`, `reg_lost_uin_det`, `reg_manual_verification` |
| `mosip_credential` (`CREDENTIAL_DB_URL`) | `credential_transaction` | `credential_transaction` |
| `mosip_ida` (`IDA_DB_URL`) | `identity_cache` | `identity_cache`, `ident_binding_cert_store`, `uin_auth_lock` |
| `mosip_resident` (`RESIDENT_DB_URL`) | `resident_transaction` | `resident_transaction` |
| `mosip_esignet` (`ESIGNET_DB_URL`) | — | `consent_detail`, `consent_history`, `public_key_registry` (module 5 only; see 4.1) |
| self-registration (optional) | — | `inji_certify_tan.self_registration` (only if `SELF_REGISTRATION_ENABLED=true`) |

Each table lives in the schema of the same name as its module (`idrepo.uin`,
`regprc.registration`, ...).

**Audit database** (`AUDIT_DB_URL`): a new, empty PostgreSQL database, e.g.
`mosip_deletion_audit`. On every start the service runs `CREATE SCHEMA IF NOT
EXISTS deletion`, `CREATE TABLE IF NOT EXISTS deletion.uin_deletion_audit` and
an index. Either grant the service user `CREATE` on that database, or create the
objects yourself beforehand (DDL in
`deletion-service/src/main/java/com/mosip/deletion/audit/AuditRepository.java`)
and grant `SELECT, INSERT` on the table. Back the audit database up like any
other system of record; it is what proves a deletion happened.

> If the audit write fails, the deletion has still happened, and the service
> only logs `failed to write audit record`. Alert on that log line.

### 5.2 Object store (MinIO or S3-compatible)

`MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`. The service needs
**list** and **delete** on:

- `packet-manager` (`PACKET_MANAGER_BUCKET`), objects under `{rid}/`
- `landing-zone` (`LANDING_ZONE_BUCKET`), objects under `{rid}/`, when
  `LANDING_ZONE_TYPE=ObjectStore`. With `DMZServer`, the landing-zone NFS share
  must be mounted into the container at `LANDING_ZONE_NFS_PATH`.
- `idrepo` (`IDREPO_OBJECT_BUCKET`), biometric and document objects
- every datashare bucket referenced by the credential and ABIS datashare URLs
  (the bucket is the URL's policy id). Check which ones your installation uses.

### 5.3 eSignet client registration

Register one OIDC client on production eSignet (through its client-management
API or partner portal). Mirror the local registration in
`collab-ui/local-dev/register-client.sql`:

| Field | Value |
|---|---|
| Client id | your choice; set it as `ESIGNET_CLIENT_ID` (service) and `esignet.clientId` (delete-uin chart) |
| Redirect URI | the delete-uin page URL exactly, e.g. `https://delete-uin.example.org/` |
| Grant type | `authorization_code` |
| Client auth method | `private_key_jwt` |
| Public key | the JWK of the key from 5.4 |
| Claims | `individual_id` (essential). Add `name` only if you want it on the consent screen. Request nothing else: the service uses only `individual_id`. |
| ACR values | the authentication factors you allow, matching `esignet.acrValues` in the chart |
| Name / logo | shown on the eSignet sign-in screen |

### 5.4 Keys

Two RSA key pairs, generated per environment. The formats below are the ones
the code reads (PKCS#8 private key, X.509 public key); both commands were
tested.

```bash
# 1. eSignet client key: private half -> deletion service, public JWK -> eSignet
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out esignet-rp-private-key.pem

# public JWK to register with eSignet (needs: pip install cryptography)
python -c "import base64,json,sys; from cryptography.hazmat.primitives.serialization import load_pem_private_key as L; k=L(open(sys.argv[1],'rb').read(),None).public_key().public_numbers(); b=lambda n: base64.urlsafe_b64encode(n.to_bytes((n.bit_length()+7)//8,'big')).rstrip(b'=').decode(); print(json.dumps({'kty':'RSA','e':b(k.e),'use':'sig','alg':'RS256','n':b(k.n)},separators=(',',':')))" esignet-rp-private-key.pem

# 2. /api/deletion token key: public half -> deletion service, private half -> operators only
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out deletion-api-jwt-private.pem
openssl pkey -in deletion-api-jwt-private.pem -pubout -out deletion-api-jwt-public.pem
```

Store the private keys in your secret store. Mount them into the container
(for example at `/run/secrets/`) and set:

```
ESIGNET_RP_PRIVATE_KEY=file:/run/secrets/esignet-rp-private-key.pem
DELETION_API_JWT_PUBLIC_KEY=file:/run/secrets/deletion-api-jwt-public.pem
```

Never bake keys into an image: `deletion-service/.dockerignore` already keeps
private keys and `.env` files out of the build.

### 5.5 Configuration

Every setting is an environment variable; the full list, with explanations, is
[deletion-service/.env.example](deletion-service/.env.example). Defaults are the
**local development** values, so in production you must set at least:

- all nine `*_DB_URL` values and the database credentials (5.1, and 4.1 for
  `MOCKIDENTITY_DB_URL` / `ESIGNET_DB_URL`);
- `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` and the bucket names;
- `ALLOWED_ORIGINS`: the delete-uin page's origin, e.g. `https://delete-uin.example.org`
  (comma-separated if more than one, no trailing slash);
- `ESIGNET_TOKEN_URL`, `ESIGNET_USERINFO_URL`, `ESIGNET_CLIENT_ID`,
  `ESIGNET_RP_PRIVATE_KEY`;
- `DELETION_API_JWT_PUBLIC_KEY` (4.3);
- `CONSOLE_AUDIT_ENABLED=false` (4.4) and `ESIGNET_CLEANUP_ENABLED` (4.1).

Format notes: plain `KEY=VALUE`, comments only on their own line (a `#` after a
value becomes part of it), forward slashes in paths. Real environment variables
override a `.env` file.

### 5.6 Network

| From | To | Purpose |
|---|---|---|
| Resident's browser | landing page, delete-uin page | public |
| Resident's browser | deletion service `/v1/delete-uin/**` only | public, via ingress |
| Resident's browser | eSignet UI (`/authorize`) | public |
| Deletion service | eSignet API (`/oauth/v2/token`, `/oidc/userinfo`) | outbound HTTPS |
| Deletion service | the module databases, the audit database, the object store | internal |

The service listens on plain HTTP (8096); terminate TLS at the ingress.

## 6. Deployment steps

1. **Resolve section 4** (or record a decision for each item).
2. **Build the image** from the repository root and push it to your registry:
   ```bash
   docker build -t <registry>/identity-data-deletion-service:<version> deletion-service
   ```
   The image runs as a non-root user with `-Duser.timezone=UTC` (required: the
   PostgreSQL servers reject some legacy zone names).
3. **Create the audit database** and the database users and grants (5.1), and
   the object-store access (5.2).
4. **Generate the keys and register the eSignet client** (5.3, 5.4).
5. **Deploy the service** (you write the manifests):
   - 1 replica (4.6), env from 5.5, keys mounted as files;
   - container port 8096; health: TCP on 8096, or HTTP
     `GET /v1/delete-uin/status?transactionId=probe` (404 means the service is
     up; there is no actuator endpoint). The image's own Docker `HEALTHCHECK`
     posts to `/api/deletion/execute` and expects 401;
   - ingress: route only `/v1/delete-uin/**` (4.3), timeout ≥ 120 s (4.6).
6. **Deploy the pages** with Helm, from copies of the templates:
   ```bash
   helm upgrade --install delete-uin   collab-ui/delete-uin   -f values-prod-delete-uin.yaml
   helm upgrade --install landing-page collab-ui/landing-page -f values-prod-landing.yaml
   ```
   Keep the filled-in values files out of git (`values-*.yaml` is ignored).
7. **Smoke test** (no data is deleted):
   - `POST /v1/delete-uin/start` with `{}` → 400; with `{"code":"x"}` → 401;
   - CORS preflight from the page origin → allowed; from another origin → 403;
   - `/api/deletion/execute` is not reachable from outside;
   - the landing page shows "Delete my UIN" and it opens the delete-uin page,
     which redirects to eSignet.
8. **End-to-end test with a dedicated test identity** that may be deleted
   permanently: sign in, consent, confirm the page shows completion, the audit
   row (7.2) shows the expected modules, and the identity can no longer sign in.
   Repeat signing in with that identity's VID (4.2).
9. **Go live.**

## 7. Operations

### 7.1 Logs

The application log (stdout, Spring Boot format) records each request with the
masked UIN, eSignet failures (`eSignet verification failed: ...`), failed
modules (`module X failed hard: ...`) and audit write failures
(`failed to write audit record`). Alert on the last two.

### 7.2 The audit table

`deletion.uin_deletion_audit` in the audit database: one row per deletion that
ran.

| Column | Meaning |
|---|---|
| `id` | request id |
| `uin_hash_bare`, `uin_hash_prefixed` | salted SHA-256 of the UIN, as MOSIP stores it |
| `overall_status` | `DELETED`, or `PARTIAL` if any module was `PARTIAL`/`FAILED` |
| `module_status` | JSON: every module, every sub-step, rows/objects removed, errors |
| `cr_dtimes` | when it ran |

Useful queries:

```sql
-- volume per day and outcome
SELECT date(cr_dtimes), overall_status, count(*) FROM deletion.uin_deletion_audit GROUP BY 1, 2 ORDER BY 1 DESC;

-- recent partial deletions and what failed
SELECT id, cr_dtimes, module_status FROM deletion.uin_deletion_audit
WHERE overall_status = 'PARTIAL' ORDER BY cr_dtimes DESC LIMIT 20;
```

"Already deleted" and "nothing to delete" outcomes return without writing a
row. There is no reason, comment or requester data: the requirement for a
reason field was not built (section 8).

### 7.3 When a deletion is PARTIAL

The resident sees "Something went wrong" with a retry button for 5 minutes.

**How a retry works.** Only an earlier `DELETED` audit row counts as "already
deleted"; a `PARTIAL` row does not block another attempt. Every attempt (a
retry, a new sign-in, or an operator call) starts again by **looking up the
resident's keys**: registration ids, `uin_ref_id`s, VIDs and handles from the
ID Repository, and token ids from ID Authentication and the credential tables.
Then it runs all modules with whatever it found. Deleting twice removes nothing
extra.

**The limit.** Keys that existed only in rows the first attempt already deleted
cannot be found again. The important case: the registration ids (RIDs), which
module 1 deletes by, come from `idrepo.uin` / `idrepo.uin_h`, which module 2
deletes. So if module 1 failed and module 2 succeeded, a retry finds no RIDs,
and module 1's leftovers (registration rows, packets, landing-zone files) are
not retried. If nothing else remains, the retry reports `NOT_FOUND` and **the
page shows the deletion as complete although that registration data is still
there.** The audit row stores only hashes, so it does not tell you the RIDs
either.

The same applies to the Resident Portal module (4), which finds its rows by
token id: token ids come from tables that modules 2 and 3 delete.

**What to do now:** treat every `PARTIAL` audit row (query in 7.2) as needing a
manual check. `module_status` names the failed sub-steps; a retry may not have
reached them. **Proper fix (code change, recommended before go-live):** keep
the keys resolved by the first attempt (with the retry transaction, and in a
restricted table for operators) and reuse them on every retry.

**Operator re-run** (after the window, or after fixing the cause): have the
resident start again from the landing page, or call
`POST /api/deletion/execute` internally with a token for that UIN signed with
the operators' private key (4.3; `postman/mint_jwt.py` shows the token format).
The same limit applies.

## 8. Known gaps against the requirements

| Requirement | Status |
|---|---|
| AC1 "Delete My Data" option on the landing page | Done; the button reads **"Delete my UIN"**. Change the wording in `collab-index.html` if the brief's text is required. |
| AC2 Sign-in through eSignet | Done |
| AC3 Detect already-deleted UINs | Done (audit table) |
| AC4 Message "You are already deactivated your account. No further action is required." | **Partly.** Detected, but the API answers `COMPLETED` for both "deleted now" and "already deleted", so the page cannot show this message. Needs a field in the `/start` response and a page panel. |
| AC6 Delete the UIN and all linked data | Done, see the module table in `deletion-service/README.md`; production caveats in 4.1 and 4.2 |
| AC7 Status page during deletion | Done (progress screen; deletion is synchronous) |
| AC8 Retry after a failure | Done (5-minute window), **with a limit**: a retry cannot reach registration data once the ID Repository rows are gone (7.3). |
| AC9 After timeout, restart from the landing page | Done (410 → restart screen) |
| AC10 Confirmation message, exact wording | **Partly.** The page says "Deletion complete" with different text. Copy change in `delete-uin-index.html`. |
| AC11 No further actions with the UIN | Done: sign-in, credential download and contact details are removed. There is no separate notification suppression list. |
| AC12 Re-register for a new UIN | By design |
| AC12 All deletions logged | Done (audit table), with the exceptions in 7.2 |
| Reason dropdown, comments, analytics dashboard | **Not built.** The brief's Google-Form approach was replaced by eSignet sign-in; no reason is captured. |
| Asynchronous deletion | **Not built.** Runs inside the request (4.6). |
| Digital card module | Not covered (no such module in Collab). |

Code-level notes: there are **no automated tests**; a few unused settings and
fields remain (`auth-partner-id`, `digital-card-enabled`, `Txn.summary`,
`Txn.lastResult`).

## 9. What was tested, and what was not

**Tested (local environment, synthetic data, 6 October 2026):**

- Full stack from `start-all.ps1`: pages, deletion service, eSignet, mock
  identity, admin.
- A real deletion through the eSignet flow: completed, 58 rows/objects removed
  across 3 modules, no failed steps; the page received only the masked UIN; the
  same code reused → 401; the UIN can no longer sign in
  (`invalid_individual_id`).
- Error paths: missing code 400, invalid code 401 with nothing deleted, CORS
  allowed/blocked, `/api/deletion` without token 401, with token works.
- Configuration: defaults without `.env`, values from `.env`, environment
  variables overriding `.env`; the 4.1 workaround starts and serves requests.
- Key generation commands (5.4) and the container image build.

**Not tested:**

- Real MOSIP modules, real eSignet/IDA, production-sized data or timings.
- Sign-in with a VID (4.2).
- `LANDING_ZONE_TYPE=DMZServer` (NFS), and deletion of real `idrepo` bucket
  objects (the local backup had no such bucket, so that step always removed 0).
- Self-registration deletion (module off; no portal deployed).
- Running the container image against real infrastructure, Kubernetes, Helm
  installs of the two pages.

## 10. History you may run into

- **auth-gateway (removed).** An earlier design put a separate gateway service
  (port 8095) between the page and the deletion service. The deletion service
  now does that work itself; the gateway was removed from `main` in commit
  `c008400`. Old documents or branches may still mention it.
- **The old root Spring Boot app (removed).** The repository root used to hold
  an earlier registration/deletion UI (`src/`, `com.example.mosip`). It was
  incomplete after a merge, unused by this flow, and removed in `c008400`. The
  last complete version is commit `dd084d9`.
- **`charts/` was renamed to `collab-ui/`** in commit `1e4c481`.
- **Local-development keys** were committed before `1e4c481` and are now
  git-ignored; the README explains how a fresh local clone restores them.
