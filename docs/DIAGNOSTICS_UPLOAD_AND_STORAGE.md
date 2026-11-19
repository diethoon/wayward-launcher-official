# Android Diagnostics Button: Upload Flow and Storage

This document records the verified behavior of the Android launcher's **진단** button and the backend storage location. Keep this file in sync with both the development and official repositories.

## 1. Button entry point and upload behavior

Source reference: `WAL-Android-Development`, branch `work/android-product-split-v1.3-20261120`, commit `a4abb832b535a0b35c0cdc6b8c4b8c15de87ebec`.

- The home-screen button is created in `MainActivity` as `compactButton("진단", false, this::shareDiagnosticsZip)`.
- Tap handler: `MainActivity.shareDiagnosticsZip()`.
- The handler creates a new archive with `DiagnosticsBundleExporter.create()`, unless a previous failed upload is pending.
- It then calls `DiagnosticsBundleUploader.upload()` automatically. This is a real network upload; it is not merely a share-sheet export.
- The Android share chooser is also opened with that exact same ZIP. The share chooser is a secondary/manual sharing path, not the upload mechanism.
- Success/failure is shown by Toast. On failure, the same archive is marked for retry and is selected on the next diagnostics-button tap.

## 2. Local archive and pending retry location

All paths are app-private:

- ZIP directory: `Context.getCacheDir()/diagnostics/`
- ZIP name: `wayward-diagnostics-YYYYMMDD-HHmmss.zip`
- Failed-upload marker: `<zip-name>.upload-pending`

A successful upload removes the retry marker. The archive remains in cache until Android clears the cache or a later ZIP creation prunes archives older than 24 hours. Failed uploads retain the archive and marker for retry.

## 3. ZIP contents in the currently inspected implementation

The exporter currently includes:

- `metadata.json`
- `README.txt`
- `launcher.ndjson`
- `launcher.previous.ndjson`
- `imagepack.ndjson`
- `imagepack.previous.ndjson`

The active and previous event journals are each bounded to 512 KiB when copied into the ZIP. The ZIP does not include the game folder, selected folder contents, image-pack image files, or source game files.

### Important ImageGen / NovelAI diagnostic gap

The ImageGen service writes additional logs to:

- `Context.getFilesDir()/imagegen/startup.log`
- `Context.getFilesDir()/imagegen/server.log`

`ImageDiagnosticsActivity.refreshServerLog()` reads these files locally, but **the current `DiagnosticsBundleExporter.create()` does not add either file to the uploaded ZIP**. Therefore the current button upload can show that the local ImageGen proxy received a request and returned an HTTP status, but it cannot reliably show the NovelAI upstream response, provider error body, or backend stack trace.

Do not claim that a 200 response from `ImageGenProxy` proves that NovelAI completed generation: it only records the local loopback API response. To diagnose NAI failures end-to-end, a follow-up code change must add bounded, secret-redacted tails of `startup.log` and `server.log` to the ZIP. Apply the same token/API-key/password redaction used by `ImageDiagnosticsActivity.redactRuntimeLog()`; never include the raw `NAI_TOKEN`.

## 4. Cloud upload endpoint and protocol

Uploader source: `DiagnosticsBundleUploader.java`.

- Cloudflare Worker: `wayward-diagnostics-ingest`
- Base URL: `https://wayward-diagnostics-ingest.trpgengine.workers.dev`
- Ticket request: `POST /v1/ticket`
- ZIP upload: `POST /v1/upload`
- Maximum ZIP size: 5 MiB
- Ticket lifetime: 10 minutes
- Ticket rate limits in the Worker: 100 per source IP per hour and 10 per install ID per hour.

Protocol:

1. Client calculates ZIP SHA-256 and byte length.
2. Client requests a short-lived upload ticket with install ID, SHA-256, and byte length.
3. Worker stores only the ticket hash and expected metadata in D1.
4. Client uploads the ZIP as `application/zip` with the ticket as a Bearer credential.
5. Worker verifies ticket, size, and SHA-256, writes the ZIP to the private R2 bucket, then commits the submission metadata to D1.
6. The client treats a 2xx response containing `ok: true` and a submission ID as success.

The install ID is a random per-install identifier; it is not an Android hardware serial. The ZIP uploader does not send the NovelAI token.

## 5. Confirmed server-side storage

Cloudflare resources verified from the connected account:

- Worker: `wayward-diagnostics-ingest`
- D1 database: `wayward-diagnostics-db`
- D1 table: `submissions`
- R2 bucket: `wayward-diagnostics-private` (private bucket; no public object URL)
- R2 object key format:
  `reports/<install-id>/<YYYY-MM>/<submission-id>.zip`

The D1 `submissions` row records submission ID, install ID, SHA-256, R2 object key, byte length, upload time, app version, and error count. It is an index/metadata record; the actual ZIP bytes are stored in R2. The current Android ZIP uploader does not send the Worker's optional `X-Wayward-error-count` header, so the Worker stores the default `error_count = 0`; do not interpret that field as proof that the bundle contains no errors.

Verified lifecycle policy:

- Objects under `reports/` are deleted from R2 after 30 days.
- Incomplete multipart uploads are aborted after 24 hours.
- The inspected Worker does not delete old `submissions` rows. D1 metadata can therefore outlive the R2 ZIP unless a separate cleanup policy is added.

Access to the private R2 object contents requires the authorized Cloudflare account/API path; there is no public download endpoint in this Worker.

## 6. Separate background event uploader — not the 진단 button

Do not confuse the ZIP uploader with `DiagnosticsUploader`.

- `DiagnosticsUploader` sends only queued WARN/ERROR event records to
  `https://wayward-launcher-diagnostics-hoon-ead4.vercel.app/api/v1/ingest`.
- It runs automatically when urgent events are logged / the app resumes, in bounded batches.
- It does not upload the diagnostic ZIP.
- The **진단** button's ZIP upload uses the Cloudflare Worker and private R2 bucket described above.

## 7. Confirmed NAI investigation interpretation

For a report from the official 1.0.0 package, the uploaded launcher journal recorded:

- ImageGen service started.
- `novelai_credential_available_at_start` was recorded.
- Local `/api/image/health` returned HTTP 200.
- Game requests to local `POST /api/image/generate` returned HTTP 200.
- Local `GET /api/image/status/<job-id>` requests returned HTTP 200.
- The game settings lock to NovelAI was recorded.

These are useful transport and mode-selection signals, **not proof of successful NovelAI upstream authentication or completed image generation**. The ZIP did not contain `server.log` or `startup.log`, so the actual NAI upstream failure reason is not present in this report.

## 8. Diagnostic enrichment (security review 2026-11-20)

The existing home-screen button, ZIP creation, Cloudflare ticket/upload protocol, private R2 storage, and retry marker remain unchanged.

The ZIP additionally includes these app-private log tails when present:

- `imagegen/startup.log`: at most 128 KiB
- `imagegen/server.log`: at most 128 KiB

Only the tail is read. Missing or empty logs are omitted. Bearer credentials, NAI_TOKEN, API/access/refresh keys, authorization values, passwords, secrets, and cookie headers are redacted before ZIP insertion. Existing launcher and image-pack journals retain their existing 512 KiB per-file bound.

For local `GET /api/image/status/:promptId` responses, the launcher records a filtered `image_job_status` event containing only promptId, workflow, provider, normalized status (completed/error/queued/processing, otherwise unknown), sanitized error, HTTP status, and elapsed milliseconds. It does not persist raw status JSON or generation prompt. HTTP 200 is recorded separately from job status and is not treated as generation success.

The pinned NovelAI backend bundle patch records a `[novelai] generation_failed` line in the existing ImageGen server log with error type, parsed HTTP status when present, sanitized/truncated error message, model, and elapsed milliseconds. It never writes the token or Authorization header. This diagnostic line is collected by the same bounded server-log tail.

On upload success, the existing Toast includes the Worker's `submissionId`. On failure, the safe error code is shown and the same ZIP remains pending for retry. The D1 `error_count` field remains non-authoritative.

## 9. Maintenance requirements

- Keep this document in both repositories' `main` branches.
- When the button flow, endpoint, D1 schema, R2 bucket, or retention policy changes, update both copies in the same change.
- Any future diagnostic ZIP expansion must redact tokens, API keys, passwords, cookies, and user-selected file paths before upload.
- Preserve the current explicit button-triggered ZIP upload behavior and retry-on-failure behavior.
