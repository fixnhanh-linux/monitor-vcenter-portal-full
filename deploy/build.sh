#!/usr/bin/env bash
# Build 2 file jar (web-admin, web-khach) vào deploy/dist/. Có Maven + JDK 21 thì build trực tiếp, không thì build trong Docker.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/deploy/dist"
for m in web-admin web-khach; do
  if command -v mvn >/dev/null; then (cd "$ROOT/$m" && mvn -q -B -DskipTests package)
  else docker run --rm -v "$ROOT/$m:/src" -v vmmon-m2:/root/.m2 -w /src maven:3.9.9-eclipse-temurin-21 mvn -q -B -DskipTests package; fi
done
cp "$ROOT/web-admin/target/vm-monitor-admin.jar" "$ROOT/web-khach/target/vm-monitor-khach.jar" "$ROOT/deploy/dist/"
ls -lh "$ROOT/deploy/dist"
