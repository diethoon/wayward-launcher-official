# Wayward Android Launcher v2.0 — Original Recovery Report

## Recovery result

Original source recovery succeeded from the Vercel snapshot. All five files were checked against the user-supplied SHA-256 values and matched exactly. The local working tree was clean at recovered HEAD `13e14c11766c4e43476b680ac1d845a2297f6b82`.

## Provenance

- Repository: `diethoon/wayward-launcher-official`
- Local recovery branch: `work/android-file-engine-v2-20261119`
- Recovered HEAD: `13e14c11766c4e43476b680ac1d845a2297f6b82`
- Base: `a9f1684c7f53fead204f13015814ec293eaeb30b`
- Vercel snapshot: `snap_sb8iLzqGm9zFIiL8PJIPizHZtGJZ`

The recovered tree differs from the base in exactly five source, test, workflow, and documentation files. No other source paths were changed.

## SHA-256

| File | Bytes | SHA-256 |
|---|---:|---|
| `app/src/main/assets/imagepack-folder-picker.js` | 45,444 | `41e54271031f2497c7974fdeff0772c82885bee5a00ce61f83e495db060a8c4e` |
| `app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java` | 53,123 | `464c1a122b9d89177fb06a46e8b30ca1df3b55095ce62e677d2e34747b339ed6` |
| `.github/workflows/android-validation.yml` | 14,902 | `5777b8339548afdc22502d702ba00512dbe5a373ebd858674d8c34b879aab9fc` |
| `tests/file-access-engine-policy.test.mjs` | 4,997 | `9089a62ad05126f6b8899be3f0eb8cb4c7652916285060c4c741fff9512cb550` |
| `docs/android-file-access-engine-v2.md` | 3,440 | `a1cc134b4a717dc415eb225a5efbaa18849da672dd8ec3daf53a079ea558a443` |

## APKs

- Debug APK: 10,809,409 bytes; SHA-256 `81fe595d457124c935feb08a08c29ea47ce0d1a82494d817237e1d09c18c64f5`
- Release unsigned APK: 9,155,149 bytes; SHA-256 `56d61382773c699a4895f9f0352f295dad59deda416f677aa6805e587337c45e`

The Debug APK is signed with the existing v2.0 debug certificate. The Release APK is unsigned and is not a distributable signed release.

## Implementation review status

The original v2.0 implementation is preserved without modification. The known gaps before v2.1 are:
- SAF Provider queries and Cursor processing still execute synchronously in the native bridge.
- Full directory results are accumulated and serialized as one response rather than bounded WebView batches.
- JavaScript cancellation does not reliably cancel the in-flight native SAF query.
- Large-directory memory pressure remains due to accumulated results and File object/list construction.

The original implementation retains its existing FileList adapter, relative path behavior, input/change ordering, persistable URI permission handling, 32 MiB file cap, 64 KiB stream chunks, and maximum four concurrent streams.

No real-device or emulator tests were performed, consistent with the established prohibition.
