# Android file-access engine: current v2 changes

## Contract

The SAF/WebView bridge remains unfiltered by default. The generic adapter only applies a file-selection policy when the requesting `<input type="file" webkitdirectory>` explicitly supplies `data-wayward-file-policy` JSON. The policy is parsed into a fresh immutable object for that picker invocation; it is not stored globally or persisted between HTML documents.

Supported request-local keys:

- `includeExtensions`: optional list of file extensions (with or without a leading dot)
- `excludeExtensions`: optional list of file extensions
- `includePathPrefixes`: optional list of case-sensitive relative path prefixes, including the selected root directory name
- `excludePathPrefixes`: optional list of case-sensitive relative path prefixes
- `maxFiles`: optional positive integer no greater than the engine's hard ceiling

Example caller opt-in:

```html
<input type="file" webkitdirectory multiple
  data-wayward-file-policy='{"includeExtensions":["png","webp"]}'>
```

This example is documentation only; no existing game or HTML has been changed to apply it. With no attribute, every discovered file remains eligible for delivery. Invalid JSON, unknown keys, malformed paths, and out-of-range limits fail the request instead of silently broadening or narrowing it.

## Large-tree safeguards

- Hard safety ceilings: 100,000 files, 200,000 total entries, and depth 128.
- SAF directory results are sorted in place rather than copied before sorting.
- Enumeration yields to the WebView event loop every 256 entries and between directory queries.
- The native SAF picker dialog remains single-flight, but separate selections can be enumerated concurrently after each dialog closes. Each enumeration owns an independent policy and cancellation control.
- `window.cancelWaywardDirectoryUpload(requestId)` cancels only the matching enumeration. An unscoped cancellation is accepted only when exactly one enumeration is active; all request state is cleared in `finally`. Cancellation is observed between directory calls and at 256-entry yield points; it cannot interrupt a SAF provider query already executing synchronously.
- The existing 32 MiB per-file bridge limit, 64 KiB native stream chunks, and four-open-stream cap are unchanged.

## Diagnostics

Native directory-list success events now include SAF query duration, native metadata/JSON construction duration, and JSON serialization duration. WebView directory-list events include bridge round-trip/parse duration. Enumeration completion includes directory count, total entry count, scanned file count, delivered file count, excluded count, whether a policy was applied, and elapsed time, plus cumulative File-object construction time. File names and contents are not added to diagnostics.

## Known boundaries

This change raises the previous 50,000-file ceiling to 100,000 to accommodate the previously observed 51,985-item tree without weakening the 200,000-entry/depth ceilings. It does not eliminate the provider's requirement to enumerate a directory cursor, and the synchronous JavaScript bridge call still blocks the WebView JavaScript execution context for the duration of one directory query and JSON parse. SAF timings must be collected on representative physical providers before claiming device-level speed improvements. The existing FileList compatibility object and on-demand file streams are retained.
