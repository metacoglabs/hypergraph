#!/usr/bin/env bash
# SPDX-FileCopyrightText: MetaCognition India Pvt Limited
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
set -Eeuo pipefail
cd "$(dirname "$0")"

image="${HSTORE_IMAGE:-hstore}:${HSTORE_TAG:-latest}"
if ! docker image inspect "$image" > /dev/null 2>&1; then
	echo "no $image image: run 'docker build -t hstore .' at the repository root, or set HSTORE_IMAGE and HSTORE_TAG" >&2
	exit 1
fi

export COMPOSE_PROJECT_NAME=hstore-memory-smoke
export HSTORE_PASSWORD="smoke-$(od -An -N8 -tx8 /dev/urandom | tr -d ' ')"
export HSTORE_USER=admin HSTORE_WIRE_PORT=17532 HSTORE_HTTP_PORT=17580
export HSTORE_URL="http://127.0.0.1:${HSTORE_HTTP_PORT}"
export SMOKE_STATE="$(mktemp)"
cleanup() {
	docker compose down -v > /dev/null 2>&1 || true
	rm -f "$SMOKE_STATE"
}
trap cleanup EXIT

docker compose up -d --wait
python3 smoke_test.py load
docker compose restart
python3 smoke_test.py after-restart
