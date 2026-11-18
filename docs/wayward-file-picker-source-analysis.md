# Wayward FAITH 3.0.0 file-picker source contract

## Authoritative source identity

- Repository: `diethoon/wayward` (read-only)
- Branch: `main`
- File: `Wayward_Faith_v3.0.0.html`
- Git blob SHA: `ac6b44ccb75584383ad2875f4f36a55138c228cf`
- Source identity status: the exact Blob SHA was queried from GitHub and matched. Do not substitute a different Wayward edition or an older source file.

This document supersedes the previous WML-based analysis. The former `Wayward_WML_ko.html` blob `d198eacfc127c55dc44bd5d1b8e29ff2bb7d08e6` is historical evidence only and is not an acceptance baseline for FAITH 3.0.0.

## Confirmed FAITH image-pack folder-upload contract

The FAITH 3.0.0 image-pack folder input uses the directory-upload form:

```html
<input type="file" webkitdirectory multiple onChange={a} />
```

The relevant original functions are:

- `wfPackFolderButton()`
- `wfPackScanFolder()`

The original scan flow consumes the selected file collection, examines each file's `webkitRelativePath`, locates paths containing `image-packs/`, reads JavaScript files, and checks the FAITH image-pack marker:

```js
/* WAYWARD_FAITH_IMAGE_PACK */
```

It then validates and registers discovered packs.

### Required browser-facing contract

The Android compatibility layer must provide the original handler with a file collection that behaves as a FileList and contains file-like objects with:

- `length`
- `item(index)`
- `name`
- `text()`
- `webkitRelativePath`

The relative path must preserve the selected folder's hierarchy and the `image-packs/` segment exactly as the original scanner expects. Returning only a SAF Tree URI or a DirectoryHandle does not satisfy this upload contract.

The original input/change flow and the existing `wfPackScanFolder()` implementation must remain unmodified. The adapter must deliver the selected files through the input's expected change-event path.

## Separate File System Access API requirement

`window.showDirectoryPicker()` is a distinct API contract. It returns a directory handle through a Promise; it does not produce the FileList expected by a `webkitdirectory` input.

The launcher must support both capabilities independently:

1. FAITH `webkitdirectory`: return a FileList-compatible collection with file objects and `webkitRelativePath`.
2. Launcher File System Access API: return a DirectoryHandle-compatible object backed by SAF document URIs.

A shared Android SAF folder-selection and document-access layer may serve both. The web-facing return values and event/Promise semantics must remain distinct.

Do not claim that FAITH itself calls `showDirectoryPicker()` unless that call is found in the exact FAITH 3.0.0 blob. It is a separate launcher compatibility target in this work.

## Android implementation mapping

1. Detect a file input carrying `webkitdirectory` and route it to the Android SAF folder-selection flow. Do not treat it as an ordinary multi-file document picker.
2. Recursively enumerate the selected SAF tree.
3. Construct the browser-facing file collection, including `length`, `item()`, `text()`, and correctly rooted `webkitRelativePath` values.
4. Dispatch the original input/change flow so the unchanged FAITH scanner receives the collection it expects.
5. Implement `window.showDirectoryPicker()` separately, with DirectoryHandle-compatible semantics backed by actual SAF document URIs.
6. Keep ordinary single-file and multiple-file chooser requests working without changing their behavior.

## Platform constraints

- Android 15 / API 35 is a required compatibility target.
- Do not assume the public `FileChooserParams.MODE_OPEN_FOLDER` API is available on API 35; the public folder mode is API 37-era functionality.
- Verify the actual Android System WebView behavior and the public SDK contract separately.
- A Tree URI returned through `ValueCallback<Uri[]>` is not, by itself, a FileList with relative paths.
- Do not expose unsupported File System Access API methods as if they were implemented.

## Verification boundaries

- Source baseline: exact FAITH 3.0.0 Git blob SHA above.
- Static tests must assert the FAITH source identity and the directory-upload contract, not WML filenames, WML line numbers, or WML snippets.
- Contract tests must cover FileList shape, file reads, relative paths, folder recursion, cancellation, and separation from DirectoryHandle semantics.
- Debug/Release builds and CI are static/build evidence only. They do not establish Android WebView runtime success.
- No emulator, physical-device, SAF UI, or PC/LAN runtime test is authorized under the active verification policy.

## Historical baseline warning

The previous version of this document described `Wayward_WML_ko.html`, including its JSON file inputs and Blob-download behavior. Those findings may remain historically true for that WML blob, but they are unrelated to the FAITH 3.0.0 image-pack folder-upload acceptance criteria. Never reuse that WML analysis as evidence that FAITH folder upload works.
