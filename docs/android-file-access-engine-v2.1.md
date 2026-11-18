# Android SAF File Access Engine v2.1

## Native execution and cancellation

- \`startListDirectory()\` performs request-ID and SAF-tree URI validation only; it does not query the provider.
- \`ContentResolver.query(..., CancellationSignal)\` and all Cursor iteration execute on a bounded two-thread executor.
- Each \`DirectoryTask\` owns its request ID, \`CancellationSignal\`, \`Future\`, active Cursor reference, and per-batch acknowledgement latch.
- Cancellation calls \`CancellationSignal.cancel()\`, interrupts the Future, releases any waiting acknowledgement, and closes an already-open Cursor. If a provider ignores cancellation while blocked inside its own query implementation, Android cannot guarantee immediate interruption; once the query returns, the cancelled task closes/discards its Cursor and does not emit more entries.
- WebView destruction calls \`cancelPending()\`/\`cancelAllDirectoryLists()\`, cancels all active tasks, clears request-scoped tree references, and shuts down the executor.

## Bounded delivery and memory

- Native output is limited to 32 entries per batch. Exactly one batch is in flight; the native worker waits up to 30 seconds for the matching WebView acknowledgement before reading more Cursor rows.
- JavaScript validates request ID and monotonically increasing batch index, consumes entries through an async iterator, and acknowledges only after the current batch has been consumed. The JS receive timeout is 35 seconds.
- Completion, provider error, cֻǱ˞