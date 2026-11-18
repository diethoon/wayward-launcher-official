# Wayward FAITH 3.0.0 file-picker API matrix

## Authoritative source

- Repository: \`diethoon/wayward\` (read-only)
- Branch: \`main\`
- File: \`Wayward_Faith_v3.0.0.html\`
- Exact Git blob SHA: \`ac6b44ccb75584383ad2875f4f36a55138c228cf\`

The previous WML source analysis is historical only. This matrix does not use \`Wayward_WML_ko.html\` or \`Wayward_MOD_v2.107.html\` as evidence for FAITH image-pack behavior.

## FAITH original upload contract

The original UI is emitted by \`wfPackFolderButton()\` and contains:

\`\`\`html
<input type="file" webkitdirectory multiple onChange={a} />
\`\`\`

The unchanged \`wfPackScanFolder()\` iterates \`Array.from(fileList)\`, normalizes backslashes in \`file.webkitRelativePath\`, looks for the \`image-packs/\` path segment, filters for \`.js\`, calls \`file.text()\`, and checks the exact marker \`/* WAYWARD_FAITH_IMAGE_PACK */\`. It then validates and registers the discovered image packs. A DirectoryHandle is not a FileList, and a SAF Tree URI is not a FileList; neither can replace the upload collection.

## Implemented Android mapping

| Request / contract | Web-facing result | Android / SAF implementation | Boundaries |
|---|---|---|---|
| FAITH \`webkitdirectory\` input click | Existing input's \`files\` becomes FileList-compatible for synchronous event dispatch | Capture-phase listener detects the actual \`webkitdirectory\` attribute and intercepts the input or associated label before the browser opens the generic chooser | Does not infer folder requests from \`showDirectoryPicker()\` or a chooser mode |
| SAF folder selection | Selected root name plus real child file metadata | Shared \`ImagePackDirectoryAccess\` uses \`ACTION_OPEN_DOCUMENT_TREE\`, persistable read permission and document-URI validation | Upload selection requests read-only access |
| Recursive enumeration | All descendants appear in one file collection | JS recursively calls the existing capability-guarded SAF directory-list operation; paths are slash-normalized and unsafe segments rejected | Maximum depth 128; maximum 50,000 files |
| FileList | \`length\`, \`item(index)\`, numeric indexes and iteration | Array-like collection based on \`FileList.prototype\` | Assigned only during synchronous event dispatch and then detached from the input |
| File objects | \`name\`, \`size\`, \`type\`, \`lastModified\`, \`webkitRelativePath\`, \`text()\`, \`arrayBuffer()\`, \`bytes()\`, \`stream()\`, \`slice()\` | Lazy SAF-backed Blob subclass with File prototype compatibility; file bytes are read only when a read method is called | Existing SAF bridge limits each full file read to 32 MiB; image assets are not eagerly copied into JS memory |
| Change flow | Original input receives \`input\` then \`change\` | Dispatches events on the original input after its temporary \`files\` property has been installed | Original \`wfPackFolderButton()\` and \`wfPackScanFolder()\` remain unmodified |
| Cancellation / error | \`cancel\` event or \`waywardfoldererror\` event | SAF cancellation is surfaced as \`AbortError\`; permission and provider failures are diagnosed | No empty fake FileList is delivered as success |
| Ordinary input[type=file] | Existing WebView chooser behavior | Existing \`WebFileChooser.onShowFileChooser()\` remains responsible for normal single/multiple file selection | Directory adapter ignores inputs without \`webkitdirectory\` |
| \`showDirectoryPicker()\` | Promise resolving to a DirectoryHandle-compatible object | Independent adapter uses the same SAF tree and document URI layer | It never substitutes a DirectoryHandle for FAITH's FileList |
| DirectoryHandle iteration | \`entries()\`, \`keys()\`, \`values()\`, async iteration, \`getDirectoryHandle()\`, \`getFileHandle()\`, \`resolve()\` | Real SAF document URIs are retained and revalidated for each operation | Unavailable \`id\` values and unsupported \`startIn\` forms reject with \`NotSupportedError\` rather than being silently ignored |
| FileHandle read | \`getFile()\` | Reads the selected SAF document URI and returns a browser File | Same 32 MiB bounded read |
| FileHandle write | \`createWritable()\` for the implemented bounded write path | Writes back to the selected content URI on close | Limited to 32 MiB in-memory staging; not a general-purpose unbounded streaming filesystem |

## Android 15 / API 35 compatibility

The API-35 path does not refer to \`FileChooserParams.MODE_OPEN_FOLDER\`. The public Android reference marks that constant as added in API 37. The Android 15 route is the app's existing \`ACTION_OPEN_DOCUMENT_TREE\` SAF flow, initiated by the DOM-level \`webkitdirectory\` detector. The generic WebView file chooser remains file-only and does not reinterpret \`MODE_OPEN_MULTIPLE\` as a folder request.

The adapter is installed with \`WebViewCompat.addDocumentStartJavaScript\` for the active game's allowed origin. AndroidX documents that this script executes in every frame whose origin matches the allowed-origin rules. The existing after-page-finished fallback remains origin-checked.

## Security, resource and lifecycle limits

- Each SAF document URI is checked against the selected tree before listing or reading.
- Relative path segments reject empty names, dot segments, slash, backslash and NUL.
- File content is loaded lazily; metadata enumeration does not eagerly read image bytes.
- The native full-file read limit is 32 MiB.
- Recursive traversal is bounded to 128 directory levels and 50,000 files.
- A second active folder request is rejected; cancellation is distinct from provider and permission errors.
- Destroying the WebView cancels the pending SAF request.
- The injected bridge capability is scoped to the active game WebView and native calls continue to verify it.

## Evidence classification

The contract tests run against the exact FAITH fixture and exercise the JavaScript adapter with a deterministic SAF provider stub. Java policy tests and Android Debug/Release builds are static/build evidence. They do not establish real Android WebView, DocumentProvider, file-picker UI, or device behavior. No emulator or physical-device SAF test is part of this work.


## Async lifetime and original scanner integration

The exact `wfPackFolderButton()` and `wfPackScanFolder()` bodies are embedded verbatim in `tests/fixtures/wayward-file-picker-source-contract.json`, with UTF-8 byte offsets and per-snippet Git blob SHA-1 values. The complete source payload was 23,245,163 UTF-8 bytes; the recomputed Git blob SHA-1 matched `ac6b44ccb75584383ad2875f4f36a55138c228cf`.

The original handler reads `event.target.files` before its first await. The scanner snapshots `Array.from(fileList)` before awaiting `File.text()`. The adapter now retains the successful FileList on the input after dispatch; a later successful selection replaces it. FAITH's synchronous array snapshot preserves prior File objects while an earlier scan is pending. This does not claim that overlapping handlers can each see a different `input.files` value.

The exact scanner is evaluated in the automated integration test with only parser/state side effects stubbed. The adapter-produced FileList is passed to it, and registration, nested paths, multiple packs, ignored paths, invalid markers, empty files, read failures, duplicate IDs, Unicode/special names and overlapping async snapshots are checked.

`queryPermission()` only inspects persisted SAF grants. A read-write `requestPermission()` opens a user-mediated SAF selection and reports granted only if the same tree is reselected with a persisted write grant; cancellation resolves denied. The bounded writable sink exposes `write()`, `seek()`, `truncate()`, `close()` and `abort()` but is not a native WritableStream subclass.

The SAF read bridge is not end-to-end streaming: it buffers each complete file, Base64-encodes it, and decodes it in JavaScript. `File.stream()` chunks consumption only after decoding. The 32 MiB per-file cap remains an explicit memory-safety boundary.
