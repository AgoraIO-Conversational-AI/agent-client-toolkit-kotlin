# Python Backend

This FastAPI service keeps Agora credentials and agent lifecycle operations out
of the Android APK. It uses `agora-agents==2.4.1` with explicit Agora Fengming
STT plus managed OpenAI LLM and MiniMax TTS.

## Access Boundary

This is a development backend. `/get_config`, `/startAgent`, and `/stopAgent`
have no caller authentication or per-user authorization. A reachable caller
can obtain user tokens and invoke agent lifecycle operations. Keeping the App
Certificate on the server does not restrict who may call these endpoints.

The physical-device helper listens on `0.0.0.0:8000` so the Android phone can
reach the development machine over LAN. This binds all network interfaces;
use a trusted development LAN and limit inbound access to your test devices
with a firewall. Do not expose the service through public port forwarding or
tunnels. Shared or production deployments need authentication, authorization,
and abuse controls such as rate limits.

For testing entirely on the development machine, use `--host 127.0.0.1`.
Likewise, a Docker container used only from that machine can publish its port
with `-p 127.0.0.1:8000:8000`. A physical phone needs the development machine's
LAN address; it cannot reach a service bound only to the machine's loopback.

## Configure

```bash
cp server/.env.example server/.env.local
```

Set `AGORA_APP_ID` and `AGORA_APP_CERTIFICATE` in `server/.env.local`. The
managed provider path does not require third-party provider keys. `PORT` is
optional and defaults to `8000`.

## Run

From the repository root, use the physical-device helper:

```bash
./scripts/start_backend.sh
```

It creates `server/.venv` on first use, starts FastAPI on `0.0.0.0:8000`,
detects the development machine's LAN IP, and updates only
`agent.backend.url` in the Git-ignored root `local.properties` after the backend
passes its health check.

Open the printed LAN URL with `/docs` in a browser for the API summary, or use
`/health` for the startup probe. The Android app requests `/get_config` only
after the user taps **Start Agent**; launching the Activity alone does not call
the backend.

For backend-only development:

```bash
python3 -m venv server/.venv
server/.venv/bin/pip install -r server/requirements-dev.txt
server/.venv/bin/python -m uvicorn server.src.server:app --host 0.0.0.0 --port 8000
```

Successful `/get_config` responses use `Cache-Control: no-store`. The backend
sets the Agora SDK request timeout to 25 seconds, below the Android client's
30-second read timeout.

## Test

```bash
server/.venv/bin/pytest server/tests
```

The tests mock the SDK session boundary and do not call Agora cloud services.
FastAPI shutdown also stops every Agent session still tracked by this process.
