# Codex Android

Native Android workspace for managing GitHub repositories and running reviewed agent-assisted development from a phone.

## Current architecture

```text
Android
  -> authenticated BFF over HTTPS
  -> OpenAI Agents session
  -> GitHub repository snapshot pinned to base SHA
  -> openai_hosted sandbox, network disabled
  -> local Git baseline
  -> agent edits workspace
  -> deterministic text-only change exporter
  -> /workspace/outputs/changes.json
  -> OpenAI session Artifact
  -> BFF validation
  -> Android ChangeSetDraft + per-file diff review
  -> explicit user Publish
  -> atomic Git Data API commit to codex/*
  -> Pull Request
  -> GitHub Actions
  -> verified APK artifact
```

Agent output is treated as untrusted draft data. It never writes to GitHub automatically.

## Android capabilities

- Kotlin + Jetpack Compose
- encrypted GitHub credential storage
- encrypted Agent BFF token storage
- repository and branch browser
- file viewer/editor with diff
- commits, Pull Requests and GitHub Actions views
- workflow jobs and logs
- verified APK artifact download and installer flow
- Agent workspace
- SSE event stream
- steer/follow-up messages with idempotency keys
- real server-side active-turn cancellation
- disconnected-stream recovery from saved OpenAI session items
- encrypted active-session persistence across Android restarts
- reviewed multi-file `ChangeSetDraft`
- explicit atomic publish through Git Data API
- force-push disabled

## Agent BFF

The backend is a FastAPI application in `backend/`.

### Required production environment

- `OPENAI_API_KEY` — server-side OpenAI API credential.
- `CODEX_BFF_TOKEN` — dedicated bearer token used by the Android app to authenticate to `/v1/*`.

For private GitHub repositories also configure:

- `GITHUB_TOKEN` — server-side GitHub credential used only to fetch the pinned repository archive. It is never injected into the OpenAI sandbox.

Optional:

- `CODEX_STATE_DB` — SQLite state path. Default: `./codex-android-state.sqlite3` outside the container and `/data/codex-android-state.sqlite3` in the production image.
- `CODEX_AGENT_INSTRUCTIONS` — replacement system instructions for the coding agent.
- `PORT` — HTTP port for the Docker image, default `8080`.

The deployed BFF must be exposed through HTTPS. The Android client rejects clear-text remote BFF URLs; clear-text is accepted only for localhost/emulator development.

### Local backend

```bash
cd backend
python -m pip install -r requirements.txt

export OPENAI_API_KEY=...
export CODEX_BFF_TOKEN=...
export GITHUB_TOKEN=...   # required for private repositories

uvicorn app.server:create_runtime_app \
  --factory \
  --host 0.0.0.0 \
  --port 8080
```

### Docker

```bash
docker build -f backend/Dockerfile -t codex-android-bff backend

docker run --rm \
  -p 8080:8080 \
  -v codex-android-state:/data \
  -e OPENAI_API_KEY \
  -e CODEX_BFF_TOKEN \
  -e GITHUB_TOKEN \
  codex-android-bff
```

The container runs as an unprivileged user. Mount `/data` on persistent storage if session recovery must survive container replacement.

## Session safety

Session creation is protected by application idempotency plus an atomic claim in the state store. Two concurrent requests using the same `Idempotency-Key` cannot create two agent runtimes. A request that arrives while the first creation is still running receives HTTP `409` and may retry later with the same key.

The BFF stores only session association/state metadata in SQLite. OpenAI and GitHub credentials are not written to the session database.

Steer messages use a separate idempotency key. Cancellation is sent to OpenAI as a real `agent.session.input.cancel` event; closing the Android SSE connection is not treated as cancellation.

After an SSE disconnect, Android reconnects the stream and retrieves the saved remote session state/items. It does not automatically repeat the original task.

## Change safety

The sandbox:

- receives a repository archive pinned to the requested base SHA,
- has outbound network disabled,
- never receives the GitHub token,
- creates a local baseline Git commit,
- exports only UTF-8 text add/modify/delete/rename changes,
- rejects binary changes and unsafe paths.

Before publication:

1. BFF validates the exported artifact and its base SHA.
2. Android maps it to a local `ChangeSetDraft`.
3. The user reviews per-file diffs.
4. The user explicitly selects **Publish**.
5. `GitDataPublisher` verifies the current branch HEAD.
6. The multi-file update is written as one Git commit.
7. The branch ref is updated without force-push.

## Verification

Backend:

```bash
cd backend
python -m pytest -q
```

Android:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

GitHub Actions runs both test suites for `main` and `codex/**`. Backend CI also builds the production Docker image. Android CI publishes the debug APK artifact after tests and compilation succeed.

## Codex naming

This is an independent Android client. It does not impersonate an official OpenAI Codex application. OpenAI functionality is integrated only through supported API interfaces.
