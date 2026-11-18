# Wayward original file API ↔ Android contract matrix

Source of truth: `docs/wayward-file-picker-source-analysis.md`; exact original blob `d198eacfc127c55dc44bd5d1b8e29ff2bb7d08e6`.

| Original API / operation | Source location and caller | Original options / input | Original result / consumer | Android implementation and evidence |
|---|---|---|---|---|
| `<input type=file>` save-data import | HTML line 144; JSON save import component | `accept="application/json,.json"`; single; no capture | `files[0]`, then `File.text()`; save validator and UI error | WebChromeClient `MODE_OPEN` → `ACTION_OPEN_DOCUMENT`; raw accept tokens retained, MIME filter applied, returned filename/MIME revalidated, one URI returned. |
| `multiple` image-pack import | HTML line 466; `g(FileList)` → `Oit(File)` | `multiple=true`; `accept=".json,application/json"`; order retained by sequential `Array.from(FileList)` loop | All Files parsed individually with `File.text()`; per-file result list | WebChromeClient `MODE_OPEN_MULTIPLE` → `ACTION_OPEN_DOCUMENT` + `EXTRA_ALLOW_MULTIPLE`; ClipData and parsed results are deduplicated without collapsing to one URI. |
| `accept` | Lines 144 and 466 | JSON MIME and `.json` extension | Game validates content after reading | MIME mapping and extension suffix are retained independently; selected display name and provider MIME are checked against the original filter. |
| `capture` | No game use | Not present | Not applicable | No claim of game capture use. Generic WebView capture requests are accepted only when WebView's source Intent contains a live capture action; otherwise they fail explicitly. |
| `showOpenFilePicker()` | Not found | Not applicable | Not applicable | Not a Wayward source request. |
| `showSaveFilePicker()` | Not found | Not applicable | Not applicable | Not a Wayward source request. |
| Save-data export | Line 142 `ntt`; caller `exportSaveToFile` | Blob MIME `application/json`; suggested filename passed to helper | Anchor `download` click; no file-handle result | Document-start Blob URL capture → chunked WMLHost transfer → `ACTION_CREATE_DOCUMENT`; original filename, MIME and bytes preserved; content URI write permission checked and bytes streamed to the selected URI. |
| Play-log export | Line 143 `vtt`; UI download button | Blob MIME `application/jsonl`; timestamped filename | Anchor `download` click; returns count/filename to in-page caller and clears log storage | Same chunked save route. The bridge defers the original `localStorage.removeItem` until the native content write succeeds; cancel/failure preserves play logs. |
| `showDirectoryPicker()` | Not found in game HTML | Not applicable | Not applicable | Existing injected `imagepack-folder-picker.js` is launcher-only. It is kept separate from the game-origin contract and now stores actual SAF document URIs, not synthetic relative-path identities. |
| `getFile()`, `createWritable()` | Not found in game HTML | Not applicable | Not applicable | Not required by this source. In the launcher-only adapter, reads and writes target the selected content document URI; writes commit on stream close and enforce the 32 MiB per-document bridge limit. |
| `getFileHandle()`, `getDirectoryHandle()`, iteration | Not found in game HTML | Not applicable | Not applicable | Not required by this source. Launcher-only adapter enumerates SAF children and returns each provider document URI; create, delete, and resolve operations are URI-based. |
| Cancel / invalid selected file | Import handlers catch read/parse errors; empty FileList returns | User cancellation yields no selected files | No import callback when no file was selected; invalid file shows error | WebView chooser callback receives `null` exactly once on cancel/error; save export receives an internal success/failure result, and failed/cancelled play-log save does not clear the source log. |

## Coverage and evidence classification

- Original source discovery: exact blob and SHA-256 verified.
- Original call sites: two file inputs, their change handlers, their File consumers, and two Blob-download helpers traced in the compiled HTML.
- No FSA folder picker API was found in the original Wayward HTML.
- Added source-contract fixture and CI checks use the exact extracted request snippets and metadata.
- Static source analysis, contract tests and APK builds are not device runtime evidence.
