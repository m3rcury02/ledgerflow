#!/usr/bin/env bash
# Runs one k6 scenario against the local application using the pinned grafana/k6 image on
# the same Docker bridge network as the application ("ledgerflow-local_ledgerflow",
# reaching it as http://app:8080), not the application's published host port. This is a
# deliberate, evidence-based choice, not a default: an earlier run of normal-traffic.js
# through the published port (--network host, http://localhost:18080) measured p(95)
# latency of ~547ms; the identical scenario run container-to-container on the compose
# network measured p(95) ~70ms. The ~8x difference is the local environment's port-forwarding
# layer (see docs/performance-experiments.md), not application behavior, so measuring the real
# request path avoids attributing environment artifacts to the application. Every scenario's
# lib/client.js reads its access tokens from
# performance/scenarios/tokens.json (git-ignored; performance/scripts/run-experiments.sh
# regenerates it before each scenario), not from an environment variable, since it needs a
# whole pool of subjects rather than one. Writes a JSON summary to performance/results/.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
K6_IMAGE="grafana/k6:1.4.0@sha256:6a3ee54ac0e9ff5527923f6295257453dd88012f32f40dadf0eb1b638cbb21c7"

SCENARIO=${1:?usage: run-k6.sh <scenario-file.js>}
NAME=$(basename "$SCENARIO" .js)
mkdir -p "$ROOT_DIR/performance/results"

if [[ ! -s "$ROOT_DIR/performance/scenarios/tokens.json" ]]; then
  echo "performance/scenarios/tokens.json is missing or empty; mint a token pool first" >&2
  exit 1
fi

docker run --rm --network ledgerflow-local_ledgerflow \
  --user "$(id -u):$(id -g)" \
  --env BASE_URL="${BASE_URL:-http://app:8080}" \
  --volume "$ROOT_DIR/performance:/performance:ro" \
  --volume "$ROOT_DIR/performance/results:/results" \
  "$K6_IMAGE" run \
  --summary-export "/results/$NAME.json" \
  "/performance/scenarios/$(basename "$SCENARIO")"
