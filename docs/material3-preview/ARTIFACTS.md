# Artifact identity and source correspondence

This is an experimental fork preview, not an upstream release or medical-safety certification.

## Public phone APK

- Filename: `juggluco-percentage-clip-8d1a56c3-arm64.apk`
- SHA-256: `8d1a56c30bd62653e856305f20ce768ec8ecec170142b52f3445686e7787c30f`
- Size: 15,341,639 bytes
- Package: `tk.glucodata.dub`
- ABI: `arm64-v8a`
- Build task: `:Common:assembleMobileLibre3SiDexGoogleReleasedub` with `-Pabi=arm64-v8a`
- Signing certificate SHA-256: `364faa248fa32da0dafe11ab889415e9388b629b6e946d1a42d24e56c4e6669d`
- This is the exact APK already installed by the owner, not an emulator/synthetic-hook build.

The APK was built while its source was an uncommitted working tree on top of `fcb4f80da1ac7032fc2d4e8cac82fcb5289e184f`. The 96 pending source/resource/test files at build time are enumerated in `apk-source-manifest.json`. All 96 staged file hashes initially matched that build manifest. Publication review then removed an unnecessary local SDK account path from **one test Markdown document**, `Common/src/test/MAIN_MENU_TILE_TESTS.md`; `publication-adjustments.json` records its before/after hashes. The other 95 files, including all application code and resources, remain byte-identical. Other application source remains inherited from that parent. Preview documentation and images are additional non-APK inputs.

This establishes the recorded source relationship, not byte-for-byte reproducibility or a complete supply-chain audit. The original fork base is upstream `afe6539f1e48aa0b6aeb1a34741752323858a5ad` (11.5.7); upstream `81f59687334771a44a6e348994f95f0065a671b5` (12.0.5) is not integrated.

The phone archive was inspected for private database, account/preference, key-store and QA-instrumentation archive entries; none were found by the bounded path checks. The native libraries present are `libg.so` and `libnative.so`. This is not proof that every sensor path has all its optional binary dependencies or has been tested.

## Non-shipping screenshot build

- Emulator APK SHA-256: `e0d05fbcd724d8a9bec745cb770901302deee07423af71251900d19cc2be3871`
- Android 14/API 34, x86_64 emulator.
- Same application source; a temporary x86_64 ABI-selection entry was the only build-file change, restored after building.
- Separate instrumentation seeded only invented rows and an inactive fictional meter. That instrumentation is not in the downloadable phone APK and is not a release asset.
- App launch, seed completion and navigation were observed; the crash buffer was empty after the capture session.
- `screenshot-manifest.json` identifies each published PNG, the original emulator-capture hash, the exact emulator APK and the disclosure-banner transformation.

No screenshots from personal devices, patient exports or medical-source directories were used. Screenshot labels are experimental/synthetic disclosures, not altered readings or fabricated app UI.

## Verification boundaries

Current staged-source checks: Java decision 17 passed; Java binding/persistence 4 passed; connection policy 5 passed; menu XML contracts 3 passed. Native import, reset, source-incarnation, snapshot, legacy-match and plot-transform fixtures passed with sanitizers where configured. Earlier exact-row Android clipping fixture: 72 cases passed. None establishes clinical safety, real sensor accuracy, reliable multi-phone meter handoff or full security coverage.
