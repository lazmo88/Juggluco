# Main-menu tile sizing regression

## Scope / implementation

`Menus.showNow()` inflates `res/layout/material_menu.xml`; this checkout has no
programmatic main-menu grid factory. Its cells are weighted MaterialButtons in
horizontal, `wrap_content` LinearLayouts. Previously their individual
`wrap_content` heights could differ when one caption wrapped.

Only the main menu now selects `MenuTile` / `MenuTimeButton` style overrides.
`match_parent` child heights opt into Android LinearLayout's built-in
`forceUniformHeight` second measurement pass after weighted widths and natural
content heights have been measured. Each row grows to its tallest child.
Two logical lines are reserved, and more lines remain allowed. No stored pixel
height, global-layout callback, changed column count, new colours, or shared
style change. `Menus.java`, its handlers, theme check marks and scroll restoration
are untouched, including pre-existing uncommitted FullGraph work.

## Host test (executed)

From repository root:

```sh
python3 Common/src/test/menu_tile_contract_test.py
```

This is an **offline resource contract**, not a claim to Android rendering.
The first test failed before the resource edit with `64 != 88`, using synthetic
natural content sizes and the actual XML layout-height policy. It checks all
20 cells are wired into the framework equal-height pass, reserve two lines,
allow unbounded text, and keep widths/weights/icons and Appearance styles.

## Android measurement assertions (prepared; NOT executed here)

`Common/src/androidTestMobile/java/tk/glucodata/MenuTileLayoutAssertions.java`
provides real `View.measure()` / `View.layout()` assertions against the actual
inflated menu. It has no JUnit dependency or automatic runner registration.
Invoke it from the parent's existing mobile instrumentation harness, on the
main thread, for example:

```java
instrumentation.runOnMainSync(() ->
    MenuTileLayoutAssertions.verify(instrumentation.getTargetContext()));
```

It checks same-row rectangle top/bottom and weighted widths, full text/layout
height, retained icons and column counts in Light/Dark, LTR/RTL, font scales
1/1.5/2, and widths 320/480/800/320 dp on the same view. It then grows a synthetic
caption beyond two lines, shrinks it again, and hides the optional Photo row.
No patient records, screenshot data or native initialization are involved.

On the final app, the parent should additionally check open-menu rotation /
split-screen resizing and theme switching at a nonzero scroll position, plus
unchanged System/Light/Dark selection marks and action dispatch. Those behaviors
are outside this detached resource measurement fixture.

This worker did not run any Android build, emulator or device write. Local
constraints: `java` and `javac` are absent from PATH; neither `ANDROID_HOME` nor
`ANDROID_SDK_ROOT` is set; local.properties points to an unavailable workstation
SDK directory. The parent owns the final build/UI run. There is
therefore no honest local Android passing-test result or connected-test command
until the parent wires these assertions into its runner.
