# Wayward FAITH 3.0.0 ↔ Android file and directory picker contract matrix

Authoritative source: `diethoon/wayward/main/Wayward_Faith_v3.0.0.html`  
Exact Git blob SHA: `ac6b44ccb75584383ad2875f4f36a55138c228cf`  
Detailed contract: `docs/wayward-file-picker-source-analysis.md`

The former WML baseline (`Wayward_WML_ko.html`, blob `d198eacfc127c55dc44bd5d1b8e29ff2bb7d08e6`) is deprecated for this work and must not be used as FAITH evidence.

| Request path | Web-facing contract | Android implementation requirement | Acceptance evidence |
|---|---|---|---|
| FAITH image-pack folder upload: `input[type=file][webkitdirectory][multiple]` | FileList-compatible collection; `length`, `item(index)`; file-like entries with `name`, `text()`, and `webkitRelativePath`; original change flow | Detect the directory input, open SAF tree picker, recursively enumerate descendants, read file contents on demand, preserve root-relative paths and dispatch the expected change flow | Test nested folders, `image-packs/` paths, JS marker files, file ordering, reads, cancellation, and unchanged FAITH scanner |
| `window.showDirectoryPicker()` | Promise resolving to DirectoryHandle-compatible object | Separate request path backed by SAF tree/document URIs; implement only supported handle operations and permission semantics | Test handle identity, traversal, file reads, supported writes, permission and cancellation behavior |
| Ordinary single-file input | Existing WebView FileChooser contract | Preserve current single-file chooser behavior | Existing single-file chooser tests |
| Ordinary multiple-file input | Existing WebView FileChooser contract | Preserve current multi-file chooser behavior; do not route it through the directory-upload adapter | Existing multiple-file chooser tests |
| SAF shared substrate | Native URI/document access | Tree permission, child enumeration, metadata, lazy reads, path normalization, lifecycle and error handling | Native unit/contract tests; no runtime claim without device testing |

## Non-equivalence rules

- `webkitdirectory` upload and `showDirectoryPicker()` are different web API contracts.
- A DirectoryHandle is not a FileList.
- A SAF Tree URI is not a FileList and does not automatically supply `webkitRelativePath`.
- Returning a Tree URI through `ValueCallback<Uri[]>` alone does not satisfy FAITH's folder-upload contract.
- The two web-facing adapters may share SAF internals, but must not share or conflate their return semantics.

## Android version rule

Android 15 / API 35 is in scope. Do not assume public `MODE_OPEN_FOLDER` support on API 35. Select and document an API-35-compatible SAF route. Keep public SDK availability, Chromium/WebView internal behavior, and actual device WebView behavior as separate evidence categories.

## Evidence status

The FAITH source identity and its `webkitdirectory` image-pack request are the acceptance baseline. Existing WML source-contract tests are not valid evidence for this matrix. Static contract tests and APK builds do not prove runtime behavior in Android WebView.
