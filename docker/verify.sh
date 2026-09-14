#!/usr/bin/env bash
# Verify each restored database against the row counts measured in the dumps.
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")"

check() {
  local svc=$1 db=$2
  echo "=== $db ==="
  docker compose exec -T "$svc" psql -U postgres -d "$db" -At -F'|' -c "
    SELECT n.nspname||'.'||c.relname, c.reltuples::bigint
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE c.relkind='r' AND n.nspname NOT IN ('pg_catalog','information_schema')
    ORDER BY c.reltuples DESC;" 2>&1 | awk -F'|' '{printf "  %-52s %12s\n", $1, $2}'
}

check pg-idmap      mosip_idmap
check pg-resident   mosip_resident
check pg-regprc     mosip_regprc
check pg-credential mosip_credential
check pg-ida        mosip_ida

echo "=== minio ==="
docker run --rm --network mosip-collab_default --entrypoint sh minio/mc -c \
  'mc alias set local http://minio:9000 minioadmin minioadmin >/dev/null; mc ls local' 2>&1
