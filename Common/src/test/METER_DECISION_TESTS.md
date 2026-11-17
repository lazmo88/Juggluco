# Shared meter decision regressions

These Java tests use synthetic identities and packets only. They do not open patient sources, connect to a meter, call native code, export data, or write a device. The binding suite writes private synthetic host files and can round-trip a blob supplied by the separate native test runner.

## Run the real production helper on a host JDK

From the repository root:

```sh
JDK=/home/hermes/dev/scratch/herald-tc/gradle-home/jdks/jetbrains_s_r_o_-25-amd64-linux.2
OUT=/var/lib/hermes/cache/scratch/meter-decision-tests
mkdir -p "$OUT"
"$JDK/bin/javac" -Xlint:all -d "$OUT" \
  Common/src/mobile/java/tk/glucodata/MeterDecisionState.java \
  Common/src/mobile/java/tk/glucodata/MeterImportBinding.java \
  Common/src/test/java/tk/glucodata/MeterDecisionStateTest.java \
  Common/src/test/java/tk/glucodata/MeterBindingPersistenceTest.java
"$JDK/bin/java" -ea -cp "$OUT" tk.glucodata.MeterDecisionStateTest
"$JDK/bin/java" -ea -cp "$OUT" tk.glucodata.MeterBindingPersistenceTest
```

`MeterDecisionState` is used directly by `MeterAsk.State`: receive routing, visible review identities, persisted presentation, individual/bulk drafts, decision serialization, and durable-before-import commit are not tests-only implementations.

## Observed RED → GREEN

Each feature slice was exercised with the command above before its implementation:

- RED: `Later/cancel must not re-prompt already presented pending` → GREEN after durable presentation markers.
- RED: `draft Add must persist immediately` → GREEN after transactional draft persistence.
- RED: `Save choices must succeed` → GREEN after explicit decisions are durably committed before import; untouched rows remain pending.
- RED: `storage failure must block fallback saving` → GREEN after false/throwing writes latch fail-closed routing.
- RED: `on-disk draft survives restart` → GREEN after the review/legacy-decision codecs and wire identity implementation.
- RED: `Exclude all drafts persist atomically` → GREEN after atomic bulk draft selection.
- RED: `committed Add cannot offer unsafe Exclude/undo after uncertain native import` → GREEN after committed choices became immutable.
- RED: `explicit show reviews presented/deferred pending` → GREEN after explicit/automatic review identity selection was shared with real UI code.

The final suite additionally covers 2,505 exclusions through actual text serialization/reload (no eviction or excluded raw payload), same sequence with changed base time, cross-device separation, resync/restart, newly unseen arrivals without redisplaying deferred rows, opt-out exclusion/pending protection, interrupted native-import callback, and failed post-import cleanup.

Android restart testing exposed an additional serialization defect: the empty review string written as `1\n` was read back with length six and a second line, setting `loadFailed` and blocking new arrivals. Review output now has no trailing newline, accepts version-only `1`, and tolerates whitespace-only lines in legacy metadata while still rejecting malformed nonblank choices. The regression was RED at `empty review must not end in a newline in Android preferences`; all nine host tests pass after the change. Actual Android restart verification remains a separate release gate.

## Root cause and compatibility

Previously `resume()`/`synced()` looked only at pending count. Later used a temporary skip set; the next resume rebuilt that set. Each dialog also constructed a new unchecked `boolean[]`, losing its selection. There was no durable presentation or draft layer. The old `MAX_DECIDED=2000` evicted exclusions, the `anyOn`/`!st.on` receive shortcuts bypassed exclusions when disabled, and exception fallback returned false to a caller that immediately saved the record.

The existing `on|device`, `pending|device`, `decided|device` encodings and public APIs are preserved. New `review|device` metadata separates presented identities and uncommitted draft choices. Old valid decisions load unchanged. Exclusions retain identity only, without an eviction cap. Unreadable state is held and is not overwritten with an incomplete map. `bindings|device` now retains immutable accepted-import conversion contexts even after pending cleanup. New explicit Add consent and context are persisted atomically before import. Existing committed Add **without** a binding is quarantined, not automatically rebound/reinserted or silently cleaned; the UI surfaces that individual recovery review is needed.

Additional page integration accessors: `excludedCount(name)`, `draftCount(name)` (uncommitted drafts only), and `storageProblem(name)`; existing `pendingCount`, `onChanged`, `show`, `enabled`, `setEnabled`, `resume` remain.

## Conversion-binding extension

The decision suite now reports **12 passed**, including the observed RED `legacy unbound Add must defer, not reinterpret` before the migration guard. `MeterBindingPersistenceTest` reports **4 passed**: version/checksum/bounds/raw-identity validation, real host-file serialization/restart with first-binding retention after uncertain and completed imports, failed binding+consent commit and read-only preparation failure, and pending/completed legacy Add quarantine without duplicate writes or payload loss. Its importer is a controlled callback, not a pretend native store. When invoked by the native runner with a native-generated binding path, it round-trips the actual native bytes through production codecs/state before a fresh native process consumes them. See `cpp/METER_IMPORT_TESTS.md` for complete exact commands and native ASan/UBSan/mmap evidence.

## Verification limits

A scratch-only API compile of the actual `MeterAsk.java` against Android 37 and Material 1.12.0 succeeds (`python3 /var/lib/hermes/cache/scratch/compile-meter-receipt-api.py`). That compile uses app/R/Natives stubs outside the repository. It is not an APK build or Android UI/runtime acceptance test. Build/emulator ownership remains with the parent; no remote build or device write was performed.

Native cursor/older-record acceptance and import receipts are now covered separately in `cpp/METER_IMPORT_TESTS.md`. The native ABI has an explicit accepted-import receipt API; the ordinary GATT boolean remains a just-now notification, not a receipt. Once Add is durably committed, a crash/cleanup failure may leave a retryable pending payload even if native import already occurred; its choices are locked and the UI explicitly does not offer undo. Previously evicted exclusions cannot be reconstructed. Identity-only decision history now grows without silent expiry; it may eventually warrant a transactional database, not pruning exclusions.
