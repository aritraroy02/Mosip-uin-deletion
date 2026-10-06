#!/usr/bin/env python3
"""
Read-only web browser for the restored MOSIP Collab databases.

Serves a small JSON API plus the static UI in ./static. Every connection is
opened read-only with a statement timeout, so nothing here can modify or hang
the restored data.

Two things drive most of the design:

  * ida.identity_cache holds ~1.5 MB of biometric bytea per row. Raw bytea is
    never selected -- it is summarised server-side as a size plus a short hex
    preview, otherwise a single page of rows would be hundreds of megabytes.

  * credential.batch_* tables hold ~2.1 M rows each. Table listings therefore
    use planner estimates, and exact counts are fetched only for the table
    actually being viewed.
"""

import json
import mimetypes
import os
import re
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import psycopg
from minio import Minio
from minio.error import S3Error
from psycopg import sql

# Host ports for every database this tool browses.
#
# The first seven are the deletion-environment databases from
# docker/docker-compose.yml, each its own container on its own port.
#
# The last two live together on the eSignet stack's Postgres
# (esignet/docker-compose, container docker-compose-database-1), exposed on host
# port 5455. They are the "mock eSignet" databases: mosip_mockidentitysystem
# holds the identities eSignet authenticates against (individuals, kyc_auth,
# verified_claim) and mosip_esignet holds the OIDC state (client_detail,
# consent, token/key tables). Same host, user and password as the rest, so they
# slot into the same connection model -- the port simply repeats.
DATABASES = {
    "mosip_idmap": 5442,
    "mosip_idrepo": 5448,
    "mosip_regprc": 5443,
    "mosip_resident": 5444,
    "mosip_credential": 5445,
    "mosip_ida": 5446,
    "mosip_deletion_audit": 5447,
    "mosip_mockidentitysystem": 5455,
    "mosip_esignet": 5455,
}


def _load_dotenv(path):
    """Fill os.environ from a KEY=VALUE .env file; real variables take precedence."""
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    os.environ.setdefault(key.strip(), value.strip())
    except FileNotFoundError:
        pass


# Settings come from admin/.env (template: .env.example) or the environment.
# With neither, the local docker ports and credentials above and below apply.
_load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))

# One PostgreSQL port for every database (typical outside local docker, where
# all databases share one server). Unset keeps the per-database ports above.
if os.environ.get("PGPORT"):
    DATABASES = {db: int(os.environ["PGPORT"]) for db in DATABASES}

PGHOST = os.environ.get("PGHOST", "127.0.0.1")
PGUSER = os.environ.get("PGUSER", "postgres")
PGPASSWORD = os.environ.get("PGPASSWORD", "postgres")

STATEMENT_TIMEOUT_MS = 20000      # per-query ceiling for browsing
SEARCH_TIMEOUT_MS = 12000         # per-table ceiling during a sweep
CELL_PREVIEW = 300                # chars shown in the grid before truncating
BYTEA_PREVIEW = 24                # bytes of bytea rendered as hex in the grid
SEARCH_CAP = 500                  # capped match count per table
SWEEP_WORKERS = 3                 # concurrent table scans per database
PAGE_MAX = 200

MINIO_ENDPOINT = os.environ.get("MINIO_ENDPOINT", "127.0.0.1:9000")
MINIO_ACCESS_KEY = os.environ.get("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.environ.get("MINIO_SECRET_KEY", "minioadmin")
OBJECT_PREVIEW = 8192             # bytes pulled when previewing an object

STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")


# ---------------------------------------------------------------- object store

_minio = None
_objects = {}                     # bucket -> list of {key, size, modified}
_minio_lock = threading.Lock()


def minio_client():
    global _minio
    if _minio is None:
        _minio = Minio(MINIO_ENDPOINT, access_key=MINIO_ACCESS_KEY,
                       secret_key=MINIO_SECRET_KEY, secure=False)
    return _minio


def bucket_names():
    try:
        return [b.name for b in minio_client().list_buckets()]
    except (S3Error, OSError, ValueError) as exc:
        raise ApiError("object store not reachable: " + str(exc), 503)


def objects_of(bucket, refresh=False):
    """
    Every key in a bucket, indexed once and held in memory.

    packet-manager alone holds 39,336 objects. Re-listing it over S3 for each
    page or keystroke would make the UI unusable, and the whole estate is only
    ~61k keys, so the full index is cheap to keep and makes filtering and
    paging instant.
    """
    with _minio_lock:
        cached = _objects.get(bucket)
    if cached is not None and not refresh:
        return cached
    try:
        listed = [
            {"key": o.object_name, "size": o.size or 0,
             "modified": o.last_modified.isoformat() if o.last_modified else None}
            for o in minio_client().list_objects(bucket, recursive=True)
        ]
    except (S3Error, OSError, ValueError) as exc:
        raise ApiError("cannot list " + bucket + ": " + str(exc), 503)
    listed.sort(key=lambda o: o["key"])
    with _minio_lock:
        _objects[bucket] = listed
    return listed


def bucket_overview(refresh=False):
    out = []
    for name in bucket_names():
        try:
            objs = objects_of(name, refresh)
            out.append({"bucket": name, "objects": len(objs),
                        "bytes": sum(o["size"] for o in objs), "online": True})
        except ApiError as exc:
            out.append({"bucket": name, "objects": 0, "bytes": 0,
                        "online": False, "error": str(exc)})
    return out


def object_page(bucket, term, page, size):
    objs = objects_of(bucket)
    if term:
        needle = term.lower()
        objs = [o for o in objs if needle in o["key"].lower()]
    start = page * size
    return {"bucket": bucket, "total": len(objs), "page": page, "size": size,
            "objects": objs[start:start + size]}


def object_detail(bucket, key):
    """Metadata plus a bounded preview, decoded as text when it is text."""
    client = minio_client()
    try:
        stat = client.stat_object(bucket, key)
    except (S3Error, OSError, ValueError) as exc:
        raise ApiError("cannot stat object: " + str(exc), 404)

    head, response = b"", None
    try:
        response = client.get_object(bucket, key, offset=0,
                                     length=min(OBJECT_PREVIEW, stat.size or 0))
        head = response.read()
    except (S3Error, OSError, ValueError):
        head = b""
    finally:
        if response is not None:
            response.close()
            response.release_conn()

    try:
        text = head.decode("utf-8")
        printable = sum(c.isprintable() or c.isspace() for c in text)
        as_text = len(text) == 0 or printable / len(text) > 0.9
    except UnicodeDecodeError:
        as_text, text = False, ""

    if as_text:
        try:
            text = json.dumps(json.loads(text), indent=2)
        except (ValueError, TypeError):
            pass
        preview, kind = text, "text"
    else:
        preview, kind = head.hex(), "hex"

    return {"bucket": bucket, "key": key, "size": stat.size,
            "content_type": stat.content_type,
            "modified": stat.last_modified.isoformat() if stat.last_modified else None,
            "etag": stat.etag, "kind": kind, "preview": preview,
            "truncated": (stat.size or 0) > OBJECT_PREVIEW}


def sweep_objects(term):
    """Key-substring matches per bucket. In-memory, so effectively instant."""
    needle = term.lower()
    results = []
    for name in bucket_names():
        try:
            hits = [o for o in objects_of(name) if needle in o["key"].lower()]
        except ApiError:
            continue
        if hits:
            results.append({"database": "minio", "schema": "bucket",
                            "table": name, "name": name, "hits": len(hits),
                            "capped": False, "status": "ok", "kind": "objects"})
    return results


class ApiError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def connect(db, timeout_ms=STATEMENT_TIMEOUT_MS):
    if db not in DATABASES:
        raise ApiError("unknown database " + repr(db), 404)
    try:
        conn = psycopg.connect(
            host=PGHOST, port=DATABASES[db], dbname=db,
            user=PGUSER, password=PGPASSWORD, connect_timeout=4,
        )
    except psycopg.OperationalError as exc:
        raise ApiError(db + " is not reachable: " + str(exc).strip(), 503)
    conn.read_only = True
    with conn.cursor() as cur:
        cur.execute("SET statement_timeout = " + str(int(timeout_ms)))
    return conn


def list_tables(conn):
    """Tables with planner row estimates and on-disk size."""
    with conn.cursor() as cur:
        cur.execute("""
            SELECT n.nspname,
                   c.relname,
                   GREATEST(c.reltuples::bigint, COALESCE(s.n_live_tup, 0)),
                   pg_total_relation_size(c.oid)
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
            WHERE c.relkind = 'r'
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
            ORDER BY n.nspname, c.relname
        """)
        return [
            {"schema": r[0], "table": r[1], "name": r[0] + "." + r[1],
             "estimated_rows": max(r[2], 0), "bytes": r[3]}
            for r in cur.fetchall()
        ]


def columns_of(conn, schema, table):
    with conn.cursor() as cur:
        cur.execute("""
            SELECT column_name, udt_name, data_type, character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = %s AND table_name = %s
            ORDER BY ordinal_position
        """, (schema, table))
        cols = cur.fetchall()
    if not cols:
        raise ApiError("no such table " + schema + "." + table, 404)
    return [{"name": c[0], "udt": c[1], "type": c[2], "maxlen": c[3]}
            for c in cols]


def select_expr(col):
    """
    Render one column safely for grid display.

    bytea becomes "<n> bytes: <hex>" so a 1.5 MB biometric blob costs a few
    dozen characters; everything else is cast to text and clipped one character
    past the preview limit so truncation can be detected client-side.
    """
    ident = sql.Identifier(col["name"])
    if col["udt"] == "bytea":
        return sql.SQL(
            "CASE WHEN {c} IS NULL THEN NULL ELSE "
            "octet_length({c})::text || ' bytes: ' || "
            "encode(substring({c} from 1 for {n}), 'hex') END"
        ).format(c=ident, n=sql.Literal(BYTEA_PREVIEW))
    return sql.SQL("left({c}::text, {n})").format(
        c=ident, n=sql.Literal(CELL_PREVIEW + 1))


# Types that carry no length limit, and so may hold arbitrarily large values.
UNBOUNDED = {"text", "varchar", "bpchar", "json", "jsonb", "xml"}
TEXTUAL = {"varchar", "text", "bpchar"}
NUMERIC = {"int2", "int4", "int8", "numeric"}

# A term with no spaces or wildcards is almost certainly a whole identifier --
# a UUID, RID, UIN hash or token -- rather than a phrase to find inside text.
IDENTIFIER = re.compile(r"^[A-Za-z0-9_.:@-]{6,}$")


def resolve_mode(term, mode):
    """
    Pick between indexed equality and a full substring scan.

    This is the single biggest performance lever in the whole tool. MOSIP
    indexes the identifier columns people actually trace (reg_id, token_id,
    event_id, uin_hash), so equality on them is a bitmap index scan, while
    ILIKE '%...%' can use no index at all and must read every row. Measured on
    resident_transaction: 10,299 ms for the substring scan versus 81 ms for
    equality -- the same single matching row, 127x apart.
    """
    if mode in ("exact", "contains"):
        return mode
    return "exact" if IDENTIFIER.match(term or "") else "contains"


def exact_targets(cols, term):
    """Columns an equality predicate can address without defeating an index."""
    out = [c for c in cols if c["udt"] in TEXTUAL]
    if term.isdigit() and len(term) <= 18:
        out += [c for c in cols if c["udt"] in NUMERIC]
    return out


def searchable(cols, deep=False):
    """
    Columns a search predicate may touch.

    bytea is always excluded -- casting binary blobs to text is useless and
    slow. Unbounded text is excluded unless deep search is requested: in
    resident_transaction the two unbounded JWS signature columns are 72% of the
    row text, so skipping them cuts the scan several-fold, and a cryptographic
    signature is not something anyone substring-searches for. Deep search puts
    them back when the caller really does want every byte covered.
    """
    out = []
    for c in cols:
        if c["udt"] == "bytea":
            continue
        if not deep and c["udt"] in UNBOUNDED and c["maxlen"] is None:
            continue
        out.append(c)
    return out


def skipped_columns(cols, deep, mode="contains", term=""):
    if mode == "exact":
        searched = {c["name"] for c in exact_targets(cols, term)}
    else:
        searched = {c["name"] for c in searchable(cols, deep)}
    return [c["name"] for c in cols if c["name"] not in searched]


def where_clause(cols, term, deep=False, mode="contains"):
    """
    Build the search predicate for the resolved mode.

    exact -- OR of `column = value` over textual (and, for a digit term,
    numeric) columns. The literal is parameterised and the column is left
    untouched, so btree indexes still apply and Postgres can bitmap-OR them.

    contains -- one case-insensitive comparison over the whole row rather than
    one per column. Casting each column separately is dramatically more
    expensive on wide tables holding large text: resident_transaction has 36
    columns including two unbounded JWS signature fields, and the per-column
    form blew past a 20 s timeout where ROW(...)::text takes about 3 s.

    bytea is left out of both: hex-expanding a 1.5 MB biometric blob for every
    row would be far worse than the problem being solved.
    """
    if not term:
        return None, []

    if mode == "exact":
        targets = exact_targets(cols, term)
        if not targets:
            return None, []
        parts, params = [], []
        for c in targets:
            parts.append(sql.SQL("{c} = %s").format(c=sql.Identifier(c["name"])))
            params.append(int(term) if c["udt"] in NUMERIC else term)
        return sql.SQL(" OR ").join(parts), params

    targets = searchable(cols, deep)
    if not targets:
        return None, []
    row = sql.SQL("ROW({cols})::text").format(
        cols=sql.SQL(", ").join(sql.Identifier(c["name"]) for c in targets))
    return sql.SQL("{r} ILIKE %s").format(r=row), ["%" + term + "%"]


def qualified(schema, table):
    return sql.Identifier(schema) + sql.SQL(".") + sql.Identifier(table)


def order_by(cols, sort, direction):
    if sort and any(c["name"] == sort for c in cols):
        return sql.SQL(" ORDER BY {c} {d}").format(
            c=sql.Identifier(sort),
            d=sql.SQL("DESC" if direction == "desc" else "ASC"))
    return sql.SQL("")


def fetch_rows(db, schema, table, term, page, size, sort, direction, deep=False,
               mode="auto"):
    mode = resolve_mode(term, mode)
    with connect(db) as conn:
        cols = columns_of(conn, schema, table)
        target = qualified(schema, table)

        pred, params = where_clause(cols, term, deep, mode)
        where = sql.SQL(" WHERE ({p})").format(p=pred) if pred else sql.SQL("")
        order = order_by(cols, sort, direction)

        with conn.cursor() as cur:
            # Exact count, but only for the table actually being viewed.
            try:
                cur.execute(
                    sql.SQL("SELECT count(*) FROM {t}{w}").format(t=target, w=where),
                    params)
                total = cur.fetchone()[0]
                exact = True
            except psycopg.errors.QueryCanceled:
                conn.rollback()
                total, exact = None, False

            cur.execute(
                sql.SQL("SELECT {sel} FROM {t}{w}{o} LIMIT %s OFFSET %s").format(
                    sel=sql.SQL(", ").join(select_expr(c) for c in cols),
                    t=target, w=where, o=order),
                params + [size, page * size])
            raw = cur.fetchall()

    rows = []
    for r in raw:
        out = []
        for value in r:
            if value is None:
                out.append(None)
            elif len(value) > CELL_PREVIEW:
                out.append({"v": value[:CELL_PREVIEW], "truncated": True})
            else:
                out.append(value)
        rows.append(out)

    return {"columns": cols, "rows": rows, "total": total, "exact": exact,
            "page": page, "size": size, "deep": deep, "mode": mode,
            "not_searched": skipped_columns(cols, deep, mode, term) if term else []}


def fetch_cell(db, schema, table, column, term, offset, sort, direction,
               deep=False, mode="auto"):
    mode = resolve_mode(term, mode)
    """Full value of one cell, for the expand-in-place view."""
    with connect(db) as conn:
        cols = columns_of(conn, schema, table)
        col = next((c for c in cols if c["name"] == column), None)
        if col is None:
            raise ApiError("no such column " + repr(column), 404)

        target = qualified(schema, table)
        pred, params = where_clause(cols, term, deep, mode)
        where = sql.SQL(" WHERE ({p})").format(p=pred) if pred else sql.SQL("")
        order = order_by(cols, sort, direction)

        if col["udt"] == "bytea":
            # 32 KB of hex is plenty to inspect a blob's structure.
            expr = sql.SQL(
                "CASE WHEN {c} IS NULL THEN NULL ELSE "
                "octet_length({c})::text || ' bytes total' || chr(10) || chr(10) || "
                "encode(substring({c} from 1 for 32768), 'hex') END"
            ).format(c=sql.Identifier(column))
        else:
            expr = sql.SQL("{c}::text").format(c=sql.Identifier(column))

        with conn.cursor() as cur:
            cur.execute(
                sql.SQL("SELECT {e} FROM {t}{w}{o} LIMIT 1 OFFSET %s").format(
                    e=expr, t=target, w=where, o=order),
                params + [offset])
            row = cur.fetchone()

    return {"value": row[0] if row else None, "bytea": col["udt"] == "bytea"}


def sweep_table(db, meta, cols, term, deep, mode):
    """Capped match count for one table, on its own connection."""
    pred, params = where_clause(cols, term, deep, mode)
    if pred is None:
        return None
    try:
        with connect(db, SEARCH_TIMEOUT_MS) as conn, conn.cursor() as cur:
            cur.execute(
                sql.SQL("SELECT count(*) FROM "
                        "(SELECT 1 FROM {t} WHERE ({p}) LIMIT %s) s").format(
                    t=qualified(meta["schema"], meta["table"]), p=pred),
                params + [SEARCH_CAP])
            hits = cur.fetchone()[0]
        status = "ok"
    except psycopg.errors.QueryCanceled:
        hits, status = 0, "timeout"
    except (psycopg.Error, ApiError):
        hits, status = 0, "error"

    if not hits and status == "ok":
        return None
    return {"database": db, "schema": meta["schema"], "table": meta["table"],
            "name": meta["name"], "hits": hits,
            "capped": hits >= SEARCH_CAP, "status": status}


# Spring Batch job-run history: pure operational metadata (job_execution_id,
# status, exit_code, serialized_context, ...) with no UIN, hash, token or any
# identity data. These are the only 2.1 M-row tables in the estate, and their
# unindexed text columns (serialized_context) force a full scan that blows the
# per-table ceiling. They can never hold anything an identity search targets, so
# the global sweep skips them by default; they stay browsable directly, and a
# deliberate substring hunt can still include them via include_operational=1.
OPERATIONAL_TABLES = {
    "batch_job_execution", "batch_job_execution_context",
    "batch_job_execution_params", "batch_job_instance",
    "batch_step_execution", "batch_step_execution_context",
}


def sweep(db, term, deep=False, mode="contains", include_operational=False):
    """
    Capped match counts for every table in one database.

    Tables are scanned concurrently. Spring Batch job-history tables are skipped
    unless include_operational is set -- see OPERATIONAL_TABLES -- because they
    hold no identity data and are the sole cause of sweep timeouts.
    """
    with connect(db, SEARCH_TIMEOUT_MS) as conn:
        metas = list_tables(conn)
        cols = {m["name"]: columns_of(conn, m["schema"], m["table"])
                for m in metas}
    if not metas:
        return [], 0

    scanned, skipped = [], 0
    for m in metas:
        if not include_operational and m["table"] in OPERATIONAL_TABLES:
            skipped += 1
        else:
            scanned.append(m)

    results = []
    if scanned:
        with ThreadPoolExecutor(max_workers=min(SWEEP_WORKERS, len(scanned))) as pool:
            futures = [pool.submit(sweep_table, db, m, cols[m["name"]], term,
                                   deep, mode) for m in scanned]
            for future in as_completed(futures):
                hit = future.result()
                if hit:
                    results.append(hit)
    return results, skipped


def overview():
    """Per-database reachability and totals; drives the sidebar."""
    out = []
    for db in DATABASES:
        entry = {"database": db, "port": DATABASES[db]}
        try:
            with connect(db, 8000) as conn:
                tables = list_tables(conn)
            entry.update(online=True, tables=len(tables),
                         estimated_rows=sum(t["estimated_rows"] for t in tables),
                         bytes=sum(t["bytes"] for t in tables))
        except ApiError as exc:
            entry.update(online=False, tables=0, estimated_rows=0, bytes=0,
                         error=str(exc))
        out.append(entry)
    return out


SAFE_NAME = re.compile(r"^[A-Za-z0-9_]+$")


def require_name(params, key):
    value = (params.get(key) or [""])[0]
    if not SAFE_NAME.match(value):
        raise ApiError("invalid " + key)
    return value


def one(params, key, default=""):
    return (params.get(key) or [default])[0]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, body, ctype):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, payload, status=200):
        self._send(status, json.dumps(payload, default=str).encode(),
                   "application/json")

    def do_GET(self):
        parsed = urlparse(self.path)
        route, params = parsed.path, parse_qs(parsed.query)
        try:
            if route.startswith("/api/"):
                return self._api(route, params)
            return self._static(route)
        except ApiError as exc:
            self._json({"error": str(exc)}, exc.status)
        except Exception as exc:                      # noqa: BLE001
            self._json({"error": type(exc).__name__ + ": " + str(exc)}, 500)

    def _api(self, route, params):
        if route == "/api/overview":
            payload = {"databases": overview()}
            try:
                payload["buckets"] = bucket_overview(
                    one(params, "refresh") == "1")
            except ApiError as exc:
                payload["buckets"] = []
                payload["buckets_error"] = str(exc)
            return self._json(payload)

        if route == "/api/objects":
            bucket = one(params, "bucket")
            if not bucket:
                raise ApiError("bucket is required")
            page = max(0, int(one(params, "page", "0")))
            size = min(PAGE_MAX, max(1, int(one(params, "size", "50"))))
            return self._json(object_page(bucket, one(params, "q"), page, size))

        if route == "/api/object":
            bucket, key = one(params, "bucket"), one(params, "key")
            if not bucket or not key:
                raise ApiError("bucket and key are required")
            return self._json(object_detail(bucket, key))

        if route == "/api/tables":
            with connect(one(params, "db")) as conn:
                return self._json({"database": one(params, "db"),
                                   "tables": list_tables(conn)})

        if route == "/api/rows":
            page = max(0, int(one(params, "page", "0")))
            size = min(PAGE_MAX, max(1, int(one(params, "size", "50"))))
            return self._json(fetch_rows(
                one(params, "db"), require_name(params, "schema"),
                require_name(params, "table"), one(params, "q"),
                page, size, one(params, "sort"), one(params, "dir", "asc"),
                one(params, "deep") == "1", one(params, "mode", "auto")))

        if route == "/api/cell":
            return self._json(fetch_cell(
                one(params, "db"), require_name(params, "schema"),
                require_name(params, "table"), require_name(params, "column"),
                one(params, "q"), max(0, int(one(params, "offset", "0"))),
                one(params, "sort"), one(params, "dir", "asc"),
                one(params, "deep") == "1", one(params, "mode", "auto")))

        if route == "/api/search":
            term = one(params, "q")
            if not term:
                raise ApiError("q is required")
            deep = one(params, "deep") == "1"
            include_op = one(params, "include_operational") == "1"
            mode = resolve_mode(term, one(params, "mode", "auto"))
            scope = one(params, "db")
            targets = [scope] if scope in DATABASES else list(DATABASES)

            # Each database is its own container, so sweeping them in parallel
            # costs nothing extra and turns the wall time from the sum of all
            # scans into the slowest single one.
            found, offline, skipped_op = [], [], 0
            with ThreadPoolExecutor(max_workers=len(targets)) as pool:
                futures = {pool.submit(sweep, db, term, deep, mode, include_op): db
                           for db in targets}
                for future in as_completed(futures):
                    try:
                        rows, skipped = future.result()
                        found.extend(rows)
                        skipped_op += skipped
                    except ApiError:
                        offline.append(futures[future])
            # Object keys are matched from the in-memory index, so the store
            # is swept regardless of mode at no meaningful cost.
            try:
                found.extend(sweep_objects(term))
            except ApiError:
                offline.append("minio")

            found.sort(key=lambda r: r["hits"], reverse=True)
            return self._json({"term": term, "results": found,
                               "offline": offline, "deep": deep, "mode": mode,
                               "skipped_operational": skipped_op})

        raise ApiError("unknown endpoint", 404)

    def _static(self, route):
        rel = "index.html" if route in ("/", "") else route.lstrip("/")
        path = os.path.normpath(os.path.join(STATIC_DIR, rel))
        if not path.startswith(STATIC_DIR) or not os.path.isfile(path):
            return self._send(404, b"not found", "text/plain")
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        with open(path, "rb") as fh:
            self._send(200, fh.read(), ctype)


def main():
    port = int(os.environ.get("ADMIN_PORT", "8090"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("MOSIP data admin  ->  http://127.0.0.1:" + str(port))
    print("databases: " + ", ".join(DATABASES))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()
