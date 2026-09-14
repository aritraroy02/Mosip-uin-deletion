# Repository structure

Map of the repo and what each part does. See [README.md](README.md) for how to
run it and [decision.md](decision.md) for why it is shaped this way.

```
Mosip-uin-deletion/
├── docker/                     Deletion-side data plane (docker compose)
│   ├── docker-compose.yml      7 Postgres DBs (5442-5448) + MinIO (9000)
│   ├── restore-postgres.sh     Restore the MOSIP dumps into their DBs
│   ├── restore-minio.sh        Restore the MinIO bucket export
│   └── verify.sh               Row counts / bucket listing
│
├── seed/                       Synthetic identities with KNOWN plaintext UINs
│   ├── derive.py               UIN -> hashes/RID/VID/token/object-keys
│   ├── seed.py                 Create N identities across all DBs + MinIO
│   ├── teardown.py             Remove them (by manifest, safe)
│   ├── verify.py               Read one/all identities back from live stores
│   ├── load_mock_identities.py Register the seeded UINs in mock eSignet (8082)
│   └── manifest.json           UIN -> every derived key, row, object
│
├── admin/                      Read-only browser for all DBs + MinIO (no SQL)
│   ├── server.py               JSON API + static UI, port 8090
│   └── static/                 index.html / app.js / styles.css
│
├── auth-gateway/               eSignet relying-party backend (port 8095)
│   ├── src/main/resources/
│   │   ├── application.yml          eSignet URLs, keys, deletion-service URL
│   │   ├── esignet-rp-private-key.pem   RP key (matches registered client)
│   │   └── gateway-signing-private.pem  signs the 5-min UIN JWT
│   └── src/main/java/com/mosip/gateway/
│       ├── AuthGatewayApplication.java
│       ├── api/DeleteUinController.java   /v1/delete-uin/start|status|retry
│       ├── esignet/EsignetClient.java     token-exchange + /userinfo -> UIN
│       ├── jwt/RsaSigner.java             RS256 sign + JWT claim decode
│       ├── jwt/GatewayTokenService.java   mint 5-min JWT for the UIN
│       ├── deletion/DeletionClient.java   call deletion-service with the JWT
│       ├── txn/TransactionStore.java      in-memory jobs (holds JWT, not UIN)
│       └── config/GatewayProperties.java, CorsConfig.java
│
├── deletion-service/           JWT-secured cross-module deletion (port 8096)
│   ├── src/main/resources/
│   │   ├── application.yml          7 datasources, MinIO, JWT public key
│   │   └── gateway-signing-public.pem   verifies the gateway JWT
│   ├── run.ps1 / run-cli.ps1       REST API / interactive CLI launchers
│   ├── postman_collection.json     ready-to-import requests
│   └── src/main/java/com/mosip/deletion/
│       ├── DeletionServiceApplication.java
│       ├── security/JwtAuthFilter.java     gate on /api/deletion/**
│       ├── security/JwtVerifier.java       RS256 verify + exp/iss/aud
│       ├── api/DeletionController.java      /check, /execute (UIN from JWT)
│       ├── service/DeletionService.java     orchestrates the modules (design §7)
│       ├── service/ContextResolver.java     resolve all keys up front
│       ├── service/DeletionContext.java
│       ├── steps/RegistrationStep.java      design §8
│       ├── steps/IdRepositoryStep.java      design §9
│       ├── steps/IdaStep.java               design §10
│       ├── steps/ResidentStep.java          design §11
│       ├── steps/SelfRegistrationStep.java  design §12 (SKIPPED)
│       ├── hash/UinHashService.java         MOSIP salt-modulo hashing (§6)
│       ├── datashare/DatashareUrl.java      datashare URL parser (§15)
│       ├── store/ObjectStoreService.java    MinIO deletes
│       ├── audit/AuditRepository.java       audit table + "already deleted" (§13)
│       ├── config/Databases.java, DeletionProperties.java, MinioClientConfig.java
│       ├── model/ ModuleStatus, ModuleResult, SubStep, CheckResult, DeletionResult
│       └── cli/DeletionCli.java             interactive terminal flow
│
├── esignet/                    eSignet stack (upstream) + docker-compose
│   └── docker-compose/         esignet (8088), oidc-ui (3000),
│                               mock-identity-system (8082), postgres (5455)
│
├── charts/                     Helm charts + local-dev harness
│   ├── delete-uin/             static "Delete my UIN" page (served on 5501)
│   ├── landing-page/           static landing page (5500)
│   └── local-dev/              render.py / serve.ps1 / values-local.json /
│                               register-client.sql / RP private key
│
├── decision.md                 Design decisions and rationale
├── structure.md                This file
└── README.md                   How to run the whole thing
```

## How a request flows through the code

1. `charts/delete-uin` redirects to eSignet, returns with `?code`, and POSTs the
   code to `auth-gateway` `DeleteUinController.start`.
2. `EsignetClient.resolveUin` exchanges the code and calls `/userinfo` → UIN.
3. `GatewayTokenService.mintForUin` signs a 5-minute JWT;
   `DeletionClient.execute` sends it to the deletion service.
4. `JwtAuthFilter` + `JwtVerifier` validate the token and expose the UIN;
   `DeletionController.execute` → `DeletionService.executeAuthorized` runs the
   `steps/*` in order, writes the audit row, and returns the module-wise status.
5. The gateway stores the job and returns `{transactionId, status, maskedUin,
   retryExpiresAt}`; the page polls `status` and shows the result.
```
