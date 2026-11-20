# WAL Android Launcher diagnostics and NovelAI CAPTCHA

## Diagnostic ZIP contents

`DiagnosticsBundleExporter.create()` creates a ZIP in the app cache. The ZIP always contains:

- `metadata.json`: app package/version/build type, Android release/SDK, export time, and selected journal-presence/failure metadata
- `README.txt`: bundle scope and sharing warning

The following entries are included when the corresponding file exists and is non-empty:

- `launcher.ndjson`
- `launcher.previous.ndjson`
- `imagepack.ndjson`
- `imagepack.previous.ndjson`
- `imagegen/startup.log`
- `imagegen/server.log`

**Correction:** `startup.log` and `server.log` are not excluded. The exporter explicitly adds both entries when present. Each server log is exported as a redacted tail of at most 128 KiB. Event journals are copied with a per-file cap of 512 KiB. Missing or empty files are omitted.

## Redaction scope and limitations

The exporter masks:

- Bearer credential values
- Values following `NAI_TOKEN`, `TOKEN`, `API_KEY`, `APIKEY`, access/refresh token, authorization, password, and secret labels
- `Cookie` and `Set-Cookie` header values

The embedded ImageGen 0.8.4 launcher patch additionally redacts the positive prompt, negative prompt, NovelAI token, Bearer values, and token-like key/value fields from the `[novelai] generation_failed` message before writing it to `server.log`.

This is targeted redaction, not a guarantee that every arbitrary identifier, URL, file path, or prompt emitted by unrelated code is removed. Review a ZIP before sharing it. The exporter does not add game HTML, game saves, image files, or selected game-folder contents.

## Image generation request and failure path

The pinned backend is Wayward ImageGen 0.8.4 from source commit:

`26d6c32e0b0abbb886e3029efd548b7ca6774401`

The relevant local API flow is:

1. The game sends `POST /api/image/generate`.
2. `src/routes/image.ts` validates the request, calls `jobs.submit(...)`, and returns `jobResponse(...)`. The NovelAI job is queued and the POST response can be returned before the upstream NovelAI request finishes.
3. `src/comfy/jobRunner.ts` performs `NovelAI_이미지생성하기(...)` asynchronously. If NovelAI rejects the request, the job is marked `error` and stores its safe error message.
4. A later `GET /api/image/status/:promptId` calls `jobResponse(...)` again and is the normal API response that carries the asynchronous job error.

This explains the original Android detection gap: inspecting only GET status responses cannot detect a failure if the game never sends that follow-up request. A `POST /api/image/generate` response normally contains the queued job ID/status, not the eventual NovelAI error.

## CAPTCHA error contract

When the upstream error contains:

`Recaptcha token is required for trial generation`

the backend now labels the failed job with:

`errorType: novelai_trial_recaptcha_required`

The image status response exposes both `error` and `errorType`. The Android launcher:

- Inspects the immediate generate response for a synchronous CAPTCHA error.
- Starts a bounded, read-only status watcher only when the generate response identifies a queued/processing `workflow: nai` job. In local mode it reads the loopback status API; in paired-PC mode it uses the existing authenticated `RemoteGameImageApiClient` status route.
- Stops the watcher when the game itself begins polling that job, or when the job reaches a terminal state.
- Does not retry generation, modify its payload, alter the selected provider, or read/store NovelAI credentials.
- Opens the CAPTCHA handoff screen when the exact error message or structured error type is received.

The watcher is limited to 120 checks at 2.5-second intervals. It performs only `GET /api/image/status/:promptId` requests through the active local or paired-PC route and never sends a second generation request. Paired-PC status IDs are restricted to the existing UUID allowlist.

## CAPTCHA handoff screen

The handoff opens the official NovelAI image page:

`https://novelai.net/image/gallery`

The user signs in and manually starts an image generation to let NovelAI decide whether a CAPTCHA challenge is required. The launcher does not inspect, capture, forward, or persist CAPTCHA tokens or login credentials. After completing any verification NovelAI presents, the user closes the page and retries generation from the launcher.

A CAPTCHA challenge is not guaranteed to appear merely because the page opened; the challenge decision is made by NovelAI.

## Diagnostic event names

The launcher records bounded metadata only; it does not record prompts, API tokens, cookies, or request bodies.

| Event | Purpose |
|---|---|
| `api_response` | HTTP method, local API path, status, elapsed time |
| `novelai_trial_captcha_detected` | CAPTCHA error found in a generate/status response |
| `status_watch_started` | Native watcher started for an asynchronous job |
| `status_watch_http_response` | Watcher HTTP status and attempt number |
| `status_watch_delegated_to_game_poll` | Game client began polling; native watcher stopped |
| `status_watch_terminal_without_captcha` | Job completed/failed without CAPTCHA signal |
| `status_watch_timeout` | Bounded watcher ended without a terminal result |
| `activity_launch_post_accepted` | WebView accepted the Activity launch post |
| `activity_launch_started` / `activity_launch_failed*` | Activity start outcome |
| `activity_launch_suppressed_duplicate` | Duplicate launch prevented |
| `webview_load_requested`, `webview_page_load_started`, `webview_page_load_finished` | Official page loading progress |
| `webview_main_frame_load_failed`, `webview_main_frame_http_error`, `webview_ssl_error_cancelled` | Main-frame load failures |

## Upload behavior

The diagnostic ZIP uploader accepts an explicitly created ZIP, enforces a 5 MiB maximum, and sends the ZIP hash/size and an installation identifier to the diagnostics ingest service before uploading the archive. It does not upload source game files or selected game-folder contents. Upload failure may retain the ZIP locally for retry; inspect the archive before sharing it manually.
