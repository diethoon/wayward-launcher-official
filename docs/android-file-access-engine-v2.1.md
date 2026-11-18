# Android SAF File Access Engine v2.1

## Native execution and cancellation

- `startListDirectory()` performs request-ID and SAF-tree URI validation only; it does not query the provider.
- `ContentResolver.query(..., CancellationSignal)` and all Cursor iteration execute on a bounded two-thread executor.
- Each `DirectoryTask` owns its request ID, `CancellationSignal`, `Future`, active Cursor reference, and per-batch acknowledgement latch.
- Cancellation calls `CancellationSignal.cancel()`, interrupts the Future, releases any waiting acknowledgement, and closes an already-open Cursor. If a provider ignores cancellation while blocked inside its own query implementation, Android cannot guarantee immediate interruption; once the query returns, the cancelled task closes/discards its Cursor and does not emit more entries.
- WebView destruction calls `cancelPending()`/`cancelAllDirectoryLists()`, cancels all active tasks, clears request-scoped tree references, and shuts down the executor.

## Bounded delivery and memory

- Native output is limited to 32 entries per batch. Exactly one batch is in flight; the native worker waits up to 30 seconds for the matching WebView acknowledgement before reading more Cursor rows.
- JavaScript validates request ID and monotonically increasing batch index, consumes entries through an async iterator, and acknowledges only after the current batch has been consumed. The JS receive timeout is 35 seconds.
- Completion, provider error, cancellation, timeout, and duplicate request IDs have distinct handling. Request IDs isolate concurrent directory scans.
- Bulk traversal does not accumulate a full native directory list or clone/sort a JavaScript directory array. Provider-side display-name sorting is requested where supported; providers rejecting sort order are read in their returned order.
- The final FileList necessarily retains its File objects and indexed properties. The scanner does not create a second full File array for delivery.

## Limits and compatibility

- Maximum files: 100,000.
- Maximum total file and directory entries: 200,000.
- Maximum traversal depth: 128.
- The limits are checked independently. A missing caller policy permits all files; `data-wayward-file-policy` is parsed only from the requesting input and is not shared between requests.
- JavaScript yields every 256 processed entries.
- Existing FileList `length`, `item(index)`, iterator and numeric indices, `webkitRelativePath`, `input` then `change` event order, Persistable URI Permission behavior, 32 MiB file limit, 64 KiB stream chunks, four concurrent streams, and stream `cancel()`/close behavior are retained.

## Verification boundaries

- The Node policy suite covers independent limits and request-scoped policy isolation.
- The async batch protocol test simulates ordered batches, empty directories, provider errors, concurrent request isolation, cancellation, and a 100,000-entry synthetic traversal in 32-entry batches.
- CI compiles Java, runs the unit tests, builds Debug and Release APKs, and checks the Release signing certificate.
- The synthetic protocol test is not a real DocumentsProvider test. Real SAF Provider behavior, provider-specific cancellation latency, device memory profiling, and WebView destruction during a live provider query remain unverified. Physical-device and emulator tests remain disabled under the existing prohibition.
