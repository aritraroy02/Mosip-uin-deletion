#!/usr/bin/env bash
# Restore the MinIO bucket export into the dockerised MinIO.
#
# The backup is an object-level export (mc mirror style -- there is no
# .minio.sys in it), so the objects cannot simply be dropped into MinIO's data
# directory: a modern MinIO backend stores each object with its own xl.meta.
# They have to be uploaded as real objects, which is what `mc mirror` does.
#
# Extraction happens inside a Linux container onto a scratch Docker volume
# rather than on the host: 61k small files through NTFS is dramatically slower.
# The scratch volume is removed at the end.

set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")"

# Override with the same variables as docker/.env (export them first).
DUMPDIR="${DUMPS_DIR:-C:/Users/Harsh/Documents/sudo data}"
TARBALL="minIO-backup-2026-07-15.tar.gz"
NETWORK="mosip-collab_default"
SCRATCH="mosip-minio-restore-scratch"

echo "=== [1/3] extracting $TARBALL into scratch volume ==="
docker volume create "$SCRATCH" >/dev/null
docker run --rm \
  -v "$DUMPDIR:/dumps:ro" \
  -v "$SCRATCH:/work" \
  alpine:3 sh -c "rm -rf /work/* && tar -xzf '/dumps/$TARBALL' -C /work && echo extracted:\$(find /work -type f | wc -l) files"

echo "=== [2/3] creating buckets and mirroring ==="
docker run --rm \
  --network "$NETWORK" \
  -v "$SCRATCH:/work" \
  -e MC_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MC_PASS="${MINIO_ROOT_PASSWORD:-minioadmin}" \
  --entrypoint sh minio/mc -c '
    set -e
    mc alias set local http://minio:9000 "$MC_USER" "$MC_PASS" >/dev/null
    for d in /work/*/; do
      b=$(basename "$d")
      mc mb --ignore-existing "local/$b" >/dev/null
      printf "  mirroring %-28s " "$b"
      mc mirror --quiet --overwrite "$d" "local/$b" >/dev/null
      printf "done (%s objects)\n" "$(mc ls --recursive "local/$b" | wc -l)"
    done
    echo "--- final bucket listing ---"
    mc ls local
  '

echo "=== [3/3] removing scratch volume ==="
docker volume rm "$SCRATCH" >/dev/null
echo "=== minio restore finished ==="
