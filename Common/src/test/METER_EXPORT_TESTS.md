# Saved blood-label Health Connect exporter (synthetic tests)

## Scope and safety contract

- Schema 2 identifies both database incarnations (database ID + persistent 128-bit UUID + positive epoch) and reports position coverage. A record may be deleted only when **every** membership durably recorded for its insert is still present with complete prefix coverage. Pruning, source replacement, missing sources, unknown legacy provenance, or malformed inventories never prove deletion. Valid retained rows still backfill while cleanup is deferred. Memberships are unioned across later duplicate observations and are durably committed with insertion intent before the external write.
- `Natives.healthConnectBloodSnapshot()` supplies `[2, currentUnit, bloodLabel, 2, sourceA..., sourceB...]`; each source is `[databaseIdent, uuidHi, uuidLo, epoch, firstPos, lastPos, coverageComplete, bloodRowCount, epochSeconds, unsignedFloatBits, ...]`. No trailing or partial data is accepted; null is unavailable, not an empty inventory. Both sources must be genuine even when both contain no blood rows.
- Initial export interprets historical rows using the current dashboard unit (1 mmol/L, 2 mg/dL). Saved numbers have **no per-row unit provenance**; a previously mixed-unit history cannot be disambiguated. No magnitude inference is performed. The first valid export durably binds both unit and blood-label index; later changes freeze meter inserts/deletes. Disabling/re-enabling does not reset that binding.
- All exported records use **unknown specimen source and unknown recording method**, no meter/device attribution. A saved blood label does not establish capillary source, manual input, Bluetooth input, or meter model.
- Deterministic client ID: `juggluco:meter:v1:<epochSeconds>:<mgdl*1000 rounded>`. SDK conversion is 18 mg/dL per mmol/L, verified in the pinned 1.1.0-rc03 source. Canonical 0.001 mg/dL precision absorbs float conversion noise; equal content/mirrors collapse. Same-time readings with distinguishable canonical values remain distinct.
- Ledger codec v2 keeps `O|<ownedID>` and `P|<time>|<milliMgdl>` entries and adds `M|<owned-or-intent-ID>|<databaseIdent>|<uuidHi>|<uuidLo>|<epoch>` memberships. A missing membership entry means permanently unknown legacy proof. Codec v1 ownership/intents migrate without becoming deletion evidence, even when their content is subsequently seen. Duplicate/orphan/unowned/invalid provenance is rejected. All membership unions and reconstruction payloads commit synchronously before external insertion. Lost responses replay stable IDs before source-aware cleanup; failed or partial backfills never reach cleanup. Deletion remains exact owned client-ID only: no CGM IDs, unrelated IDs, broad query, time-range deletion, read permission, or live source access.
- `HealthConnection.Companion.init(MainActivity)` remains the Java startup/enable trigger. MainActivity already calls it while `gethealthConnect()` is true. It launches immediate saved-row backfill, then a process-lifetime ~30-second timer; permission grants trigger another immediate request. Requests are serialized and conflated, with a cross-generation mutex guarding persistence through disable/re-enable. Android may suspend/kill the process; startup resumes the next time the app is opened. This is not a guaranteed 30-second OS background job.
- Only foreground initialization may launch one missing-permission dialog per enabled instance. Background/CGM retries check permissions without prompting. Re-enabling explicitly allows another foreground request. Cancellation propagates, including while waiting for Health Connect.
- CGM still uses `GlucoseList` and its existing native cursor. The meter ledger is separate and never deletes CGM data.
- Clearing app data/losing the ledger is not an ownership-recovery mechanism: stable IDs still deduplicate unchanged rows, but there is deliberately no attempt to find/delete old untracked rows.

## Host regression suite

`MeterExportTest.kt` is a plain Kotlin `main`, with actual policy/exporter/codec/worker code and a fake persistent (serialized ledger) store plus idempotent Health Connect client-ID map. No JUnit, Android, device, raw sources, medical screenshot, or account access is needed.

With Kotlin and coroutines available:

```sh
OUT="$TMPDIR/juggluco-meter-tests"
mkdir -p "$OUT"
kotlinc \
  Common/src/mobile/java/tk/glucodata/MeterExportPolicy.kt \
  Common/src/mobile/java/tk/glucodata/MeterExportCodec.kt \
  Common/src/mobile/java/tk/glucodata/MeterExporter.kt \
  Common/src/mobile/java/tk/glucodata/MeterExportWorker.kt \
  Common/src/mobile/java/tk/glucodata/MeterExportForeground.kt \
  Common/src/test/java/tk/glucodata/MeterExportTest.kt \
  -classpath "$COROUTINES_JAR" -d "$OUT"
kotlin -classpath "$OUT:$COROUTINES_JAR" tk.glucodata.MeterExportTest
```

In the delegated environment no Java/Kotlin launcher was on PATH. A standalone JDK was downloaded into scratch; cached Kotlin 2.4.20 compiler/stdlib plus the app's coroutines 1.10.2 executed the suite via:

```sh
python3 "$TMPDIR/run-meter-tests.py"
git diff --check
```

The temporary runner/JDK live outside the repository. App/SDK compilation and API36 synthetic cross-app instrumentation are owned by the parent task, not this host suite.

## Source-aware RED → GREEN progression

The previous schema-1 full-set-absence cleanup was withdrawn: pruning or replacement is not a user deletion. The safety redesign used the real host runner at each step:

1. RED `Schema2 with two genuine source inventories must supply retained blood readings` → GREEN strict inventory parser plus the unchanged `snapshot(raw): List<MeterExportReading>?` status/count surface.
2. RED `Pruned source prefix is not evidence of deletion; retain previous ownership` → GREEN durable source memberships, union-on-observation, membership-bearing crash intents, v2 ledger codec, and all-members-complete deletion gate.
3. RED `Corrupt coverage fixture 0 must not form deletion proof` (one blood row in zero raw positions) → GREEN row count bounded by the source's `[firstPos,lastPos)` span as well as the available payload. `complete=1` with nonzero first position, missing/placeholder sources, invalid epoch/UUID, duplicate source IDs, malformed counts, nonfinite values, and trailing/partial data also fail closed.

Expanded regressions verify database ID/either UUID half/epoch replacement without rebaselining; late-discovered identical copies persist two memberships and either pruned/missing copy vetoes cleanup; source enumeration order changes neither IDs nor scope; genuinely empty original sources can prove deletion; known complete-source deletion continues alongside deferred partial-source cleanup; v1 ownership and uncertain intents retain permanent unknown proof; lost responses followed by deletion, source replacement, full edit, or partial edit recover without unsafe cleanup. Codec tests include signed 64-bit token round trips and orphan, duplicate, foreign-namespace, malformed, or legacy-schema provenance refusal.

The existing 1001-row/500-size batch, permission revoke/regrant, failed intent and acknowledgement persistence, restart idempotence, owned-only edit/delete, unrelated/CGM preservation, unit/label binding, settled-empty rebind, and serialized/conflated worker cancellation tests remain in the same executable suite. The helper now generates genuine two-source schema-2 fixtures, not schema-1 lists.

## Explicit recheck and completion regression

`MeterExporter.sync(raw, republishCurrent=true, onAcknowledged=...)` re-submits **current eligible saved rows** using their existing client IDs. Automatic polling remains incremental. An intact ownership ledger cannot prove that records remain in the provider after independent deletion. The RED regression cleared only the fake provider while retaining the serialized ledger: the old manual path returned without restoring any rows. The explicit-recheck path restores them; repeated calls keep the same records and preserve source membership/deletion proof. Legacy unknown provenance remains unknown. Unit/label/permission/inventory guards still precede writes, and retained obsolete IDs are not republished from guessed data.

The same durable intent mechanism covers already-owned rechecks. Lost responses and failed receipt persistence retain uncertain payloads for restart replay. `onAcknowledged` fires only after the provider insertion acknowledgement **and** durable ledger receipt, including an idempotent upsert of an already-owned ID. Status no longer equates an unchanged ownership set with no acknowledgement. An acknowledgement does not prove new insertion, independent provider readback or downstream Google Health display.

`MeterExportForeground` coalesces concurrent explicit callers onto a Job spanning real work, rather than only request scheduling. `HealthConnection.requestSync` awaits the instance-scoped explicit export and final observation; disable/stop cancels both foreground gating and instance work. Host regressions cover active-until-complete, shared completion while busy, cancellation/reuse, provider-only loss, repeated rechecks, permission/config/inventory refusal, uncertain responses and failed durable receipts. These host tests pass; combined APK/emulator acceptance remains required.

## Exact API handoff

- Preserved `MeterExportPolicy.snapshot(raw: LongArray?): List<MeterExportReading>?`, reading payload/client-ID format, `MeterExportLedger.load/save`, `MeterExportStore`, `owned`, and `intent`.
- Added `MeterExportPolicy.inventory(raw: LongArray?): MeterExportInventory?`, exposing `unit`, `bloodLabel`, `sourcesCoverage: Map<MeterExportSource,MeterExportCoverage>`, `memberships: Map<MeterExportReading,Set<MeterExportSource>>`, and sorted `readings`.
- Added `MeterExportSource(databaseIdent, uuidHi, uuidLo, epoch)` and `MeterExportCoverage(firstPos,lastPos,complete)`.
- Added defaulted `MeterExportState.memberships: Map<String,Set<MeterExportSource>>`, covering both owned IDs and uncertain intents. Missing key means unknown proof; it is never upgraded/reset.
- Added `MeterExporter.Result.CLEANUP_DEFERRED`: valid retained-row backfill/recovery and eligible exact-ID cleanup finished, but obsolete owned IDs remain because at least one membership is unknown, missing/replaced, or incomplete. It is not a total failure and not fully reconciled success. Existing results are unchanged. Null/malformed inventory remains `INCOMPLETE` without ledger/store writes.
- Snapshot schema 1 is deliberately rejected as incomplete; ledger schema 1 remains accepted conservatively. The Android adapter requires no interface changes.

Only synthetic host code was compiled/executed for this change. No Android build, native integration claim, live account, device, raw history, patient data, cloud write, commit, or push was performed.
