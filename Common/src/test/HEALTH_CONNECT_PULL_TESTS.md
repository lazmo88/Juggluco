# Health Connect page pull-to-refresh regressions

## Local executable tests

```sh
python3 Common/src/test/hc-pull/run.py
```

This standalone host harness keeps Robolectric out of the application dependency graph. It uses the existing shared build cache (override `HC_TEST_CACHE`, `JAVA_HOME`, `ANDROID_JAR` if needed), the production `androidx.core:core:1.12.0` AAR pinned in `Common/build.gradle`, Robolectric 4.17 and Android API35. The core AAR and Robolectric Android runtime may be fetched over HTTPS if not cached. Outputs go only beneath `$TMPDIR/hc-pull-tests`.

Actual result: **20 tests passed**. JDK17 emits Robolectric capability warnings for unselected API36/37; tests explicitly select API35. This is real Android `ViewGroup.dispatchTouchEvent` execution under Robolectric, not on-device or emulator instrumentation, APK acceptance, provider verification, or visual review.

Tests use a synthetic Android Activity, a real NestedScrollView and clickable Button; no MainActivity, JNI, medical data, screenshot, meter, provider or phone is accessed.

- RED: the original scroll-view OnTouchListener received no child-owned DOWN; the test asserting one refresh callback failed with `expected 1, was 0`. The final suite retains an executable old-listener control demonstrating zero callbacks, while the dispatch wrapper produces one.
- RED: a pull crossing the threshold but releasing back above it wrongly refreshed; release-coordinate handling fixes it.
- RED: accessible Sync plus a pull before completion produced two work callbacks; the shared page gate reduces this to one, without queueing a follow-up.
- Final event-dispatch coverage: child-owned DOWN, child CANCEL/no accidental click, explicit CANCEL/reset, final release below threshold, short pull, horizontal direction lock, ordinary upward scrolling, drag starting away from the top, descendant disallow-intercept, busy pull, multi-pointer cancellation, normal tap and actual Android accessibility ACTION_CLICK.
- Combined wrapper/action-gate coverage: enabled pull requests saved-reading export, disabled pull only observes status without changing enablement, repeated real dispatched pulls coalesce until completion.
- Button/gate coverage: accessible Sync shares the pull gate; Refresh status remains observation-only even when enabled; backend-running blocks new requests; exact real completion releases the gate; duplicate/stale completions cannot release a newer request; closing the page suppresses late callbacks.

## Page/backend completion contract (parent-owned API)

The page calls the existing parameter signatures:

- `HealthConnection.refreshStatus(context): Job`: already implemented by parent. Job ends after the actual observation/count check, even if StateFlow values remain equal. No inserts or permission prompts.
- `HealthConnection.requestSync(activity): Job` is now implemented by the parent. Its foreground gate returns the actual request Job and awaits the instance-scoped explicit export plus final observation, or the permission/unavailable/disabled outcome. Other callers can ignore the return value. `MeterExportForeground` host regressions verify real completion, busy-call coalescing, cancellation and reuse. The UI child did not edit HealthConnection, StatusCore, worker or exporter files; combined APK/runtime verification remains outstanding.

A scheduling-only job would reopen the local busy gate before RUNNING is published and allow another pull to queue a second request. A completed observation job cannot by itself prove a separately queued export finished. The UI awaits this real completion boundary rather than using a delay, guessing from unchanged state, or inventing a successful result.

The page immediately displays a sticky progress spinner, pull/release hints and saved-reading/status-specific wording. On actual check completion it updates a local action timestamp (second precision) and the last-completed-status-check row; manual results include the provider/permission/export outcome. No new status persistence or ledger changes are introduced. Export acknowledgement wording does not imply new insertion, provider readback or Google Health display.

## Remaining parent acceptance

### Activity-destruction cleanup regression

`python3 Common/src/test/hc-pull/lifecycle_scope_test.py` extracts the production page's observer-registration/close block verbatim and executes it with real cached JVM `LifecycleRegistry`, `StateFlow` and coroutine scopes. Page-close and refresh-action collaborators are synthetic fixtures; this is not complete Activity/APK instrumentation. No data or backend is accessed.

The original observer reproduced `Destroyed page collector still subscribed`. The fixed page uses one idempotent cleanup path for explicit close and `ON_DESTROY`, cancels only its page scope, closes its local action gate and removes its observer. The test passes across three destroy/recreate cycles: no retained subscriptions or later detached render, independent backend work remains active, and repeated close stays safe. The 20 Robolectric gesture/action tests also still pass.

Build the exact combined APK with the implemented requestSync Job contract. Instrument the real page and assert a changed completion timestamp/callback/provider write receipt, not only a successful swipe command. Cover a drag starting on a card/button, missing grant, disabled export staying off, Refresh status causing no provider write, repeated Sync/pull while busy, ordinary scroll/tap, accessibility activation, toolbar/system Back and page-close while running. Check crash logs and actual provider acknowledgement independently. No real-device or downstream Google Health display claim is supported by these host tests.
