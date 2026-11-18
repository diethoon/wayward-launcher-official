# Wayward original file-picker source analysis

## Frozen source identity

- Repository: `diethoon/wayward` (read-only)
- Branch observed: `main` at `50efc72ad46fe49d4849373a5dfe553227ed4a65`
- File: `Wayward_WML_ko.html`
- Git blob: `d198eacfc127c55dc44bd5d1b8e29ff2bb7d08e6` (matches the requested blob)
- UTF-8 SHA-256: `e159fb2002d31559b1ac1936cf6b48cdbbdc4858bfbb0b67e2d7284ca2195d1d`
- Size: 4,373,978 bytes; 3,506,143 Unicode characters; 492 lines.
- The source was fetched by the exact blob SHA. Existing `analysis_parts/` and `Wayward_MOD_v2.107.html` were not used.
- The file is a production/minified single HTML bundle; no source map reference was present in the HTML. The module script is inline.

## Actual game request inventory

The source contains exactly two `<input type="file">` elements.

### 1. Save-data import

Source line 144 (minified React component):

```js
u.jsx("input",{ref:c,type:"file",accept:"application/json,.json",className:"hidden",onChange:async H=>{var G;const ie=(G=H.target.files)==null?void 0:G[0];if(H.target.value="",!!ie)try{const se=await ie.text();if(!l(se)){p("해당 파일을 저장 데이터로 불러올 수 없습니다.");return}p(null),o==null||o()}catch(se){p(`파일을 읽지 못했습니다: ${se.message}`)}})
```

Contract:
- Single file: the handler reads only `files[0]`.
- Accept filter: `application/json,.json`.
- No `multiple`, `capture`, or directory attribute.
- Reads through `File.text()`, validates the parsed save through the existing validator, and reports invalid data/read errors in the UI.
- Resets `input.value` after reading so the same file can be selected again.
- This is an open/import request, not a save picker request.

### 2. Image-pack metadata import

Source line 466:

```js
u.jsx("input",{ref:h,type:"file",accept:".json,application/json",multiple:!0,className:"hidden",onChange:k=>void g(k.target.files)})
```

The real consumer is the component-local handler:

```js
const g=async k=>{if(!k||k.length===0)return;a(!0);const x=[];for(const S of Array.from(k)){const I=await Oit(S,c);x.push(I.ok?{ok:!0,text:o8(I)}:{ok:!1,text:I.reason})}s(x),await p(),a(!1),h.current&&(h.current.value="")}
```

The file consumer is:

```js
async function Oit(e,t){let o;try{o=JSON.parse(await e.text())}catch{return{ok:!1,reason:`${e.name}은(는) pack.json이 아닙니다.`}}const n=pne(o);return typeof n=="string"?{ok:!1,reason:`${e.name}: ${n}.`}:await _ne(n)?gne(n):{ok:!1,reason:`${n.name}의 pack.json은 찾았지만 이미지는 찾지 못했습니다. 팩 폴더는 ${t}이(가) 있는 폴더 안의 packs/${n.folder}이어야 합니다.`}}
```

Contract:
- Multiple files are explicitly requested and consumed sequentially in original FileList order via `Array.from(k)`.
- Accept filter: `.json,application/json`.
- No `capture` or directory attribute.
- Every selected File is parsed with `File.text()`; each per-file result is accumulated and reported.
- This is metadata JSON selection. The game's own UI says the pack folder/images are not read by this picker; users select each `pack.json`.

## Save/export behavior found in the same original bundle

The game does not call `showSaveFilePicker()`. It exports through Blob-backed download anchors.

- Save-data export: `ntt(filename, serializedSave)` creates a `Blob` with MIME `application/json`, assigns an object URL and `a.download=filename`, clicks the anchor, and revokes the URL after 1 second. Its caller is the store action `exportSaveToFile`; the default filename includes character name, day and timestamp.
- Play-log export: `vtt()` creates an `application/jsonl` Blob and downloads `play-log-<timestamp>.jsonl`; it returns the exported count and filename to its caller.
- Therefore the actual Android save integration point is the WebView download/Blob-anchor path, not `FileChooserParams.MODE_SAVE`.

## API absence findings

No occurrences were found in this exact source blob for:
- `showOpenFilePicker()`, `showSaveFilePicker()`, `showDirectoryPicker()`
- `FileSystemFileHandle.createWritable()`, `getFileHandle()`, `getDirectoryHandle()`
- `webkitdirectory`, `capture`, or `FileReader`

No game-origin folder picker request exists in this source. The Android `showDirectoryPicker` adapter is launcher-injected functionality, not a request emitted by this Wayward HTML. The source uses browser `File` objects for the two file inputs and Blob download anchors for exports.

## Android implementation mapping

1. WebView `onShowFileChooser` maps the single import to `MODE_OPEN` and the pack import to `MODE_OPEN_MULTIPLE`; it preserves the source accept tokens, MIME mapping, extension recheck and URI list.
2. The game's Blob anchor exports are intercepted at document start. The exact Blob object is retained before the game's immediate object-URL revocation, transferred to native in bounded chunks, and saved through `ACTION_CREATE_DOCUMENT` with the original filename and MIME.
3. Play-log cleanup is deferred: the original storage removal runs only after the native document write succeeds. Cancellation or write failure leaves the source play log intact.
4. The injected SAF folder adapter is not part of this original game's request contract. It remains a separate launcher capability and now uses actual SAF document URIs for each directory/file handle; the former `__path` and path-based native resolution have been removed.
5. Android SAF file reads, file creation, writes, directory creation, enumeration, deletion and handle resolution are backed by ContentResolver / DocumentsContract calls against the actual selected tree and child document URIs. Persisted read/write grants are rechecked on each handle permission query.

## Verification boundaries

- The source fixture in `tests/fixtures/wayward-file-picker-source-contract.json` records the exact source blob identity and the actual request/consumer snippets used by CI.
- CI runs JavaScript syntax, source-contract tests, WebView chooser tests, SAF bridge contract tests, Debug and Release APK builds, and signed Release identity verification when signing secrets are available.
- No Android emulator, physical-device, SAF UI, or PC/LAN runtime tests are performed under the active verification policy.
- SAF file-handle read/write uses a 32 MiB per-document bridge cap; a larger operation returns an explicit quota error rather than success with truncated content.
