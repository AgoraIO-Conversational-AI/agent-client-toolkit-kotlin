#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$REPO_ROOT/server"
VENV_DIR="$SERVER_DIR/.venv"
ENV_FILE="$SERVER_DIR/.env.local"
LOCAL_PROPERTIES="$REPO_ROOT/local.properties"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "Missing $ENV_FILE"
    echo "Create it with: cp server/.env.example server/.env.local"
    exit 1
fi

CONFIGURED_PORT="$(awk -F= '
    /^[[:space:]]*PORT[[:space:]]*=/ {
        value = substr($0, index($0, "=") + 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        gsub(/^['\''"]|['\''"]$/, "", value)
        print value
        exit
    }
' "$ENV_FILE")"
PORT="${PORT:-${CONFIGURED_PORT:-8000}}"
if ! [[ "$PORT" =~ ^[0-9]+$ ]] || (( ${#PORT} > 5 )); then
    echo "Invalid PORT '$PORT'. Use an integer from 1 to 65535."
    exit 1
fi
PORT="$((10#$PORT))"
if (( PORT < 1 || PORT > 65535 )); then
    echo "Invalid PORT '$PORT'. Use an integer from 1 to 65535."
    exit 1
fi

if ! python3 -c 'import sys; raise SystemExit(sys.version_info < (3, 10))'; then
    echo "Python 3.10 or later is required. Install a newer Python and retry."
    exit 1
fi

if ! python3 -c \
    'import socket, sys; sock = socket.socket(); sock.bind(("0.0.0.0", int(sys.argv[1]))); sock.close()' \
    "$PORT" 2>/dev/null; then
    echo "Port $PORT is already in use. Set PORT to a free port in $ENV_FILE or before the command."
    exit 1
fi

detect_lan_ip() {
    local interface=""
    local address=""

    if command -v route >/dev/null 2>&1 && command -v ipconfig >/dev/null 2>&1; then
        interface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
        if [[ -n "$interface" ]]; then
            address="$(ipconfig getifaddr "$interface" 2>/dev/null || true)"
        fi
    fi

    if [[ -z "$address" ]] && command -v hostname >/dev/null 2>&1; then
        address="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
    fi

    if [[ -z "$address" ]]; then
        return 1
    fi
    printf '%s' "$address"
}

LAN_IP="$(detect_lan_ip || true)"
if [[ -z "$LAN_IP" ]]; then
    echo "Could not detect a LAN IP. Add agent.backend.url to local.properties manually."
    exit 1
fi

BACKEND_URL="http://$LAN_IP:$PORT"

mkdir -p "$VENV_DIR"
if [[ ! -x "$VENV_DIR/bin/python" ]]; then
    python3 -m venv "$VENV_DIR"
fi

if ! "$VENV_DIR/bin/python" -c 'from importlib.metadata import version; import fastapi, uvicorn; assert version("agora-agents") == "2.4.1"' >/dev/null 2>&1; then
    "$VENV_DIR/bin/pip" install -r "$SERVER_DIR/requirements.txt"
fi

echo "Starting FastAPI on 0.0.0.0:$PORT"

cd "$REPO_ROOT"
"$VENV_DIR/bin/python" -m uvicorn server.src.server:app --host 0.0.0.0 --port "$PORT" &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' INT TERM EXIT

HEALTH_URL="http://127.0.0.1:$PORT/health"
for _ in {1..50}; do
    if curl --silent --fail "$HEALTH_URL" >/dev/null; then
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        wait "$SERVER_PID"
        exit $?
    fi
    sleep 0.1
done

if ! curl --silent --fail "$HEALTH_URL" >/dev/null; then
    echo "Backend did not become ready within 5 seconds at $HEALTH_URL."
    exit 1
fi
sleep 0.1
if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "Backend exited after the health check. Port $PORT may already be in use."
    wait "$SERVER_PID"
    exit 1
fi

temp_properties="$(mktemp)"
if [[ -f "$LOCAL_PROPERTIES" ]]; then
    awk '!/^agent\.backend\.url=/' "$LOCAL_PROPERTIES" > "$temp_properties"
fi
printf 'agent.backend.url=%s\n' "$BACKEND_URL" >> "$temp_properties"
mv "$temp_properties" "$LOCAL_PROPERTIES"

echo "Android backend URL: $BACKEND_URL"
echo "Updated: $LOCAL_PROPERTIES"
echo "Backend is ready. Run the Android app on a phone connected to the same LAN."

wait "$SERVER_PID"
