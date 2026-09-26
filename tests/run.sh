#!/usr/bin/env bash
# Integration tests. These run against a live stack, not mocks.
#
#   docker compose up -d postgres redis backend
#   ./tests/run.sh                # tests 1 and 2
#   ./tests/run.sh --multi        # also 3 and 4 (needs the backend2 profile)
#
# Requires Node 20+ for the built-in WebSocket client. No npm dependencies.

set -euo pipefail
cd "$(dirname "$0")/.."

API="${API_URL:-http://localhost:8080}"
API2="${API2_URL:-http://localhost:8081}"
LOADER="--import ./tests/support/register.mjs"

# Wait for an instance to be not just listening but actually able to reach
# Redis and PostgreSQL. Starting a test while a dependency is still coming up
# makes the server degrade gracefully — which is correct, but looks like a
# test failure.
await_ready() {
  local base="$1"
  echo "Waiting for $base ..."
  for _ in $(seq 1 60); do
    if curl -sf -m 2 "$base/api/health" >/dev/null \
       && [ "$(curl -sf -m 2 "$base/api/stats" | grep -o '"queueSize":-1' || true)" = "" ]; then
      return 0
    fi
    sleep 2
  done
  echo "$base is not ready. Check: docker compose ps" >&2
  return 1
}

await_ready "$API"

run() {
  echo
  echo "=============================================================="
  echo "  $1"
  echo "=============================================================="
  # shellcheck disable=SC2086
  node $LOADER "$2"
}

run "01 · End to end: auth, matchmaking, a full game, persistence" tests/01-end-to-end.mjs
run "02 · Protocol conformance: real messages through the client reducer" tests/02-protocol-conformance.mjs

if [[ "${1:-}" == "--multi" ]]; then
  echo
  echo "Multi-instance tests also need the second backend:"
  echo "  docker compose --profile scale up -d backend2"
  await_ready "$API2"
  run "03 · Multi-instance matchmaking and sticky redirect" tests/03-multi-instance.mjs
  run "04 · Concurrent matchmaking under a 40-player burst"  tests/04-matchmaking-load.mjs
fi

echo
echo "All integration tests passed."
