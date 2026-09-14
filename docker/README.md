# MOSIP Collab data environment (Docker)

Local restore of the Collab environment dumps, for building and exercising the
UIN data-deletion service against real schemas and real data.

## Layout

One PostgreSQL container per MOSIP database, plus MinIO for the object store.
Host ports avoid `5432` and `9001`, which are already in use on this machine.

| Container | Database | Host port | Module role |
|---|---|---|---|
| `mosip-pg-idmap` | `mosip_idmap` | 5442 | `idmapuser` |
| `mosip-pg-idrepo` | `mosip_idrepo` | 5448 | `idrepouser` |
| `mosip-pg-regprc` | `mosip_regprc` | 5443 | `regprcuser` |
| `mosip-pg-resident` | `mosip_resident` | 5444 | `residentuser` |
| `mosip-pg-credential` | `mosip_credential` | 5445 | `credentialuser` |
| `mosip-pg-ida` | `mosip_ida` | 5446 | `idauser` |
| `mosip-pg-audit` | `mosip_deletion_audit` | 5447 | — (empty; design doc §13) |
| `mosip-minio` | S3 API 9000, console 9011 | | |

Superuser is `postgres` / `postgres`; MinIO is `minioadmin` / `minioadmin`.
Each module role is created with its own name as the password.

## Usage

```bash
docker compose up -d
./restore-postgres.sh    # replays the five SQL dumps
./restore-minio.sh       # extracts and mirrors the bucket export
./verify.sh              # row counts per table + bucket listing
```

The dumps are **plain-text `pg_dump` output**, not custom format, so they are
replayed with `psql -f`; `pg_restore` cannot read them. They open with a
`\restrict` token, which requires psql **≥ 15.14** — the `postgres:15` image is
currently 15.19, so this is satisfied. Every dump carries its own
`DROP ... IF EXISTS` / `CREATE SCHEMA` header, so the restores are idempotent.

The MinIO backup is an object-level export with no `.minio.sys`, so it cannot be
dropped into MinIO's data directory — a modern MinIO backend writes an `xl.meta`
per object. `restore-minio.sh` therefore uploads the objects with `mc mirror`.

## Tuning caveat

All containers run with `synchronous_commit=off` and a large `max_wal_size` to
speed up the bulk load — the IDA dump alone is 36.8 GB. This is a disposable
analysis copy that can be rebuilt from the dumps, so the reduced crash
durability is an acceptable trade. Remove those flags from `docker-compose.yml`
if this environment is ever used for anything that must survive a hard kill.

## Known data gaps

`mosip_idrepo` was supplied later and **is now loaded** (17 tables, 418 MB dump,
restored in 1m51s with zero errors). `idrepo.uin` carries both `uin_hash` and
`reg_id`, which is the UIN → RID link §8.1 depends on; 10,994 of its 14,802
RIDs resolve into `regprc.registration`, so the Registration and ID Repository
modules both have their entry point.

**Digital card (§8.7) does not apply here.** There is no digital-card module in
this Collab environment, so there is no `mosip_digitalcard` database to load —
consistent with the object store, where `mpolicy-default-digitalcard` holds 0
objects. The deletion service should report that sub-step as `SKIPPED` rather
than treating its absence as a failure.

`inji_certify_tan.self_registration` (§12) is likewise absent, but is
feature-flagged optional in the design.

Every other module named in the design is present and loaded.

### UIN hash forms differ per table — verify before implementing

Design §6 warns that some stores use `{saltId}_{hashWithSalt}` and others the
bare hash. That is confirmed here:

| Form | Tables |
|---|---|
| `{saltId}_{hash}` | `idrepo.uin`, `idrepo.uin_h`, `idrepo.handle`, `idmap.vid`, `idrepo.identity_update_count_tracker` (in column `id`, not `uin_hash`) |
| bare hash | `idrepo.uin_auth_lock`, `idrepo.credential_request_status`, `ida.identity_cache` |

More importantly, the hash *values* do not line up across modules even though
the `uin_hash_salt` tables are byte-identical in all three (1,000 of 1,000
rows match) and the RID populations overlap 74%. `idrepo.uin` hashes intersect
`idmap.vid` at **0**, `ida.identity_cache` at **15**, and
`credential_request_status` at **21**. So this is neither a salt mismatch nor a
stale snapshot: each module appears to derive its hash from a different input.
The deletion service must therefore compute the correct hash form per target
table rather than resolving one hash and reusing it everywhere — confirm each
derivation against the module source before relying on it.

Also note `ida.ident_binding_cert_store` and `ida.cred_subject_id_store` restore
**empty**, so §10.2's binding-certificate deletion has nothing to match here.
