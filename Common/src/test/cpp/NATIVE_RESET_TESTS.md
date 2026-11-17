# Native mirror reset safety (synthetic host tests)

## Contract

- Local metadata bytes 200..231 hold UUID+epoch. Bytes 232..235 separately hold the HC reset-pending marker. Neither is sent in mirror row packets.
- `numbackupinit` commits pending+advanced scope in one synchronous header flush before any identity/position mutation. App rows stay present; snapshots and outbound updates refuse the unfinished replica. Identifier-write failure may safely leave pending latched.
- Pending receipt uses `mirrorvalidate::numbers(previous=first)`, so an accepted nonempty packet covers every row in `[first,last)`. Empty completion requires an actual accepted `receivelastpos(first)` command. Raw/Wear setters cannot clear pending.
- Before either marker is cleared, `finishMirrorRepair` synchronously flushes the page-aligned nums prefix, including rows and positions, then synchronously clears the 256-byte header. Both receipt callers propagate failure, and unknown markers cannot be cleared.
- Startup repair synchronously writes new UUID/advanced epoch plus its repair journal before auxiliary truncation or logical-coverage changes. Its final byte20 clear preserves byte232 pending.
- Schema2 keeps the existing eight-field source layout but emits logical source slots (`getindex()`: mirror=0/local=1), not raw header identities, which can both be zero on fresh installs. UUID+epoch remains incarnation proof. Existing ledger scopes are never rewritten or rebaselined.

## Commands

From the repository root:

```sh
python3 Common/src/test/cpp/native_reset_contract_test.py

g++ -std=c++20 -Wall -Wextra -Werror -I Common/src/main/cpp \
  Common/src/test/cpp/healthsource_test.cpp -o "$TMPDIR/healthsource-test"
"$TMPDIR/healthsource-test"
g++ -std=c++20 -Wall -Wextra -Werror -I Common/src/main/cpp \
  Common/src/test/cpp/healthblood_test.cpp -o "$TMPDIR/healthblood-test"
"$TMPDIR/healthblood-test"
```

The reset runner compiles with ASan+UBSan. It extracts the actual Numdata/JNI method bodies, including reset, receive/validation, durable completion, snapshot helpers, raw pointer/row access, outgoing update and all six raw setters. Android, network transport, auxiliary bookkeeping and mapping availability are harness dependencies; clinical data/header live in real synthetic MAP_SHARED files. Tests use msync, fsync, reopen, deliberate flush failures, real startup-trim failure, and an abrupt child `_exit` without destructors. This is not full Android translation-unit/NDK or APK verification, nor a hardware torn-sector/power-loss simulation.

For actual native-to-Kotlin coverage, retain snapshots and run the production exporter against them:

```sh
mkdir -p "$TMPDIR/native-reset-snapshots" "$TMPDIR/native-reset-kotlin"
python3 Common/src/test/cpp/native_reset_contract_test.py "$TMPDIR/native-reset-snapshots"
kotlinc Common/src/mobile/java/tk/glucodata/MeterExportPolicy.kt \
  Common/src/mobile/java/tk/glucodata/MeterExporter.kt \
  Common/src/test/cpp/NativeResetTransitionTest.kt \
  -classpath "$COROUTINES_JAR" -d "$TMPDIR/native-reset-kotlin"
kotlin -classpath "$TMPDIR/native-reset-kotlin:$COROUTINES_JAR" \
  tk.glucodata.NativeResetTransitionTest "$TMPDIR/native-reset-snapshots"
```

This environment used cached compiler/JDK runners (no Java/Kotlin on PATH):

```sh
python3 "$TMPDIR/run-native-reset-transition.py"
python3 "$TMPDIR/run-meter-tests.py"
python3 "$TMPDIR/run-health-status-tests.py"
python3 Common/src/test/cpp/native_plot_contract_test.py
git diff --check
```

These scratch runners are environment-specific, not required by the portable commands above.

## Observed RED → GREEN

1. Before reset implementation, `python3 Common/src/test/cpp/native_reset_contract_test.py` failed at `unfinished initialization must not expose retained rows in a complete new epoch`. After the durable HC marker/completion change it passed.
2. After adding startup regression but before changing startup repair, the same command failed at `startup must persist new scope BEFORE auxiliary trim or coverage rewrite`. The test deliberately makes `libreids.dat` a directory, observes the real trim failure, and reopens nums before retry. New scope ordering and byte232 preservation made it pass.
3. Before logical-slot output, compiling/running `healthblood_test.cpp` failed at `out[4]==1&&out[14]==0` with both raw identifiers zero. Logical-slot output made it pass; the same fresh-header shape is consumed successfully by the actual Kotlin parser/exporter.

Additional GREEN checks: retained app rows while pending, blocked snapshot/outbound, suffix-only packet refusal without mutation, full validated replacement, prefix/header msync-failure negative ACKs followed by blocked reopen, confirmed-empty completion, unknown marker refusal, raw setters at first=last=0, real startup creation of legacy UUID, all-span sender packet with last=end, and actual +256 row-only payload. The native-to-Kotlin test verifies that first observation during initialization exports nothing and previously observed source memberships do not authorize deletion after reset. Existing Kotlin meter suite still verifies multi-source union/veto and all observed membership proof.

No source/raw patient files, accounts, phone, remote host, commit, push or APK release were involved.
