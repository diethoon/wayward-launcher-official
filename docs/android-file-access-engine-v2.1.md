# Android SAF File Access Engine v2.1

- SAF ContentResolver queries and Cursor iteration run on a bounded two-thread executor.
- Each task owns a CancellationSignal, Future, request ID, and per-batch acknowledgement latch.
- Native output is bounded to 32 entries per batch and waits up to 30 seconds for WebView acknowledgement; only one batch is in flight.
- JavaScript validates batch order, applies a 35-second receive timeout, and sends cancellation through the existing capability-gated bridge.
- Root tree URIs are isolated by picker request and released in a finally block.
- Bulk traversal uses an async iterator; no full directory result array or cloned/sorted array is created. FileList retains its required File objects.
- Existing policy defaults, independent limits, 256-item yielding, FileList shape/events, persistable permissions, 32 MiB cap, 64 KiB chunks, and four concurrent streams remain.
- Tests simulate protocol ordering, empty directories, provider errors, concurrent request isolation, and cancellation. Real DocumentsProvider/SAF performance and WebView-destroy integration tests remain unverified.
