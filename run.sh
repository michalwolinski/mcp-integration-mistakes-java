#!/usr/bin/env bash
# Builds the image and records one full run of the scenarios in scenarios.log.
# Pass scenario numbers to run a subset: ./run.sh 2 3
# Exits non-zero when any outcome differs from what the article claims (see "== expectations").
set -euo pipefail
cd "$(dirname "$0")"
docker compose build -q
status=0
docker compose run --rm -T --entrypoint sh demo -c 'java -jar /app/demo.jar "$@" 2>&1' -- "$@" | tee scenarios.log || status=$?
left=$(docker ps -q --filter ancestor=mcp-integration-mistakes-java:local | wc -l | tr -d ' ')
echo "containers left running: $left" | tee -a scenarios.log
[ "$left" = "0" ] || status=1
exit "$status"
