#!/usr/bin/env bash
# Restore the MOSIP plain-SQL dumps into their per-database containers.
#
# These are plain-text pg_dump output, so they are replayed with `psql -f`
# (pg_restore does not read this format). Each dump carries its own
# DROP ... IF EXISTS / CREATE SCHEMA header, so re-running is idempotent.
#
# Each dump ends with GRANTs to a MOSIP per-module login role that does not
# exist in a fresh container, so the role is created before the replay.
#
# Restores run sequentially: the host has limited free RAM and the IDA dump
# alone is 36.8 GB, so parallel loads would contend badly.

set -uo pipefail
export MSYS_NO_PATHCONV=1          # keep Git Bash from rewriting /dumps
cd "$(dirname "$0")"

LOGDIR=./logs
mkdir -p "$LOGDIR"

restore() {
  local svc=$1 db=$2 role=$3 file=$4
  printf '=== %-18s <- %-24s ' "$db" "$file"

  docker compose exec -T "$svc" psql -U postgres -d "$db" -q -c \
    "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='$role')
       THEN CREATE ROLE $role LOGIN PASSWORD '$role'; END IF; END \$\$;" \
    >>"$LOGDIR/$db.log" 2>&1

  local start=$SECONDS
  docker compose exec -T "$svc" \
    psql -U postgres -d "$db" -v ON_ERROR_STOP=0 -f "/dumps/$file" \
    >"$LOGDIR/$db.log" 2>&1
  local rc=$? elapsed=$((SECONDS-start))
  local errs
  errs=$(grep -c 'ERROR:' "$LOGDIR/$db.log" 2>/dev/null || echo 0)
  printf 'exit=%s elapsed=%ss errors=%s\n' "$rc" "$elapsed" "$errs"
}

# Smallest first so problems surface early; IDA last because it is the bulk.
restore pg-idmap       mosip_idmap       idmapuser       mosip_idmap.dump
restore pg-idrepo      mosip_idrepo      idrepouser      mosip_idrepo.dump
restore pg-resident    mosip_resident    residentuser    mosip_resident.dump
restore pg-regprc      mosip_regprc      regprcuser      mosip_regprc.dump
restore pg-credential  mosip_credential  credentialuser  mosip_credential.dump
restore pg-ida         mosip_ida         idauser         mosip_ida.dump

echo "=== all restores finished ==="
