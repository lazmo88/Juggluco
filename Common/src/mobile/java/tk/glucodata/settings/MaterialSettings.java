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

package tk.glucodata.settings;

import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.help.help;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;

import tk.glucodata.Applic;
import tk.glucodata.BuildConfig;
import tk.glucodata.GlucoseCurve;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.Notify;
import tk.glucodata.R;

/*
 * Material 3 version of the top level Settings screen for phones.
 *
 * Settings.java still owns the screen: it opens it, handles Back and closing,
 * and its sub screens (alarms, display, exchange data, ...) are reused
 * unchanged.  Every row calls the same setter or opens the same sub screen as
 * the button or check box of the old Layout based screen, with the same
 * "set unit first" checks.  The Wear OS build keeps the old screen.
 *
 * The screen follows Juggluco's dark mode and dynamic colour, like the menu
 * sheet it is opened from.  The old sub screens keep the colours of the
 * selected app theme, so while Settings is open the system bar icons follow
 * whichever of the two is shown at the top of the screen.
 */
public class MaterialSettings implements Settings.Screen {
private static final String LOG_ID="MaterialSettings";

public static void register() {
    Settings.phoneScreen=MaterialSettings::new;
    }

private final Settings settings;
private MainActivity act;
private Context ctx;
private ViewGroup root;
private LinearLayout list;
/* Re-read values shown on the screen; run when it is shown again. */
private final ArrayList<Runnable> refreshers=new ArrayList<>();
private boolean updating=false;
private boolean wasTop=false;
private ViewGroup content;
private ViewTreeObserver.OnGlobalLayoutListener barsListener;

private MaterialSettings(Settings settings) {
    this.settings=settings;
    }

private static Context themed(MainActivity act) {
    final int theme=getInvertColors()?R.style.Juggluco_M3_Dark:R.style.Juggluco_M3_Light;
    return DynamicColors.wrapContextIfAvailable(new ContextThemeWrapper(act,theme));
    }

@Override
public ViewGroup show(MainActivity act) {
    this.act=act;
    ctx=themed(act);
    root=(ViewGroup)LayoutInflater.from(ctx).inflate(R.layout.material_settings,null,false);
    list=root.findViewById(R.id.jm_settings_list);
    MaterialToolbar toolbar=root.findViewById(R.id.jm_settings_toolbar);
    /* Same as system Back: runs Settings' close handler, or closes the sub
     * screen (dialog) that is open on top. */
    toolbar.setNavigationOnClickListener(v -> MainActivity.doonback());
    build();
    handleInsets();
    /* Not addMyContentView(): that restyles buttons and switches for the old
     * app themes and makes texts selectable, which breaks the list rows. */
    act.addContentView(root,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    watchSystemBars();
    return root;
    }

@Override
public void update() {
    refresh();
    }

private void refresh() {
    updating=true;
    try {
        for(Runnable r:refreshers)
            r.run();
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"refresh",th);
        }
    updating=false;
    }

private void applyInsets(int left,int top,int right,int bottom) {
    View toolbar=root.findViewById(R.id.jm_settings_toolbar);
    toolbar.setPadding(left,top,right,0);
    ViewGroup.LayoutParams params=toolbar.getLayoutParams();
    params.height=top+Math.round(64*act.getResources().getDisplayMetrics().density); // M3 small top app bar
    toolbar.setLayoutParams(params);
    root.findViewById(R.id.jm_settings_scroll).setPadding(left,0,right,bottom);
    }

private void handleInsets() {
    /* Start with the insets MainActivity already knows, so the first frame
     * is right; the listener follows rotations and cutouts. */
    applyInsets(MainActivity.systembarLeft,MainActivity.systembarTop,MainActivity.systembarRight,MainActivity.systembarBottom);
    ViewCompat.setOnApplyWindowInsetsListener(root,(v,windowInsets) -> {
        Insets bars=windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
        applyInsets(bars.left,bars.top,bars.right,bars.bottom);
        return windowInsets;
        });
    root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View v) {
            ViewCompat.requestApplyInsets(v);
            }
        @Override
        public void onViewDetachedFromWindow(@NonNull View v) { }
        });

    /* Material 3 top app bar: surface colour, container colour when the
     * list is scrolled under it. */
    final MaterialToolbar toolbar=root.findViewById(R.id.jm_settings_toolbar);
    final int flat=themeColor(com.google.android.material.R.attr.colorSurface);
    final int lifted=themeColor(com.google.android.material.R.attr.colorSurfaceContainer);
    androidx.core.widget.NestedScrollView scroll=root.findViewById(R.id.jm_settings_scroll);
    scroll.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener)(sv,x,y,ox,oy) ->
        toolbar.setBackgroundColor(y>0?lifted:flat));
    }

private int themeColor(int attr) {
    TypedValue tv=new TypedValue();
    ctx.getTheme().resolveAttribute(attr,tv,true);
    return tv.data;
    }

/* ---------- system bar icons ---------- */

private static boolean appThemeIsLight(Context context) {
    TypedValue value=new TypedValue();
    return context.getTheme().resolveAttribute(R.attr.isAppLightTheme,value,true)&&value.data!=0;
    }

/* Colour of a full screen view, looking through scroll views without a
 * background of their own. */
private static Integer backgroundColor(View view) {
    for(int depth=0;view!=null&&depth<4;depth++) {
        Drawable d=view.getBackground();
        if(d instanceof ColorDrawable) {
            int col=((ColorDrawable)d).getColor();
            if((col>>>24)>=0xF0)
                return col;
            }
        if(d!=null)
            return null;
        if(!(view instanceof ViewGroup)||((ViewGroup)view).getChildCount()==0)
            return null;
        view=((ViewGroup)view).getChildAt(0);
        }
    return null;
    }

/* The old screens set light or dark bar icons for the graph's colours, but
 * are drawn in the app theme's colours.  While Settings is open, use the
 * colours of whatever covers the status bar. */
private void watchSystemBars() {
    if(!(root.getParent() instanceof ViewGroup))
        return;
    content=(ViewGroup)root.getParent();
    barsListener=this::checkSystemBars;
    content.getViewTreeObserver().addOnGlobalLayoutListener(barsListener);
    root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View v) { }
        @Override
        public void onViewDetachedFromWindow(@NonNull View v) {
            if(barsListener!=null) {
                ViewTreeObserver obs=content.getViewTreeObserver();
                if(obs.isAlive())
                    obs.removeOnGlobalLayoutListener(barsListener);
                barsListener=null;
                }
            v.removeOnAttachStateChangeListener(this);
            }
        });
    }

private void checkSystemBars() {
    if(root.getParent()==null)
        return;
    View top=null;
    final int height=content.getHeight();
    for(int i=content.getChildCount()-1;i>=0;i--) {
        final View child=content.getChildAt(i);
        if(child.getVisibility()!=VISIBLE||child instanceof FloatingActionButton)
            continue;
        if(child.getTop()>0||child.getHeight()<height/2) // dialogs and panels
            continue;
        top=child;
        break;
        }
    final boolean isTop=top==root;
    if(isTop&&!wasTop)
        refresh();
    wasTop=isTop;
    if(top==null||top instanceof GlucoseCurve||Build.VERSION.SDK_INT<30)
        return;
    final boolean light;
    if(isTop)
        light=!getInvertColors();
    else {
        Integer col=backgroundColor(top);
        light=col!=null?ColorUtils.calculateLuminance(col)>0.5:appThemeIsLight(act);
        }
    var win=act.getWindow();
    var controller=WindowCompat.getInsetsController(win,win.getDecorView());
    if(controller.isAppearanceLightStatusBars()!=light||controller.isAppearanceLightNavigationBars()!=light)
        act.lightBars(light);
    }

/* ---------- building the list ---------- */

private LinearLayout section(int title) {
    View sec=LayoutInflater.from(ctx).inflate(R.layout.material_settings_section,list,false);
    ((TextView)sec.findViewById(R.id.jm_section_title)).setText(title);
    list.addView(sec);
    return sec.findViewById(R.id.jm_section_items);
    }

private ViewGroup row(LinearLayout parent,int icon,CharSequence title) {
    ViewGroup row=(ViewGroup)LayoutInflater.from(ctx).inflate(R.layout.material_settings_row,parent,false);
    ((ImageView)row.findViewById(R.id.jm_row_icon)).setImageResource(icon);
    ((TextView)row.findViewById(R.id.jm_row_title)).setText(title);
    parent.addView(row);
    return row;
    }

private static void setSummary(View row,CharSequence summary) {
    TextView view=row.findViewById(R.id.jm_row_summary);
    if(summary==null||summary.length()==0)
        view.setVisibility(View.GONE);
    else {
        view.setText(summary);
        view.setVisibility(VISIBLE);
        }
    }

/* Row that opens a sub screen. */
private ViewGroup link(LinearLayout parent,int icon,CharSequence title,View.OnClickListener click) {
    ViewGroup row=row(parent,icon,title);
    ImageView chevron=new ImageView(ctx);
    chevron.setImageResource(R.drawable.ic_jm_chevron_right);
    chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    chevron.setColorFilter(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
    if(act.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL)
        chevron.setScaleX(-1f);
    ((FrameLayout)row.findViewById(R.id.jm_row_end)).addView(chevron);
    row.setOnClickListener(click);
    return row;
    }

interface BoolGet { boolean get(); }
interface BoolSet { void set(boolean on); }

/* Row with a switch.  The whole row toggles it and is announced as a switch. */
private ViewGroup toggle(LinearLayout parent,int icon,CharSequence title,BoolGet get,BoolSet set) {
    ViewGroup row=row(parent,icon,title);
    MaterialSwitch sw=new MaterialSwitch(ctx);
    sw.setClickable(false);
    sw.setFocusable(false);
    sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    sw.setChecked(get.get());
    sw.setOnCheckedChangeListener((b,isChecked) -> {
        if(!updating)
            set.set(isChecked);
        });
    ((FrameLayout)row.findViewById(R.id.jm_row_end)).addView(sw);
    row.setOnClickListener(v -> sw.toggle());
    ViewCompat.setAccessibilityDelegate(row,new AccessibilityDelegateCompat() {
        @Override
        public void onInitializeAccessibilityNodeInfo(@NonNull View host,@NonNull AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host,info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(sw.isChecked());
            }
        });
    refreshers.add(() -> sw.setChecked(get.get()));
    return row;
    }

private boolean unitChosen() {
    if(settings.checkedunit()==0) {
        Applic.argToaster(act,R.string.setunitfirst,Toast.LENGTH_SHORT);
        return false;
        }
    return true;
    }

private String unitName() {
    switch(Natives.getunit()) {
        case 1: return act.getString(R.string.mmolL);
        case 2: return act.getString(R.string.mgdL);
        }
    return null;
    }

private String alarmSummary() {
    final String low=act.getString(R.string.jm_set_alarm_low,Natives.hasalarmlow()?Settings.float2string(Natives.alarmlow()):act.getString(R.string.jm_set_off));
    final String high=act.getString(R.string.jm_set_alarm_high,Natives.hasalarmhigh()?Settings.float2string(Natives.alarmhigh()):act.getString(R.string.jm_set_off));
    return low+" · "+high;
    }

private String targetSummary() {
    final String unit=unitName();
    if(unit==null)
        return null;
    return act.getString(R.string.jm_set_target,Settings.float2string(Natives.targetlow()),Settings.float2string(Natives.targethigh()),unit);
    }

private void unitRow(LinearLayout parent) {
    String title=act.getString(R.string.unit).trim();
    if(title.endsWith(":")||title.endsWith("："))
        title=title.substring(0,title.length()-1).trim();
    ViewGroup row=row(parent,R.drawable.ic_jm_straighten,title);
    MaterialButtonToggleGroup group=new MaterialButtonToggleGroup(ctx);
    group.setSingleSelection(true);
    MaterialButton mmol=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
    mmol.setId(View.generateViewId());
    mmol.setText(R.string.mmolL);
    MaterialButton mgdl=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
    mgdl.setId(View.generateViewId());
    mgdl.setText(R.string.mgdL);
    group.addView(mmol,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    group.addView(mgdl,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    Runnable show=() -> {
        switch(Natives.getunit()) {
            case 1: group.check(mmol.getId());break;
            case 2: group.check(mgdl.getId());break;
            default: group.clearChecked();
            }
        };
    show.run();
    /* Once a unit is chosen there is always one: as with the old radio buttons. */
    group.setSelectionRequired(true);
    group.addOnButtonCheckedListener((g,id,isChecked) -> {
        if(updating||!isChecked)
            return;
        final int unit=id==mmol.getId()?1:2;
        if(unit!=Natives.getunit()) {
            ((Applic)act.getApplication()).setunit(unit);
            settings.recreate();
            }
        });
    refreshers.add(show);
    ((FrameLayout)row.findViewById(R.id.jm_row_end)).addView(group);
    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

private void build() {
    final MainActivity context=act;
    /* The old screen gave some sub screens its inner layout and others the
     * whole scroll view; list and root play those roles here. */
    final View inner=list;

    unitRow(section(R.string.jm_set_section_unit));

    LinearLayout sensor=section(R.string.jm_set_section_sensor);
    if(MainActivity.hasnfc) {
        toggle(sensor,R.drawable.ic_jm_volume_up,act.getString(R.string.nfcsound),Natives::nfcsound,isChecked -> {
            Natives.setnfcsound(isChecked);
            context.setnfc();
            });
        settings.scanenabled=settings.IntentscanEnabled();
        toggle(sensor,R.drawable.ic_jm_nfc,act.getString(R.string.startsapp),settings::IntentscanEnabled,settings::EnableIntentScanning);
        if(Natives.camerakey()>0) {
            toggle(sensor,R.drawable.ic_jm_no_photography,act.getString(R.string.disablecamerakey),() -> Natives.camerakey()==1,
                isChecked -> Natives.setcamerakey(isChecked?1:2));
            }
        }
    toggle(sensor,R.drawable.ic_jm_contactless,act.getString(R.string.googlescan),Natives::getGoogleScan,Natives::setGoogleScan);
    link(sensor,R.drawable.ic_jm_tune,act.getString(R.string.calibration),v -> Calibration.show(context,inner));

    LinearLayout alerts=section(R.string.jm_set_section_alerts);
    View alarms=link(alerts,R.drawable.ic_jm_alarm,act.getString(R.string.alarms),v -> {
        if(unitChosen())
            Settings.alarmsettings(context,root);
        });
    refreshers.add(() -> setSummary(alarms,alarmSummary()));
    link(alerts,R.drawable.ic_jm_schedule,act.getString(R.string.remindersname),v -> new tk.glucodata.setNumAlarm().mkviews(context,root));
    toggle(alerts,R.drawable.ic_jm_notifications,act.getString(R.string.glucosestatusbar),Natives::getshowalways,Notify::glucosestatus);
    View floating=link(alerts,R.drawable.ic_jm_picture_in_picture,act.getString(R.string.floatglucose),v -> tk.glucodata.FloatingConfig.show(context,inner));
    refreshers.add(() -> setSummary(floating,act.getString(Natives.getfloatglucose()?R.string.jm_set_on:R.string.jm_set_off)));

    LinearLayout data=section(R.string.jm_set_section_data);
    link(data,R.drawable.ic_jm_sync_alt,act.getString(R.string.exchanges),v -> Settings.exchanges(context,inner));
    link(data,R.drawable.ic_jm_label,act.getString(R.string.numberlabels),v -> {
        settings.hidekeyboard();
        new LabelsClass(context).mklabellayout(inner);
        });

    LinearLayout display=section(R.string.jm_set_section_display);
    View displayrow=link(display,R.drawable.ic_jm_palette,act.getString(R.string.display),v -> {
        if(unitChosen())
            Settings.displaysettings(context,settings);
        });
    refreshers.add(() -> setSummary(displayrow,targetSummary()));

    LinearLayout helpsec=section(R.string.jm_set_section_help);
    link(helpsec,R.drawable.ic_jm_help,act.getString(R.string.helpname),v -> help(R.string.settinghelp,context));
    final String advhelp=Natives.advanced();
    if(advhelp!=null) {
        link(helpsec,R.drawable.ic_jm_description,act.getString(R.string.advanced),v -> {
            EnableControls(inner,false);
            help(advhelp,context,l -> EnableControls(inner,true));
            });
        }
    link(helpsec,R.drawable.ic_jm_school,"Intro",v -> help(R.string.introhelp,context));
    if(doLog)
        link(helpsec,R.drawable.ic_jm_bug_report,act.getString(R.string.logging),v -> LogConfig.make(context,inner));
    View about=link(helpsec,R.drawable.ic_jm_info,act.getString(R.string.aboutname),v -> GlucoseCurve.doabout(context));
    setSummary(about,BuildConfig.VERSION_NAME);

    refresh();
    }
}
