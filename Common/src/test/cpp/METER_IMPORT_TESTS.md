# Accepted meter import — immutable conversion receipts

**STATUS: DRAFT / parent release gates still required.** The proved conversion-context replay blocker is now GREEN in source/host tests: a +1 second current meter offset produces receipt **2**, with **one** actual Num row. No proximity suppression was added. Legacy committed Adds without conversion provenance are deliberately quarantined, not automatically recovered.

No APK, real meter, Health Connect account, patient source, remote host, emulator, or screenshot was accessed.

## Immutable contract

- `GlucoseMeterPrepareAccepted(index, raw)` is **read-only**: it returns a conversion binding, without a Num write, cursor mutation, flush, calibration, or notification. Preparation and a draft are not consent.
- Only a **new explicit Add committed with Save choices** prepares a binding. `MeterDecisionState.commit` synchronously persists Add consent, pending raw packet, and binding in the **same SharedPreferences Editor commit** before calling the native importer. Failed preparation or failed preference commit cannot reach the importer.
- `bindings|device` stores versioned canonical hex, keyed by the existing sequence + seven base-time-byte identity. It is independent of `pending|device`; successful pending cleanup **never deletes a binding**. `decided|device`, pending, and review encodings remain readable.
- The immutable native `JMI1` format is big-endian: magic [0..3], corrected uint32 seconds [4..7], exact stored float bits [8..11], blood label index [12..15], configured unit [16..19], original epoch seconds [20..23], first meter correction int32 [24..27], zero-padded device name [28..66], address [67..72], zero-padded label bytes [73..84], raw length [85], entire raw packet [86..], then a four-byte FNV-1a corruption checksum. Total length is 104..154; raw length 14..64. Checksum is corruption detection, **not authentication or import authorization**.
- Native application consumes the stored corrected timestamp, stored float bits, and blood label. It **never recalculates a target timestamp using the current meter offset or timezone**. Raw-value conversion is checked only for consistency against the frozen unit/value, not used to invent a replacement tuple.
- Both Java and native validate format/version, length, checksum, timestamp/finite-positive-value/unit/label bounds, and raw identity. Java validates device-name scope before persistence; native also validates the full packet, device name/address, current configured unit, blood label index and label bytes. Offset-only changes are allowed. Unit changes, label changes/renames, changed payload under the same identity, wrong meter/index, corruption, or inconsistent fields reject without reinterpretation or pending cleanup.
- `GlucoseMeterImportAccepted(index, raw, binding)` returns **0 rejected/uncertain**, **1 persisted**, **2 already present**. Only 1/2 permit pending cleanup. Retry and completed remembered-Add replay consume the original persisted binding; neither invokes preparation again.

## Legacy migration is deliberately bounded

Existing committed Add consent with **no binding** is not fresh consent, even when Save choices is pressed again. A prior successful or uncertain write could already exist under an unknown old offset/unit. Both retained pending Adds and completed Add identities reappearing from meter replay are therefore quarantined:

- do not prepare a new conversion, invoke insertion, clear the decision ledger, remove pending, or infer a duplicate by proximity;
- retain consent and raw pending/replayed packet through restart;
- keep committed choices locked (no unsafe Exclude/undo);
- mark `storageProblem` and show the dedicated legacy-review explanation in explicit review and committed pending rows.

No automatic exact-row binding is attempted: the old raw identity alone cannot prove its former converted tuple. Individual recovery review is a necessary limitation, **not a new recovery UI delivered here**. `61b6570d` identifies the prior APK's SHA-256 prefix, **not a Git commit**. A failed `git show 61b6570d:.../MeterAsk.java` establishes neither APK availability nor migration coverage. The host migration fixtures exercise the existing `decided|device` Add plus pending encodings read by current production code; they do not prove upgrade from that APK.

## Storage, locking, and ordinary GATT preservation

- Explicit imports ignore the ingest high-water timestamp for storage admission. Exact `(frozen corrected timestamp, stored value, blood label)` lookup examines actual native Num rows, including all equal-time rows. Distinct authorized timestamps/values/types remain distinct; existing calibration exclusion bits are not changed.
- Existing `Numdata::numsaveonly` insertion is shared through its caller-locked body, not reimplemented. `numsaveonlyLocked(... exact=false)` preserves ordinary proximity semantics; explicit imports use exact mode.
- Receipt durability flushes Libre IDs, Libre change list, change times, then populated Num mapping/header positions with synchronous `msync`. Already-present retries flush again. A failed flush can leave an authorized visible row; there is no unsafe rollback, and retry uses the exact frozen tuple.
- Reviewed import lock order is **MeterAsk Java lock -> meterImportMutex -> Numdata::nummutex**. The accepted-import path and participating ordinary-save/clock paths take the meter lock; this is **not blanket synchronization of every meter accessor/setter**. Legacy setters including `GlucoseMeterSetLastPos` and `GlucoseMeterSetDeviceAddress` remain outside that mutex, so whole-runtime meter concurrency is not comprehensively proved. Exact lookup/insertion/receipt flush share the Num writer/reset mutex and mirror-repair checks. JNI copies arrays with `GetByteArrayRegion`, never holding a VM-critical array across waits or flushes.
- Ordinary `GlucoseMeterSave` keeps its legacy boolean (**saved and measured just now**, not a storage receipt), proximity policy, current-measurement calibration, and real RACP behavior. Older records and failed saves cannot rewind/advance its cursor. Explicit accepted import never calls `addCalibration` or synthesizes RACP success. Confirmed cursor updates require a strictly newer frozen timestamp; sequence wrap is preserved.

## Exact verification commands

From `/home/hermes/projects/health/apps/juggluco`:

```sh
export JDK=/home/hermes/dev/scratch/herald-tc/gradle-home/jdks/jetbrains_s_r_o_-25-amd64-linux.2
export METER_BINDING_JAVA_CLASSES=$(mktemp -d "$TMPDIR/meter-java.XXXXXX")
"$JDK/bin/javac" -Xlint:all -d "$METER_BINDING_JAVA_CLASSES" \
  Common/src/mobile/java/tk/glucodata/MeterDecisionState.java \
  Common/src/mobile/java/tk/glucodata/MeterImportBinding.java \
  Common/src/test/java/tk/glucodata/MeterDecisionStateTest.java \
  Common/src/test/java/tk/glucodata/MeterBindingPersistenceTest.java
"$JDK/bin/java" -ea -cp "$METER_BINDING_JAVA_CLASSES" tk.glucodata.MeterDecisionStateTest
"$JDK/bin/java" -ea -cp "$METER_BINDING_JAVA_CLASSES" tk.glucodata.MeterBindingPersistenceTest
python3 Common/src/test/cpp/meter_import_contract_test.py --probe-context-drift
python3 Common/src/test/cpp/meter_import_contract_test.py
python3 /var/lib/hermes/cache/scratch/compile-meter-receipt-api.py
python3 Common/src/test/cpp/native_reset_contract_test.py
git diff --check -- Common/src/main/cpp/meter/java.cpp \
  Common/src/main/java/tk/glucodata/Natives.java \
  Common/src/mobile/java/tk/glucodata/MeterAsk.java \
  Common/src/mobile/res/values/m3_strings_meterask.xml
```

Observed: **all exit 0**. Java decision suite: **12 passed**; binding/real-host-file state and codec suite: **4 passed**. Android/Material API compile: **0** (app/R/Natives stubs; not an APK). Existing native reset suite passes unchanged.

The native runner compiles extracted production JNI/Num insertion bodies plus real binding headers with `g++ -std=c++20 -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer ... -pthread`. It uses actual `MAP_SHARED` synthetic files, real Num/Libre-ID layouts/header offsets, insertion/search/movement/change-time methods, and real meter decoding/constants. No sanitizer diagnostics were emitted.

In addition to previous old-after-new, exact-near/equal-time distinctions, units, wire/meter offsets, exclusion preservation, capacity/input rejection, cursor/sequence wrap, ordinary boolean/calibration, 12-writer locking, and auxiliary-file persistence cases, it checks:

- read-only preparation and no side effects on all rejected binding/config cases;
- +1 second offset replay: `CONTEXT DRIFT: replay receipt=2 actual Num rows=1`;
- every byte corrupted, inconsistent fields with recomputed checksums, wrong identity/payload/device, current unit/blood/label rename;
- separate **native processes** for each of four uncertain flush boundaries and a completed import: binding is fsynced before the initial native write; a fresh process loads it and reopens actual mapped rows, then replays at +1 and +3601 seconds with row count still one;
- when `METER_BINDING_JAVA_CLASSES` is set as above, native-generated bindings go through the **production Java codec/state** and real host text files, including pending restart, completed cleanup/replay, failed consent+binding commit and legacy quarantine tests, then are restored byte-for-byte for the next native process to consume.

## Proof boundaries / remaining release gates

A bounded independent source review reported no blocking defect in the reviewed frozen-binding import paths. It independently reported 12 Java decision tests, 4 binding/persistence tests, and the mapped-store ASan/UBSan suite passing, including separate-process replay after four injected flush failures. This is host/source evidence only, not full-APK, Android preference-runtime, upgrade, physical-meter or Health Connect end-to-end acceptance.

- JNI platform operations, Settings lookup, notifications, mirror-availability setup and host file wrapper are harness shims. Production binding validation, JNI application bodies, actual native row storage/dedupe/flush paths and Java state/codecs execute, not a replacement in-memory dedupe model.
- Host text-file durability tests use the real production state and preference-value codecs with a file-backed persistence adapter. They do **not** prove Android SharedPreferences filesystem/runtime behavior, full application linkage, real meter behavior, UI acceptance, or HC end-to-end export. Parent owns release/UI/HC verification. No commit, push or device action was performed.
- Existing in-place Num mappings are not a transactional multi-file WAL. Successful `MS_SYNC` is the receipt boundary; arbitrary power loss/filesystem corruption and cross-file crash-atomic rollback are not promised.
- Persisted contexts intentionally retain accepted-reading payload/provenance after pending cleanup and grow with accepted history. No silent pruning is permitted. Legacy recovery and any future retention migration need their own safe contract.
- Real GATT RACP `calibrateLast` and its existing asynchronous raw-pointer concurrency assumptions were not redesigned. Synchronous preference/Num flush responsiveness on the Bluetooth callback remains a separate concern.
- Previously silently dropped readings whose raw payload was already removed require meter replay; legacy unbound Add replay now surfaces the recovery limitation instead of risking a duplicate automatic insertion.
