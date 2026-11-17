# Health Connect status and local activity

## Host core test (no Android, patient data or provider access)

```sh
OUT="$TMPDIR/health-status-tests"
mkdir -p "$OUT"
kotlinc Common/src/mobile/java/tk/glucodata/HealthConnectStatusCore.kt \
  Common/src/test/java/tk/glucodata/HealthConnectStatusTest.kt -d "$OUT"
kotlin -classpath "$OUT" tk.glucodata.HealthConnectStatusTest
```

The plain Kotlin executable tests actual state transitions and persisted encoding: disabled and unavailable providers, denied/granted permission, coalesced busy requests, success versus no-op acceptance time, restart recovery from running, cancellation, safety pause, cleanup-deferred acceptance, categorized errors, persisted log bounds, malformed state and adversarial log text. RED failures were observed before implementation, including restart permanently busy and missing permission activity, then GREEN with the production core.

The delegated host has no Java/Kotlin command on PATH. The existing cached Kotlin compiler plus scratch JDK ran the same files using `python3 "$TMPDIR/run-health-status-tests.py"`; no release API test hooks were introduced.

## Public routes (Java-compatible)

- `HealthConnectPage.show(MainActivity)` opens the Material 3 status page. MeterList and each MeterConfig link here. Parent owns replacing/enhancing the ExchangePage route.
- `HealthConnection.Companion.requestSync(MainActivity)` is foreground-only; does not enable a disabled export. Missing permission uses the existing SDK ActivityResult launcher. Busy presses do not queue an additional run.
- `setEnabled(MainActivity, boolean)` explicitly changes the native toggle and starts/stops export. Enabling may request permission as a foreground action.
- `refreshStatus(Context)` only observes provider, permission and exporter-owned ledger/snapshot counts. Safe for Activity onResume; the page already registers/removes its lifecycle observer.
- `openSettings(MainActivity)` uses the pinned SDK `HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS` (its JVM getter is named `getHealthConnectSettingsAction`, but that is not the Kotlin source name). The existing Play provider-install route is used only after a foreground settings action. This API expression was compiled against actual 1.1.0-rc03 SDK classes.
- Existing init/stop/permissionResult/writeAll/meterExportWarning entry points remain. Startup and the 30-second process-lifetime worker never request permission or launch a provider installation screen.

## Counts and timestamps

Eligible rows come from `MeterExportPolicy.snapshot(raw)`, not fixed native header offsets. Provider-acknowledged count is `MeterExportAndroid.load().owned.size`: a local exporter-ledger count, not a provider query. Pending is the distinct union of unacknowledged snapshot client IDs and durable intent IDs; unknown snapshot means unknown pending. Retained owned rows after source pruning remain counted and explicitly disclosed.

The last observed provider-acceptance time advances only when the adapter-owned ledger gains acknowledged IDs, even after partial failure or cleanup-deferred completion. A no-op does not invent a new acceptance. This is an observation at end-of-run, not an exact provider batch-return timestamp; the page says so. Old runs predating status persistence have no recoverable acceptance timestamp and remain Unknown.

Status/activity persistence is a separate bounded preference (`health_connect_status_v1`), never the ownership ledger. Only fixed enums, nonnegative counts, and action times are serialized. Exception messages/classes, values, reading timestamps, source IDs and meter IDs cannot enter its typed log. It never clears ownership or requests provider read permission.

## Parent verification still required

Build exact combined APK on Legion and exercise on the assigned synthetic API36 emulator: MeterList → page, MeterConfig → page, ExchangeData → page, Back, disabled Sync now, enable/grant/deny, repeat busy presses, Refresh and top pull-down, settings return/onResume, no-op time unchanged, safety/cleanup-deferred outcomes, restart log retention, and crash log. Verify actual provider records independently as the parent planned. No phone or Google account access is part of this work.
