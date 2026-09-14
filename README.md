# MOSIP Collab — Self-Service UIN Deletion

An end-to-end local environment for the *Collab Self-Service UIN and Personal
Data Deletion Process*: a resident authenticates with eSignet (UIN → OTP →
consent), and their data is permanently deleted across every MOSIP module and
the object store. Authentication and deletion are separate, secured backends —
the UIN travels between them only inside a short-lived signed token.

## The flow

```
 delete-uin page (static, :5501)
    │  "Delete my UIN" → OIDC redirect
    ▼
 eSignet (:3000 UI, :8088 API) ── OTP + consent ──► back to page with ?code
    │
    ▼  page POSTs the code (never a token)
 auth-gateway (:8095)                         ← eSignet relying party
    │  token-exchange (private_key_jwt) + GET /userinfo → UIN
    │  mint 5-minute RS256 JWT { uin }
    ▼  POST /api/deletion/execute  (Bearer JWT)
 deletion-service (:8096)                      ← JWT-secured, no consent flag
    │  verify JWT (sig, exp, iss, aud) → UIN
    ▼  delete across all modules, write audit, return per-module status
 7 PostgreSQL DBs (:5442-5448) + MinIO (:9000)
```

The mock eSignet resolves logins against **mock-identity-system** (:8082),
loaded with the same seeded UINs that have deletable data.

## Components

| Directory | What it is | Port |
|---|---|---|
| [`docker/`](docker/) | 7 MOSIP module DBs + a deletion-audit DB + MinIO | 5442-5448, 9000 |
| [`seed/`](seed/) | Synthetic identities with **known** plaintext UINs; loader for mock eSignet | — |
| [`admin/`](admin/) | Read-only browser for all DBs + MinIO (no SQL) | 8090 |
| [`auth-gateway/`](auth-gateway/) | eSignet RP: code → userinfo → 5-min JWT → deletion service | 8095 |
| [`deletion-service/`](deletion-service/) | JWT-secured cross-module deletion + audit | 8096 |
| [`esignet/`](esignet/) | eSignet stack (docker-compose): eSignet, oidc-ui, mock-identity-system | 8088, 3000, 8082 |
| [`charts/`](charts/) | Helm charts + local-dev harness for the landing / delete-uin pages | 5500, 5501 |

Design decisions are in [decision.md](decision.md); the file/layout map is in
[structure.md](structure.md).

## Bring it all up

All commands are PowerShell from the repo root. The `.ps1` scripts need
PowerShell; docker/python commands run in any shell.

### 1. Deletion databases + MinIO
```powershell
cd docker
docker compose up -d          # wait until all 8 containers are healthy
cd ..
```

### 2. Seed identities with known UINs
```powershell
cd seed
pip install -r requirements.txt
python seed.py                # 50 identities -> manifest.json
cd ..
```

### 3. eSignet stack + register the RP client + load residents
```powershell
docker compose -f esignet\docker-compose\docker-compose.yml up -d
# wait for the esignet container to be healthy, then:
Get-Content charts\local-dev\register-client.sql | docker compose -f esignet\docker-compose\docker-compose.yml exec -T database psql -U postgres -d mosip_esignet
python seed\load_mock_identities.py     # loads the 50 seeded UINs into mock eSignet
```

### 4. The two backends
```powershell
cd deletion-service; .\run.ps1     # :8096, JWT-secured   (leave running)
cd ..\auth-gateway;  .\run.ps1     # :8095, eSignet RP    (leave running)
```

### 5. The pages (optional, for the browser flow)
```powershell
cd charts\local-dev
python render.py                   # substitutes values-local.json into the pages
.\serve.ps1                        # serves landing :5500 and delete-uin :5501
```

Open **http://localhost:5501/**, click **Delete my UIN**, log in with a seeded
UIN (e.g. `6743558386`) and OTP **111111**, approve consent, and the page shows
the module-wise deletion status.

## Testing without a browser

The eSignet OTP step needs a browser, but the secured deletion path can be
driven directly. Mint a gateway-signed JWT and call the deletion service:

```powershell
# deletion-service must be running on :8096
$jwt = "<RS256 JWT signed with auth-gateway/.../gateway-signing-private.pem,
         claims: iss=mosip-collab-auth-gateway, aud=identity-data-deletion-service,
         uin=<seeded uin>, exp=now+300>"
curl.exe -s -X POST http://127.0.0.1:8096/api/deletion/execute `
  -H "Authorization: Bearer $jwt"
```

Or use the interactive CLI (no JWT, local only): `cd deletion-service; .\run-cli.ps1`.

## Admin browser

```powershell
cd admin; pip install -r requirements.txt; python server.py   # http://127.0.0.1:8090
```
Search a seeded UIN's RID or `synthetic-seed` to see an identity's footprint
across every database and bucket. See [admin/README.md](admin/README.md).

## Resetting test data

Deleting a UIN removes its rows/objects and writes a permanent audit record (so
it then reports "already deleted"). To start clean:

```powershell
cd seed
python teardown.py                          # remove remaining synthetic rows/objects
# clear the audit (psql on :5447):  TRUNCATE deletion.uin_deletion_audit;
python seed.py                              # re-create 50 identities
python load_mock_identities.py             # re-register them in mock eSignet
```

## Security notes

- The deletion service accepts **only** requests bearing a valid, unexpired
  gateway JWT (RS256, 5-minute life, checked issuer/audience). No token, no
  deletion. Consent is proven upstream at eSignet.
- The plaintext UIN never reaches the browser; the page sees only a masked UIN.
- The keys under `*/src/main/resources/*.pem` and `charts/local-dev/` are
  **local-development keys**. Generate fresh keys for any real deployment.
