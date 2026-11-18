# WWL launcher branding provenance

- App label: WWL
- Source APK: `apk-official-1.0.0-r3:Wayward-Launcher-1.0.0-Debug.apk`
- Source APK size: 8,047,377 bytes
- Source APK SHA-256: `3df80b2e5ed149ae6201307887d9db75ac569fd23f2f1f0c23d98baa8a82c833`
- Extracted PNGs are verified against the original recovered icon manifest; the normal and round density icons are byte-identical where the source APK defines them.
- Adaptive icon background: `#24170F`
- Adaptive icon foreground: original density-specific `ic_launcher_foreground.png` assets.
- Manifest connects both `android:icon` and `android:roundIcon`.
- Application ID, versionCode/versionName, activity/task configuration, backup rules, and signing configuration are unchanged.

## Source comparison note

The repository archive APK used by the restoration workflow is 8,047,377 bytes with SHA-256 `3df80b2e5ed149ae6201307887d9db75ac569fd23f2f1f0c23d98baa8a82c833`. The separately inspected file named `WaywardLauncher-1.0.0-Fix-Debug.apk` was 8,047,381 bytes with SHA-256 `2b3e6a4ab025886b192cf8fc9eead146284336b5c4679d48215e66f47c80fbca`; the whole APK files are therefore not byte-identical. However, all five density-specific foreground PNG hashes and all five density-specific launcher PNG hashes extracted from the repository archive match the previously recovered icon manifest exactly. The round icon PNGs are byte-identical to their corresponding regular density icons. The original compiled adaptive-icon XML was inspected with AAPT2: it references `@color/launcher_icon_background` and `@drawable/ic_launcher_foreground`; the resource table resolves the background to `#ff24170f`. The source XML committed here reconstructs those same references and is not claimed to be byte-identical to the compiled binary XML.
