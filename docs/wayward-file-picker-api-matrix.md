# Wayward original file API ↔ Android contract matrix

Source of truth: `docs/wayward-file-picker-source-analysis.md`; exact original blob `d198eacfc127c55dc44bd5d1b8e29ff2bb7d08e6`.

| Original API / operation | Source location and caller | Original options / input | Original result / consumer | Android route and baseline assessment |
|---|---|---|---|---|
| `<input type=file>` save-data import | HTML line 144; JSON save import component | `accept="application/json,.json"`; single; no capture | `files[0]`, then `File.text()`; save validator and UI error | WebChromeClient `MODE_OPEN` → `ACTION_OPEN_DOCUMENT`; MIME plus extension recheck. Existing route broadly matches; verify actual WebView accept/mode and callback result. |
| `multiple` image-pack import | HTML line 466; `g(FileList)` → `Oit(File)` | `multiple=true`; `accept=".json,application/json"`; order retained by sequential `Array.from(FileList)` loop | All Files parsed individually with `File.text()`; per-file result list | WebChromeClient `MODE_OPEN_MULTIPLE` → `ACTION_OPEN_DOCUMENT` + `EXTRA_ALLOW_MULTIPLE`; every URI must be returned in selection order. |
| `accept` | Lines 144 and 466 | JSON MIME and `.json` extension | Game validates content after reading | Preserve raw MIME/extension tokens; map MIME where possible; retain extension token and verify display name/MIME after selection. |
| `capture` | No game use | Not present | Not applicable | Do not claim game capture support is exercised. Generic WebView capture requests must either preserve a live capture Intent or explicitly fail. |
| `showOpenFilePicker()` | Not found | Not applicable | Not applicable | Not a Wayward source request. |
| `showSaveFilePicker()` | Not found | Not applicable | Not applicable | Not a Wayward source request. |
| Save-data export | Line 142 `ntt`; caller `exportSaveToFile` | Blob MIME `application/json`; suggested filename passed to helper | Anchor `download` click; no file-handle result | WebView Blob download must be bridged to `ACTION_CREATE_DOCUMENT`; preserve filename/MIME/bytes. Baseline has no `setDownloadListener` or Blob-download interception. |
| Play-log export | Line 143 `vtt`; UI download button | Blob MIME `application/jsonl`; timestamped filename | Anchor `download` click; returns count/filename to in-page caller | Same native save route as JSON export, preserving JSONL MIME and exact generated filename. |
| `showDirectoryPicker()` | Not found in game HTML | Not applicable | Not applicable | Existing injected `imagepack-folder-picker.js` is launcher-specific, not game-origin behavior. |
| `getFile()`, `createWritable()` | Not found in game HTML | Not applicable | Not applicable | Not required by this source. If the launcher-only adapter exposes these, every operation must target a real SAF document URI. |
| `getFileHandle()`, `getDirectoryHandle()`, iteration | Not found in game HTML | Not applicable | Not applicable | Not required by this source. Launcher-only adapter must not use synthetic `__path` identities. |
| Cancel / invalid selected file | Import handlers catch read/parse errors; empty FileList returns | User cancellation yields no selected files | No import callback when no file was selected; invalid file shows error | Android chooser must return `null` exactly once on cancellation; malformed/denied URI is an error and must not be represented as a successful selection. |

## Coverage and evidence classification

- Original source discovery: exact blob and SHA-256 verified.
- Original call sites: two file inputs, their change handlers, their File consumers, and two Blob-download helpers traced in the compiled HTML.
- No FSA folder picker API was found in the original Wayward HTML.
- Static source analysis is not device runtime evidence.
