# Repository structure

Map of the repo and what each part does. See [README.md](README.md) for how to
run it and [decision.md](decision.md) for why it is shaped this way.

Every folder with environment-specific settings has a committed `.env.example`;
the real `.env` and all private keys are git-ignored (README, "Environment files").

```
Mosip-uin-deletion/
├── deletion-service/           The deletion service (port 8096)
│   ├── .env.example            Settings template (QA): DBs, MinIO, eSignet, keys
│   ├── Dockerfile, .dockerignore   Image; settings and keys supplied at run time
│   ├── run.ps1 / run-cli.ps1   REST API / interactive CLI launchers
│   ├── src/main/resources/
│   │   ├── application.yml          every value is ${ENV_VAR:local default}
│   │   ├── esignet-rp-private-key.pem   eSignet client key (git-ignored)
│   │   └── gateway-signing-public.pem   verifies /api/deletion tokens (local)
│   └── src/main/java/com/mosip/deletion/
│       ├── DeletionServiceApplication.java
│       ├── api/DeleteUinController.java     /v1/delete-uin/start|status|retry (the page)
│       ├── esignet/EsignetClient.java       code -> token (private_key_jwt) -> userinfo -> UIN
│       ├── esignet/RsaSigner.java           RS256 signing for the client assertion
│       ├── txn/TransactionStore.java        in-memory jobs for status/retry
│       ├── config/CorsConfig.java           page origins allowed on /v1/delete-uin/**
│       ├── api/DeletionController.java      /api/deletion/check|execute (token API)
│       ├── security/JwtAuthFilter.java      gate on /api/deletion/**
│       ├── security/JwtVerifier.java        RS256 verify + exp/iss/aud
│       ├── service/DeletionService.java     orchestrates the modules (design §7)
│       ├── service/ContextResolver.java     resolve all keys up front
│       ├── service/DeletionContext.java
│       ├── steps/RegistrationStep.java      design §8
│       ├── steps/IdRepositoryStep.java      design §9
│       ├── steps/IdaStep.java               design §10
│       ├── steps/ResidentStep.java          design §11
│       ├── steps/EsignetIdentityStep.java   erase the eSignet login
│       ├── steps/SelfRegistrationStep.java  design §12 (off by default)
│       ├── hash/UinHashService.java         MOSIP salt-modulo hashing (§6)
│       ├── datashare/DatashareUrl.java      datashare URL parser (§15)
│       ├── store/ObjectStoreService.java    MinIO deletes
│       ├── audit/AuditRepository.java       audit table + "already deleted" (§13)
│       ├── console/ConsoleAudit.java, DeletionPlan.java   terminal audit trail
│       ├── config/Databases.java, DeletionProperties.java, MinioClientConfig.java
│       ├── model/ ModuleStatus, ModuleResult, SubStep, CheckResult, DeletionResult
│       └── cli/DeletionCli.java             interactive terminal flow
│
├── collab-ui/                  The pages (Helm charts) + local-dev harness
│   ├── landing-page/           Collab landing page with "Delete my UIN" (5500)
│   │   └── values-qa.example.yaml   QA Helm overrides template
│   ├── delete-uin/             the delete-UIN page (5501)
│   │   └── values-qa.example.yaml   QA Helm overrides template
│   └── local-dev/              render.py / serve.ps1 / values-local.json /
│                               register-client.sql / local client key (git-ignored)
│
├── esignet/docker-compose/     Local eSignet stack: esignet (8088), oidc-ui (3000),
│                               mock-identity-system (8082), postgres (5455)
│
├── docker/                     Local data plane (docker compose)
│   ├── docker-compose.yml      7 Postgres DBs (5442-5448) + MinIO (9000)
│   ├── restore-postgres.sh     Restore the MOSIP dumps into their DBs
│   ├── restore-minio.sh        Restore the MinIO bucket export
│   └── verify.sh               Row counts / bucket listing
│
├── admin/                      Read-only browser for all DBs + MinIO (port 8090)
│   ├── server.py               JSON API + static UI
│   └── static/                 index.html / app.js / styles.css
│
├── seed/                       Synthetic identities with KNOWN plaintext UINs
│   ├── derive.py               UIN -> hashes/RID/VID/token/object-keys
│   ├── seed.py                 Create N identities across all DBs + MinIO
│   ├── teardown.py             Remove them (by manifest, safe)
│   ├── verify.py               Read one/all identities back from live stores
│   ├── load_mock_identities.py Register the seeded UINs in mock eSignet (8082)
│   └── manifest.json           UIN -> every derived key, row, object
│
├── postman/                    Collections + token helpers (mint_*.py)
├── start-all.ps1 / stop-all.ps1 / esignet-logs.ps1   Bring everything up / down
├── decision.md                 Design decisions and rationale
├── structure.md                This file
└── README.md                   How to run and configure it
```

## How a request flows through the code

1. The landing page links to `collab-ui/delete-uin`, which redirects to eSignet,
   comes back with `?code`, and POSTs the code to `DeleteUinController.start`.
2. `EsignetClient.resolveUin` redeems the code at eSignet's `/token` with a
   `private_key_jwt` client assertion, calls `/userinfo`, and takes the UIN from
   the `individual_id` claim.
3. `DeletionService.executeAuthorized` checks the audit (already deleted?),
   `ContextResolver` resolves every key, the `steps/*` run in order, and
   `AuditRepository` writes the audit row.
4. The controller stores the job in `TransactionStore` and returns
   `{transactionId, status, maskedUin, retryExpiresAt}`; the page polls
   `status` and offers `retry` within the retry window.
