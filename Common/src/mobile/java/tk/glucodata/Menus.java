/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Sun Oct 08 20:48:20 CEST 2023                                                */


package tk.glucodata;

import android.content.Context;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.FrameLayout;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.REQUEST_BARCODE;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.Natives.getshowscans;
import static tk.glucodata.Natives.getsystemui;
import static tk.glucodata.Natives.setsystemui;
import static tk.glucodata.NumberView.smallScreen;

import tk.glucodata.settings.Settings;

/*
 * Phone menu.
 *
 * Upstream shows a 7x7 grid of plain buttons and rotates the whole activity
 * to landscape while it is open.  On tall phones that grid is hard to read and
 * the forced rotation makes it hard to find anything.  This version shows a
 * Material 3 bottom sheet in the current orientation, grouped by task, and
 * adds a visible menu button on top of the graph so the menu can be found
 * without knowing which part of the graph to tap.
 *
 * Behaviour kept for the rest of the app:
 *  - Menus.on stays true while the menu, or a screen opened from it, is in
 *    use.  Screens that call "if(Menus.on) Menus.show(act)" on Back therefore
 *    return to the menu.
 *  - Closing the sheet (swipe down, Back, tap outside) sets Menus.on=false.
 *  - Actions that only change the graph close the menu and show the graph.
 */
public class Menus {
static public boolean on=false;
static private final String LOG_ID="Menus";

static private BottomSheetDialog sheet=null;
/* Set while the sheet is closed in order to open another screen; the menu is
 * then still logically "on" so Back from that screen returns here. */
static private boolean leavingForScreen=false;
/* A screen opened from the menu is showing: the phone dashboard stays out of the way. */
static boolean inScreen=false;
static private FloatingActionButton launcher=null;
static private ViewGroup launcherContent=null;
static private ViewTreeObserver.OnGlobalLayoutListener launcherListener=null;

static void newTask() {
    dismissSheet();
    removeLauncher();
    on=false;
    }

static void configurationChanged(MainActivity act, Configuration config) {
    /* The sheet follows the current orientation; nothing to force.
     * Android's dark theme may have been switched: follow it. */
    if(tk.glucodata.m3.ThemeMode.sync(act))
        themeChanged(act);
    }

/* Light/dark changed while the app is open: redraw the graph and rebuild the
 * dashboard, the menu button and an open menu in the new colours. */
static public void themeChanged(MainActivity act) {
    act.requestRender();
    final boolean menuOpen=sheet!=null;
    if(launcher!=null)
        attachLauncher(act);
    if(menuOpen) {
        /* Reopen the menu where it was, so the Appearance choice stays in view. */
        final View old=sheet.findViewById(R.id.jm_root);
        if(old!=null&&old.getParent() instanceof View)
            restoreScroll=((View)old.getParent()).getScrollY();
        leavingForScreen=true;
        dismissSheet();
        leavingForScreen=false;
        show(act);
        }
    else if(!inScreen)
        act.lightBars(!getInvertColors());
    }

static Context themed(MainActivity act) {
    final int theme=getInvertColors()?R.style.Juggluco_M3_Dark:R.style.Juggluco_M3_Light;
    Context ctx=new ContextThemeWrapper(act,theme);
    return DynamicColors.wrapContextIfAvailable(ctx);
    }

static private void dismissSheet() {
    final BottomSheetDialog s=sheet;
    sheet=null;
    if(s!=null) {
        try {
            if(s.isShowing())
                s.dismiss();
            }
        catch(Throwable th) {
            Log.stack(LOG_ID,"dismissSheet",th);
            }
        }
    }

/*
 * Menu button on top of the graph.  Called by MainActivity after the graph
 * has been set as content view.  Hidden while another screen is shown on top
 * of the graph.
 */
static public void attachLauncher(MainActivity act) {
    if(isWearable)
        return;
    removeLauncher();
    final ViewGroup content=act.findViewById(android.R.id.content);
    if(content==null)
        return;
    if(tk.glucodata.m3.ThemeMode.sync(act)) { // follow Android's dark theme
        act.requestRender();
        act.lightBars(!getInvertColors());
        }
    Dashboard.attach(act);
    final Context ctx=themed(act);
    final FloatingActionButton fab=new FloatingActionButton(ctx);
    fab.setImageResource(R.drawable.ic_jm_menu);
    fab.setSize(FloatingActionButton.SIZE_NORMAL);
    fab.setContentDescription(act.getString(R.string.jm_open_menu));
    if(android.os.Build.VERSION.SDK_INT>=26)
        fab.setTooltipText(act.getString(R.string.jm_menu));
    fab.setOnClickListener(v -> show(act));
    final float density=act.getResources().getDisplayMetrics().density;
    final int margin=(int)(16*density);
    final FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT,Gravity.BOTTOM|Gravity.END);
    params.setMargins(margin,margin,margin,margin);
    ViewCompat.setOnApplyWindowInsetsListener(fab,(v,windowInsets) -> {
        Insets bars=windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
        ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)v.getLayoutParams();
        lp.leftMargin=margin+bars.left;
        lp.rightMargin=margin+bars.right;
        lp.bottomMargin=margin+bars.bottom;
        v.setLayoutParams(lp);
        return windowInsets;
        });
    act.addContentView(fab,params);
    launcher=fab;
    launcherContent=content;
    /* Global layout also fires when an existing child changes visibility,
     * which a hierarchy listener would miss. */
    launcherListener=() -> updateLauncher(content);
    content.getViewTreeObserver().addOnGlobalLayoutListener(launcherListener);
    updateLauncher(content);
    ViewCompat.requestApplyInsets(fab);
    }

static private void removeLauncher() {
    final FloatingActionButton fab=launcher;
    final ViewGroup content=launcherContent;
    final ViewTreeObserver.OnGlobalLayoutListener listener=launcherListener;
    launcher=null;
    launcherContent=null;
    launcherListener=null;
    if(content!=null&&listener!=null) {
        ViewTreeObserver obs=content.getViewTreeObserver();
        if(obs.isAlive())
            obs.removeOnGlobalLayoutListener(listener);
        }
    if(fab!=null)
        Settings.removeContentView(fab);
    }

/* The button is only useful on the bare graph: hide it when another screen
 * has been added on top (Settings, sensor status, number entry, ...). */
static private void updateLauncher(ViewGroup content) {
    final FloatingActionButton fab=launcher;
    if(fab==null)
        return;
    boolean overlay=false;
    final int nr=content.getChildCount();
    for(int i=0;i<nr;i++) {
        View child=content.getChildAt(i);
        if(child==fab||child instanceof GlucoseCurve||Dashboard.isDashboard(child))
            continue;
        if(child.getVisibility()==View.VISIBLE) {
            overlay=true;
            break;
            }
        }
    if(!overlay&&inScreen&&sheet==null) {
        /* The screen opened from the menu was closed without going back
         * through the menu. */
        inScreen=false;
        Dashboard.update();
        }
    if(overlay) {
        if(fab.getVisibility()==View.VISIBLE)
            fab.hide();
        }
    else {
        if(fab.getVisibility()!=View.VISIBLE)
            fab.show();
        if(content.getChildAt(nr-1)!=fab)
            fab.bringToFront();
        }
    }

static public void show(MainActivity act) {
    on=true;
    if(isWearable||act.isFinishing())
        return;
    if(sheet!=null&&sheet.isShowing())
        return;
    showNow(act);
    }

/* Leave the menu for another screen opened by run.  Menus.on stays true. */
static private void toScreen(Runnable run) {
    leavingForScreen=true;
    dismissSheet();
    leavingForScreen=false;
    inScreen=true;
    run.run();
    Dashboard.update();
    }

/* Shortcuts on the phone dashboard: the same screens as from the menu, opened
 * without the sheet.  Menus.on stays false, so closing the screen returns to
 * the dashboard (updateLauncher clears inScreen). */
static private void fromDashboard(MainActivity act,Runnable run) {
    if(sheet!=null)
        return;
    inScreen=true;
    act.lightBars(!getInvertColors());
    try {
        run.run();
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"fromDashboard",th);
        inScreen=false;
        }
    Dashboard.update();
    }
static public void openSensors(MainActivity act) {
    fromDashboard(act,() -> bluediag.start(act));
    }
static public void openAlarms(MainActivity act) {
    fromDashboard(act,() -> tk.glucodata.settings.AlarmsPage.show(act));
    }
static public void openNewAmount(MainActivity act) {
    if(Natives.staticnum()) {
        help.help(R.string.staticnum,act);
        return;
        }
    var c=Applic.app.curve;
    if(c!=null)
        fromDashboard(act,() -> c.numberview.addnumberview(act));
    }
static public void openAmounts(MainActivity act) {
    var c=Applic.app.curve;
    if(c!=null)
        fromDashboard(act,() -> {
            Natives.makenumbers();
            act.requestRender();
            c.getnumcontrol(act);
            });
    }
static public void openMeters(MainActivity act) {
    fromDashboard(act,() -> {
        if(!PhoneUi.show("meters",act))
            bluediag.start(act);
        });
    }
static public void openStatistics(MainActivity act) {
    if(Natives.makepercentages())
        fromDashboard(act,() -> {
            act.requestRender();
            Stats.mkstats(act);
            });
    else
        tk.glucodata.m3.M3Page.message(act,act.getString(R.string.jm_stats_title),act.getString(R.string.jm_stats_nodata));
    }

/* Leave the menu and show the graph. */
static private void toGraph(MainActivity act,Runnable run) {
    on=false;
    inScreen=false;
    dismissSheet();
    act.lightBars(!getInvertColors());
    run.run();
    Dashboard.showFullGraph(act);
    act.requestRender();
    }

static private void click(View root,int id,View.OnClickListener listener) {
    View v=root.findViewById(id);
    if(v!=null)
        v.setOnClickListener(listener);
    }

static private void toggle(View root,int id,boolean checked,CompoundButton.OnCheckedChangeListener listener) {
    CompoundButton b=root.findViewById(id);
    if(b==null)
        return;
    b.setChecked(checked);
    b.setOnCheckedChangeListener(listener);
    }

/* Scroll position for the next menu (theme change rebuilds it); -1: top. */
static private int restoreScroll=-1;

static private void showNow(MainActivity act) {
    final Context ctx=themed(act);
    final BottomSheetDialog dialog=new BottomSheetDialog(ctx);
    final View view=LayoutInflater.from(ctx).inflate(R.layout.material_menu,null,false);
    dialog.setContentView(view);
    sheet=dialog;

    final BottomSheetBehavior<?> behavior=dialog.getBehavior();
    behavior.setSkipCollapsed(true);
    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    /* The expanded sheet reaches under the status bar: icons in the sheet's contrast. */
    dialog.setOnShowListener(d -> {
        android.view.Window win=dialog.getWindow();
        if(win!=null) {
            androidx.core.view.WindowInsetsControllerCompat bars=androidx.core.view.WindowCompat.getInsetsController(win,win.getDecorView());
            bars.setAppearanceLightStatusBars(!getInvertColors());
            bars.setAppearanceLightNavigationBars(!getInvertColors());
            }
        });

    dialog.setOnDismissListener(d -> {
        if(sheet==dialog)
            sheet=null;
        if(!leavingForScreen) {
            {if(doLog) {Log.i(LOG_ID,"menu closed");};};
            on=false;
            inScreen=false;
            Dashboard.update();
            act.lightBars(!getInvertColors());
            act.requestRender();
            }
        });

    // Time navigation
    click(view,R.id.jm_now,v -> toGraph(act,Natives::settonow));
    click(view,R.id.jm_dayback,v -> toGraph(act,() -> Natives.prevday(1)));
    click(view,R.id.jm_daylater,v -> toGraph(act,() -> Natives.nextday(1)));
    click(view,R.id.jm_weekback,v -> toGraph(act,() -> Natives.prevday(7)));
    click(view,R.id.jm_weeklater,v -> toGraph(act,() -> Natives.nextday(7)));
    click(view,R.id.jm_date,v -> {
        var c=Applic.app.curve;
        if(c!=null)
            toGraph(act,() -> c.startdatepick(Natives.getstarttime()));
        });
    click(view,R.id.jm_search,v -> {
        var c=Applic.app.curve;
        if(c!=null)
            toGraph(act,c::startsearch);
        });

    // Sensors and devices
    click(view,R.id.jm_sensor,v -> toScreen(() -> {
        act.lightBars(!getInvertColors());
        bluediag.start(act);
        }));
    click(view,R.id.jm_watch,v -> toScreen(() -> {
        act.lightBars(!getInvertColors());
        tk.glucodata.Watch.show(act);
        }));
    click(view,R.id.jm_mirror,v -> toScreen(() -> (new Backup()).mkbackupview(act)));
    click(view,R.id.jm_meter,v -> toScreen(() -> {
        act.lightBars(!getInvertColors());
        if(!PhoneUi.show("meters",act)) // Bluetooth blood glucose meters
            bluediag.start(act);
        }));
    View photo=view.findViewById(R.id.jm_photo);
    if(tk.glucodata.BuildConfig.SiBionics==1)
        photo.setOnClickListener(v -> toScreen(() -> PhotoScan.scan(act,REQUEST_BARCODE)));
    else
        view.findViewById(R.id.jm_photo_row).setVisibility(View.GONE);

    // Data
    click(view,R.id.jm_newamount,v -> {
        if(Natives.staticnum()) {
            help.help(R.string.staticnum,act);
            return;
            }
        var c=Applic.app.curve;
        if(c!=null) {
            toScreen(() -> {
                act.lightBars(!getInvertColors());
                c.numberview.addnumberview(act);
                if(!smallScreen)
                    c.showkeyboard(act);
                });
            }
        });
    click(view,R.id.jm_list,v -> {
        var c=Applic.app.curve;
        if(c!=null) {
            toScreen(() -> {
                act.lightBars(!getInvertColors());
                Natives.makenumbers();
                act.requestRender();
                c.getnumcontrol(act);
                });
            }
        });
    click(view,R.id.jm_statistics,v -> {
        if(Natives.makepercentages()) {
            toScreen(() -> {
                act.lightBars(!getInvertColors());
                act.requestRender();
                Stats.mkstats(act);
                });
            }
        else /* used to do nothing at all */
            tk.glucodata.m3.M3Page.message(act,act.getString(R.string.jm_stats_title),act.getString(R.string.jm_stats_nodata));
        });
    click(view,R.id.jm_export,v -> {
        var c=Applic.app.curve;
        if(c!=null) {
            {if(doLog) {Log.i(LOG_ID,"EXPORT");};};
            toScreen(() -> {
                act.lightBars(!getInvertColors());
                c.dialogs.showexport(act,c.getWidth(),c.getHeight(),null);
                });
            }
        });

    // Graph layers: change immediately, the graph redraws behind the sheet.
    toggle(view,R.id.jm_stream,Natives.getshowstream(),(b,isChecked) -> {
        Natives.setshowstream(isChecked);
        act.requestRender();
        });
    toggle(view,R.id.jm_history,Natives.getshowhistories(),(b,isChecked) -> {
        Natives.setshowhistories(isChecked);
        act.requestRender();
        });
    toggle(view,R.id.jm_scans,getshowscans(),(b,isChecked) -> {
        Natives.setshowscans(isChecked);
        act.requestRender();
        });
    toggle(view,R.id.jm_amounts,Natives.getshownumbers(),(b,isChecked) -> {
        Natives.setshownumbers(isChecked);
        act.requestRender();
        });
    toggle(view,R.id.jm_meals,Natives.getshowmeals(),(b,isChecked) -> {
        Natives.setshowmeals(isChecked);
        act.requestRender();
        });
    final String calibrated=act.getString(R.string.calibrated);
    CompoundButton calstream=view.findViewById(R.id.jm_calibratedstream);
    calstream.setText(act.getString(R.string.jm_calibrated_item,act.getString(R.string.streamname),calibrated));
    toggle(view,R.id.jm_calibratedstream,Natives.getshowcalibratedstream(),(b,isChecked) -> {
        Natives.setshowcalibratedstream(isChecked);
        act.requestRender();
        });
    CompoundButton calhistory=view.findViewById(R.id.jm_calibratedhistory);
    calhistory.setText(act.getString(R.string.jm_calibrated_item,act.getString(R.string.historyname),calibrated));
    toggle(view,R.id.jm_calibratedhistory,Natives.getshowcalibratedhistories(),(b,isChecked) -> {
        Natives.setshowcalibratedhistories(isChecked);
        act.requestRender();
        });
    CompoundButton calscans=view.findViewById(R.id.jm_calibratedscans);
    calscans.setText(act.getString(R.string.jm_calibrated_item,act.getString(R.string.scansname),calibrated));
    toggle(view,R.id.jm_calibratedscans,Natives.getshowcalibratedscans(),(b,isChecked) -> {
        Natives.setshowcalibratedscans(isChecked);
        act.requestRender();
        });

    // Display
    /* Phones now always follow Android's orientation (MainActivity), so the
     * option only matters on tablets and foldables. */
    if(act.getResources().getConfiguration().smallestScreenWidthDp<600)
        view.findViewById(R.id.jm_autorotate).setVisibility(View.GONE);
    toggle(view,R.id.jm_autorotate,Natives.getRotate(),(b,isChecked) -> {
        Natives.setRotate(isChecked);
        act.applyScreenOrientation(act.getResources().getConfiguration());
        });
    /* Appearance: System (follow Android's dark theme), Light or Dark. */
    final com.google.android.material.button.MaterialButtonToggleGroup appearance=view.findViewById(R.id.jm_appearance);
    if(appearance!=null) {
        final int[] ids={R.id.jm_appearance_system,R.id.jm_appearance_light,R.id.jm_appearance_dark};
        appearance.check(ids[tk.glucodata.m3.ThemeMode.get(act)]);
        tk.glucodata.m3.M3Page.checkMarks(appearance);
        appearance.addOnButtonCheckedListener((group,id,isChecked) -> {
            if(!isChecked)
                return;
            int mode=tk.glucodata.m3.ThemeMode.SYSTEM;
            if(id==R.id.jm_appearance_light)
                mode=tk.glucodata.m3.ThemeMode.LIGHT;
            else if(id==R.id.jm_appearance_dark)
                mode=tk.glucodata.m3.ThemeMode.DARK;
            /* Rebuild the sheet, the dashboard and the menu button in the new colours. */
            if(tk.glucodata.m3.ThemeMode.set(act,mode))
                view.post(() -> themeChanged(act));
            });
        }
    toggle(view,R.id.jm_glucosefloat,Natives.getfloatglucose(),(b,isChecked) -> Floating.setfloatglucose(act,isChecked));
    toggle(view,R.id.jm_systemui,getsystemui(),(b,isChecked) -> {
        setsystemui(isChecked);
        act.selectionSystemUI();
        });

    // App
    click(view,R.id.jm_settings,v -> toScreen(() -> Settings.set(act)));
    click(view,R.id.jm_talk,v -> toScreen(() -> tk.glucodata.Talker.config(act)));
    click(view,R.id.jm_chatgpt,v -> toScreen(() -> JugglucoChat.show(act)));
    click(view,R.id.jm_about,v -> {
        if(Applic.app.curve!=null)
            toScreen(() -> GlucoseCurve.doabout(act));
        });
    click(view,R.id.jm_close,v -> {
        on=false;
        dismissSheet();
        act.moveTaskToBack(true);
        });

    view.setAccessibilityDelegate(Layout.accessDeli);
    dialog.show();
    if(restoreScroll>0) {
        final int y=restoreScroll;
        view.post(() -> view.scrollTo(0,y));
        }
    restoreScroll=-1;
    }
};
