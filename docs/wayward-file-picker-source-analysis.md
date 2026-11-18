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

## Android integration implications

1. Keep WebView `onShowFileChooser` as the import route. Preserve single versus multiple selection and the exact JSON accept filters.
2. Do not infer a folder picker request from the multi-select file input. It selects multiple JSON files only.
3. Add a separate Blob-download-to-`ACTION_CREATE_DOCUMENT` route for the two actual exports. Preserve suggested filename, MIME type, and Blob bytes; report cancellation/failure without claiming the game selected a file.
4. The injected SAF folder adapter is not part of this original game's request contract. If retained as a launcher capability, it must remain separate and use actual SAF document URIs for each entry; it must not fabricate path-based identities.
5. The original game does not require a File System Access API polyfill. Any such adapter must be treated as a separate launcher extension, not claimed as original game behavior.
