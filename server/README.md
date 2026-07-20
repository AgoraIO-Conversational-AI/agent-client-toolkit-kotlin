# Python Backend

This FastAPI service keeps Agora credentials and agent lifecycle operations out
of the Android APK. It uses `agora-agents==2.4.1` with explicit Agora Fengming
STT plus managed OpenAI LLM and MiniMax TTS.

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

## Test

```bash
server/.venv/bin/pytest server/tests
```

The tests mock the SDK session boundary and do not call Agora cloud services.
FastAPI shutdown also stops every Agent session still tracked by this process.
