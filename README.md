# MOSIP Collab — Self-Service UIN Deletion

An end-to-end environment for the *Collab Self-Service UIN and Personal Data
Deletion Process*: a resident clicks **Delete my UIN** on the Collab landing
page, authenticates with eSignet (UIN → OTP → consent), and their data is
permanently deleted across every MOSIP module and the object store. Every
deletion is audited by hashed UIN only.

> **Status, 6 October 2026: tested end to end locally (synthetic data, mock
> eSignet). Not yet run against real MOSIP or real eSignet, and not
> production-ready as is. The production blockers are handed over unresolved
> and are owned by the receiving team.**
>
> **Deploying it? Read [HANDOVER.md](HANDOVER.md) first.** It lists what must
> be resolved before production (the dependency on the mock identity system,
> confirming the `individual_id` claim, closing `/api/deletion`, turning off the
> console trail, keys, replicas), the exact database and bucket permissions,
> the eSignet client registration, and the deployment and operations steps.
> This README covers what the system is and how to run it locally.

## The flow

```
 landing page (:5500) ── "Delete my UIN" ──► delete-uin page (:5501)
                                                │  OIDC redirect
                                                ▼
 eSignet (:3000 UI, :8088 API) ── UIN + OTP + consent ──► back with ?code
                                                │
                                                ▼  page POSTs the code
 deletion-service (:8096)   /v1/delete-uin/start · status · retry
    │  1. eSignet relying party: code → token (private_key_jwt) → /userinfo
    │     → plain UIN from the individual_id claim (never sent to the browser)
    │  2. already deleted? → resolve keys → 6 modules → audit row
    ▼
 MOSIP DBs (:5442-5448) + MinIO (:9000) + eSignet / mock-identity DB (:5455)
```

The page only ever holds the one-time code and a masked UIN. Locally, eSignet
authenticates against **mock-identity-system** (:8082), loaded with the same
seeded UINs that have deletable data.

## Components

| Directory | What it is | Local port | Production? |
|---|---|---|---|
| [`deletion-service/`](deletion-service/) | The deletion service: eSignet relying party, cross-module deletion, audit | 8096 | **Yes** (container image) |
| [`collab-ui/delete-uin/`](collab-ui/delete-uin/), [`collab-ui/landing-page/`](collab-ui/landing-page/) | The two pages, as Helm charts | 5501, 5500 | **Yes** (Helm) |
| [`collab-ui/local-dev/`](collab-ui/local-dev/) | Renders and serves the pages locally; local eSignet client registration | — | No |
| [`esignet/docker-compose/`](esignet/docker-compose/) | Local eSignet stack: eSignet, oidc-ui, mock-identity-system, Postgres | 8088, 3000, 8082, 5455 | No (production uses its real eSignet) |
| [`docker/`](docker/) | Local MOSIP module DBs + deletion-audit DB + MinIO | 5442-5448, 9000 | No |
| [`admin/`](admin/) | Read-only data browser for every DB and bucket | 8090 | No (internal tool, never public) |
| [`seed/`](seed/) | Synthetic identities with **known** UINs; loader for mock eSignet | — | No (never against production) |
| [`postman/`](postman/) | eSignet + deletion-service collections and token helpers | — | No |

Design decisions are in [decision.md](decision.md); the file map is in
[structure.md](structure.md).

## Environment files

Every component that has environment-specific settings has a committed
template, `.env.example`, next to it. Copy it to `.env` in the same folder and
fill it in. **`.env` files and private keys are git-ignored and must never be
committed.**

| Template | Read by | Holds |
|---|---|---|
| [`deletion-service/.env.example`](deletion-service/.env.example) | the service itself (from its working directory), or `docker run --env-file` | DB URLs and credentials, MinIO, eSignet URLs, client id, key paths, allowed origins |
| [`docker/.env.example`](docker/.env.example) | `docker compose` in `docker/` | dump folder, Postgres and MinIO passwords |
| [`esignet/docker-compose/.env.example`](esignet/docker-compose/.env.example) | `docker compose` in `esignet/docker-compose/` | local eSignet DB password |
| [`admin/.env.example`](admin/.env.example) | `admin/server.py` | DB host/port/credentials (read-only user), MinIO |
| [`seed/.env.example`](seed/.env.example) | the `seed/` scripts | DB host/port/credentials, MinIO, mock-identity URL |
| [`postman/.env.example`](postman/.env.example) | the two token helpers | key paths, client id, token URL |
| [`collab-ui/delete-uin/values-qa.example.yaml`](collab-ui/delete-uin/values-qa.example.yaml) | `helm -f` | page URLs, eSignet client, deletion-service endpoints |
| [`collab-ui/landing-page/values-qa.example.yaml`](collab-ui/landing-page/values-qa.example.yaml) | `helm -f` | delete-UIN link, host |

**Local development needs no `.env` files.** Every setting falls back to the
local docker value.

**Keys are never committed.** Each machine generates its own throwaway
local-development keys: `start-all.ps1` runs
[`collab-ui/local-dev/local_keys.py`](collab-ui/local-dev/local_keys.py) on
every start, which creates them on the first run in the git-ignored
`collab-ui/local-dev/keys/` folder and writes the eSignet client registration
with their public key. The deletion service reads them from there by default;
nothing is built into the jar or the Docker image.

The templates are written for QA and production. The settings that matter most
outside local development (full list and reasons in
[HANDOVER.md](HANDOVER.md), sections 4 and 5):

- **Keys.** Generate new key pairs per environment (commands in HANDOVER.md
  5.4). The local-development keys are in this repository's history and must not
  be trusted anywhere else:
  - the eSignet client key: its private key goes to the deletion service
    (`ESIGNET_RP_PRIVATE_KEY`), and its public JWK is registered with that
    environment's eSignet client;
  - the `/api/deletion` token key: the public key goes to the deletion service
    (`DELETION_API_JWT_PUBLIC_KEY`), and the private key stays with whoever
    mints test tokens (`postman/.env`).
- **`CONSOLE_AUDIT_ENABLED=false`** anywhere real identities are processed. The
  console trail prints every claim eSignet returns, including the plain UIN;
  `CONSOLE_SHOW_PLAIN_UIN` alone does not prevent that.
- **Expose only `/v1/delete-uin/**`** of the deletion service publicly, never
  `/api/deletion/**`.

## Run it locally

**Prerequisites:** Windows with PowerShell, Docker Desktop, Java 21 and
Maven, and Python 3 with these packages:

```powershell
pip install -r seed\requirements.txt -r admin\requirements.txt cryptography requests
```

The data stack restores real Collab database dumps, which are **not** in this
repository; see [docker/README.md](docker/README.md).

All commands are PowerShell from the repo root.

**One command** (Docker Desktop, both stacks, the service, the pages and the
log windows, in the right order):

```powershell
mvn -f deletion-service\pom.xml -DskipTests package    # first time, or after code changes
.\start-all.ps1
.\stop-all.ps1                                          # graceful shutdown; data is kept
```

Then open **http://localhost:5500/**, click **Delete my UIN**, log in with a
seeded UIN (e.g. `6743558386`) and OTP **111111**, approve consent, and the page
shows the result.

**Step by step**, if you prefer:

1. Databases + MinIO: `cd docker; docker compose up -d` (the dumps are not in
   the repository; set `DUMPS_DIR` and see [docker/README.md](docker/README.md)).
2. Test identities: `cd seed; pip install -r requirements.txt; python seed.py`.
3. eSignet, the client registration and the test logins:
   ```powershell
   docker compose -f esignet\docker-compose\docker-compose.yml up -d
   python collab-ui\local-dev\local_keys.py      # local keys + filled-in registration SQL
   Get-Content collab-ui\local-dev\keys\register-client.sql | docker compose -f esignet\docker-compose\docker-compose.yml exec -T database psql -U postgres -d mosip_esignet
   python seed\load_mock_identities.py
   ```
4. The service: `cd deletion-service; .\run.ps1` (port 8096).
5. The pages: `cd collab-ui\local-dev; python render.py; .\serve.ps1`.
6. Optional: the data browser, `cd admin; pip install -r requirements.txt; python server.py` (port 8090).

## Testing without a browser

- **Postman:** [postman/README.md](postman/README.md) walks the eSignet flow
  and both deletion APIs.
- **Direct API:** `/api/deletion/check` and `/api/deletion/execute` take a
  signed token instead of an eSignet code. Mint one with
  `python postman\mint_jwt.py <uin>`.
- **Interactive CLI** (local only, no token): `cd deletion-service; .\run-cli.ps1`.

## Resetting test data

A deleted UIN writes a permanent audit record, so it then reports "already
deleted" and its test login is not restored. To start clean:

```powershell
cd seed
python teardown.py                    # remove remaining synthetic rows/objects
# clear the audit (psql on :5447):  TRUNCATE deletion.uin_deletion_audit;
python seed.py                        # re-create 50 identities
python load_mock_identities.py       # re-register them in mock eSignet
```

## Security notes

- The page flow is authorised by the eSignet authorization code alone: it is
  single-use, redeemed by the service with its own client key, and the UIN
  never leaves the service. CORS allows only the configured page origins.
- The direct API (`/api/deletion/**`) requires a signed, unexpired token. Its
  default verification key is the local-development one, whose private half is
  public: never expose this API, and set your own key (HANDOVER.md 4.3).
- The audit stores salted SHA-256 hashes, never the plain UIN. The console
  trail is the exception and must be off outside local development.
- Keys and `.env` files are git-ignored and kept out of the Docker image.
  Anything that was committed before (keys, the old cloud database passwords
  in early history) must be treated as public and rotated.
