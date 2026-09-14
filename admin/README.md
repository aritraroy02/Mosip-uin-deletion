# MOSIP Collab Data Admin

A read-only browser for the restored MOSIP environment — the seven Postgres
databases *and* the MinIO object store. Every table, every column, every object
key, no SQL — plus search across all of it at once.

## Running

The Docker stack from [`../docker`](../docker) must be up first.

```bash
pip install -r requirements.txt
python server.py
```

Then open **http://127.0.0.1:8090**. Override with `ADMIN_PORT`, `PGHOST`,
`PGUSER`, `PGPASSWORD` if your setup differs from the compose defaults.

## What it does

**Browse.** The sidebar lists each database with a live/offline dot, its table
count and on-disk size. Expand one to see every table with its row count; click
a table to page through it. Column headers sort, and the header shows each
column's Postgres type.

**Search in a table.** The box above the grid filters rows as you type and
reports an exact match count.

**Search everything.** The top-bar box sweeps every table in every database and
returns the tables containing the term, ranked by match count. Click a result to
jump straight into that table with the filter already applied — useful for
tracing one identifier (a RID, a UIN hash, a `token_id`) across module
boundaries.

**Search modes.** *Exact* matches a whole column value and can use the existing
btree indexes. *Contains* finds a substring anywhere and must read every row.
*Auto* (the default) picks Exact when the term looks like a complete identifier
— no spaces, no wildcards — and Contains otherwise. The mode actually used is
always stated under the results.

**Browse the object store.** The sidebar's *MinIO object store* group lists all
eight buckets with object counts and sizes. Open one to page through its keys
with size and last-modified, filter keys as you type, and click any key to see
its metadata plus a preview — decoded as text or JSON where it is text, as hex
where it is binary.

**Inspect big values.** Cells longer than 300 characters are clipped with a
`…more` link; binary columns render as `<n> bytes: <hex>`. Clicking either opens
the full value (32 KB of hex for binary).

## Why it is built this way

**Nothing here can write.** Every connection sets
`default_transaction_read_only` and a statement timeout, so a stray query can
neither modify data nor pin a connection open.

**Binary columns are never selected raw.** `ida.identity_cache` carries roughly
1.5 MB of biometric `bytea` per row; a 50-row page would otherwise be ~75 MB.
The size-plus-hex summary is computed in Postgres, so the bytes never cross the
wire.

**Search casts the row, not each column.** `ROW(a, b, c)::text ILIKE …` costs
one comparison per row instead of one per column. On
`resident_transaction` (36 columns) the per-column form took 15.3 s and blew
past the timeout; the row form takes about 3 s.

**Deep search is opt-in.** By default the sweep skips columns with no length
limit. In `resident_transaction` the two unbounded JWS signature columns are 72%
of the row text, and nobody substring-searches a cryptographic signature.
Ticking **deep** puts them back when you really do want every byte covered —
about 3× slower. Whichever columns were skipped are named under the table title,
so the coverage is never silent.

**Object keys are indexed in memory.** packet-manager alone holds 39,336 keys;
re-listing it over S3 for every page or keystroke would make the UI unusable.
The whole estate is ~49k keys, so the full index is built once (about 33 s) and
then filtering and paging are instant — a cached overview returns in 0.45 s.
Because the index is in memory, the object store is swept on every global
search at no meaningful cost, whatever the mode.

**Sweeps run concurrently**, across databases and across tables within each one.
The six 2.1 M-row Spring Batch tables in `mosip_credential` would otherwise
dominate every global search. This took one trace from 69 s to under 15 s.

**Exact match is the biggest lever by far.** MOSIP already indexes the
identifier columns people trace, so `column = value` is a bitmap index scan
while `ILIKE '%…%'` can use no index and must read every row. Measured on
`resident_transaction`, same single matching row: **10,299 ms versus 81 ms —
127x**. A whole-estate sweep for a UUID that previously left ten large tables
unsearched now covers every table in about 10 s.

## Limits worth knowing

In **Contains** mode a search genuinely has to read every byte of every table,
so on this dataset it stays a tens-of-seconds operation and the 2.1 M-row batch
tables will usually exceed the per-table ceiling. Those are reported as not
searched rather than silently dropped — open such a table and search it
directly, which uses the longer browsing timeout, or switch to Exact. Everything
is slower while a restore is running and competing for the disk.

Row counts in the sidebar are planner estimates (instant); the exact count is
computed for the table actually open.

The object index is a snapshot taken when the server first reads a bucket. Hit
**Refresh** to rebuild it after anything writes to MinIO.
