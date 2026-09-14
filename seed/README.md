# Synthetic identity seed

The real dumps contain **no plaintext UIN** — every UIN column is HSM-encrypted,
and the key that unwraps it is not in the export. So the deletion service can't
be run end to end against the real data: you can never feed it a UIN you know.

This seed fixes that. It writes N synthetic identities whose plaintext UIN *you*
choose, each with a full cross-module footprint wired together with the same
keys and derivations the real data uses, so the deletion service can be driven
from the UIN alone and its results checked against a manifest.

## Running

The docker stack (`../docker`) must be up.

```bash
pip install -r requirements.txt
python seed.py                       # 50 identities (reproducible set)
python seed.py --count 10            # fewer
python seed.py --uins 3512806452,7788990011   # specific UINs
python teardown.py                   # remove them all
python teardown.py --verify          # report what (if anything) remains
```

`manifest.json` is written next to the scripts. It maps every UIN to all of its
derived keys and to every row and object created — it is both the **test
oracle** (what the deletion service should find and remove) and the **teardown
source**.

## What each identity gets

A complete footprint across all six databases and MinIO — every table and
bucket the design's deletion flow touches:

| Module | Rows created |
|---|---|
| ID Repository | `uin`, `uin_h`, `uin_biometric`, 2×`uin_document`, `credential_request_status`, `uin_auth_lock`, `identity_update_count_tracker`, `handle` (every 3rd identity) |
| ID Map | `vid` |
| Reg Processor | `registration`, `registration_list`, `individual_demographic_dedup`, `reg_bio_ref`, `abis_request`, 2×`registration_transaction` (incl. `PRINT_SERVICE`) |
| Credential | 3×`credential_transaction` (issuance, print, VID-card) |
| IDA | `identity_cache`, `uin_auth_lock` |
| Resident | `resident_transaction` (`VID_CARD_DOWNLOAD`) |
| MinIO | 5×`packet-manager`, `landing-zone`, `mpolicy-default-auth` (issuance + print datashare), `mpolicy-default-abis`, `mpolicy-default-resident` |

Everything is cross-linked the way the flow expects: the ABIS request's
`req_text` JSON `referenceURL` points at its `mpolicy-default-abis` object; the
`PRINT_SERVICE` transaction's `ref_id` matches a `credential_transaction.id`
whose `datashareurl` points at an auth object; the resident row's
`credential_request_id` matches the VID-card credential and its `reference_link`
points at a resident object.

## How the keys are derived

All in `derive.py`, all deterministic from the UIN:

- **`uin_hash`** = `SHA256(uin + salt).hexdigest().upper()`, `salt` =
  `uin_hash_salt[int(uin) % 1000]` read from the real table. Stored **bare** in
  `identity_cache`, `credential_request_status`, `uin_auth_lock`; **`{saltId}_`
  prefixed** in `idrepo.uin`, `uin_h`, `idmap.vid`, `handle`,
  `identity_update_count_tracker`. This is the algorithm confirmed by matching
  plaintext VIDs against the real tables, so the service's hashing is genuinely
  exercised.
- **RID** = `99001` + `10000` + `seq(5)` + `timestamp(14)` — 29 digits, center
  `99001` so it never collides with a real RID (all of which start `10xxx`).
- **VID** = 16 digits, prefixed `99`.
- **token_id** = 36-digit numeric from `SHA256(uin|partner)`, identical across
  `identity_cache`, `credential_request_status`, `resident_transaction`,
  `ida.uin_auth_lock` for a given identity — matching how one token spans stores.
- **uin_ref_id / workflow_instance_id / bio_ref_id / credential ids** = stable
  UUIDs (uuid5) from the UIN.

## What is faithful and what is not

Faithful: table/field population, key relationships, hash algorithm, ID formats,
datashare URL ↔ object-key mapping.

Not faithful, by necessity or for practicality:

- The encrypted `uin` / `uin_data` / demographic columns are **placeholders**
  shaped like MOSIP ciphertext (`{saltId}_…#KEY_SPLITTER#…`), not real
  ciphertext — that needs the HSM key. Nothing in the deletion flow reads them;
  it keys on the hash. The true UIN lives in the manifest.
- `identity_cache.bio_data` is a few hundred bytes, not the real ~1.5 MB — the
  service deletes by `token_id` regardless of blob size.
- `cr_by` is set to `synthetic-seed` on every row. The service never keys on
  `cr_by`, so this doesn't affect the test, and it gives the teardown a
  bulletproof secondary sweep and makes synthetic rows obvious in the admin UI.
- The synthetic `idmap.vid.uin_hash` equals `idrepo.uin.uin_hash` for the same
  identity. In the **real** data those differ (the unresolved §6 discrepancy);
  the seed makes them consistent so a UIN-driven service resolves both. If you
  later reproduce the real quirk, adjust `derive.py`.

## Safety

Teardown deletes strictly by the manifest's recorded primary keys and object
keys, in child-before-parent order, so it can only ever remove synthetic rows —
it never issues a broad DELETE against real data. `--sweep-tag` additionally
clears anything still tagged `cr_by='synthetic-seed'` as a backstop. Re-running
`seed.py` is safe (`ON CONFLICT DO NOTHING`), but run `teardown.py` first if you
want a clean set, since the manifest is overwritten each run.
