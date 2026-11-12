/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+ and           */
/*      Sibionics GS1Sb sensors.                                                     */
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

import android.content.pm.ActivityInfo;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Locale;

import tk.glucodata.Applic;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static tk.glucodata.Natives.setthreshold;
import static tk.glucodata.settings.Settings.float2string;
import static tk.glucodata.settings.Settings.str2float;

/* Phone Display settings (Settings.displaysettings): graph and target range,
 * scaling, threshold, axis, rotation, clock, language, and links to Colours,
 * Theme and IOB.  Switches apply at once and the range/threshold fields and
 * the scale switches are applied when the page closes, as in the old screen. */
public final class DisplayPage {
private DisplayPage() {}

public static void show(MainActivity context,Settings settings) {
    M3Page page=M3Page.open(context,R.string.display);
    page.help(R.string.displayhelp);
    final String unit=context.getString(Natives.getunit()==1?R.string.mmolL:R.string.mgdL);

    LinearLayout graph=page.section(R.string.jm_data_graph);
    LinearLayout gpair=pair(page,graph);
    TextInputEditText glow=page.field((LinearLayout)gpair.getChildAt(0),context.getString(R.string.jm_data_graphlow),float2string(Natives.graphlow()),M3Page.DECIMAL,unit,null);
    TextInputEditText ghigh=page.field((LinearLayout)gpair.getChildAt(1),context.getString(R.string.jm_data_graphhigh),float2string(Natives.graphhigh()),M3Page.DECIMAL,unit,null);
    page.note(graph,context.getString(R.string.manuallyscale));
    MaterialSwitch fixatex=page.toggle(graph,R.drawable.ic_jm_schedule,context.getString(R.string.time),null,() -> !Natives.getfixatex(),on -> {});
    MaterialSwitch fixatey=page.toggle(graph,R.drawable.ic_jm_show_chart,context.getString(R.string.glucose),null,() -> !Natives.getfixatey(),on -> {});
    page.toggle(graph,R.drawable.ic_jm_straighten,context.getString(R.string.glucoseaxisleft),null,Natives::getlevelleft,Natives::setlevelleft);
    page.toggle(graph,R.drawable.ic_jm_monitoring,context.getString(R.string.dexfuture),null,Natives::getdexcomPredict,Natives::setdexcomPredict);
    page.toggle(graph,R.drawable.ic_jm_keyboard_double_arrow_right,context.getString(R.string.clampnow),null,Natives::getcurrentRelative,Natives::setcurrentRelative);

    LinearLayout target=page.section(R.string.targetrange);
    LinearLayout tpair=pair(page,target);
    TextInputEditText tlow=page.field((LinearLayout)tpair.getChildAt(0),context.getString(R.string.jm_data_low),float2string(Natives.targetlow()),M3Page.DECIMAL,unit,null);
    TextInputEditText thigh=page.field((LinearLayout)tpair.getChildAt(1),context.getString(R.string.jm_data_high),float2string(Natives.targethigh()),M3Page.DECIMAL,unit,null);
    final String thresstring=float2string(Natives.getthreshold());
    TextInputEditText threshold=page.field(target,context.getString(R.string.threshold),thresstring,M3Page.DECIMAL,null,null);
    page.note(target,context.getString(R.string.jm_data_thresholdnote));

    LinearLayout screen=page.section(R.string.jm_data_screen);
    page.toggle(screen,R.drawable.ic_jm_schedule,context.getString(R.string.hour12),null,() -> !Natives.gethour24(),on -> Applic.sethour24(!on));
    final MaterialSwitch[] reverse={null},rotateText={null};
    final Runnable updateVisibility=() -> {
        boolean[] eff=orientationEffects(context);
        rowOf(reverse[0]).setVisibility(eff[0]?android.view.View.VISIBLE:android.view.View.GONE);
        rowOf(rotateText[0]).setVisibility(eff[1]?android.view.View.VISIBLE:android.view.View.GONE);
        };
    page.toggle(screen,R.drawable.ic_jm_sync_alt,context.getString(R.string.autorotate),null,Natives::getRotate,on -> {
        Settings.setAutoRotateOn(context,on);
        updateVisibility.run();
        });
    rotateText[0]=page.toggle(screen,R.drawable.ic_jm_sync_alt,context.getString(R.string.rotateText),null,Natives::getRotateText,on -> {
        Natives.setRotateText(on);
        context.applyScreenOrientation(context.getResources().getConfiguration());
        updateVisibility.run();
        });
    reverse[0]=page.toggle(screen,R.drawable.ic_jm_sync_alt,context.getString(R.string.invertscreen),null,() -> (Natives.getScreenOrientation()&SCREEN_ORIENTATION_REVERSE_LANDSCAPE)!=0,on -> {
        int ori=(on?SCREEN_ORIENTATION_REVERSE_LANDSCAPE:SCREEN_ORIENTATION_LANDSCAPE);
        Natives.setScreenOrientation(ori);
        context.applyScreenOrientation(context.getResources().getConfiguration());
        });
    updateVisibility.run();

    ArrayList<String> langs=new ArrayList<>();
    langs.add(context.getString(R.string.jm_data_systemlanguage));
    for(int i=1;i<Settings.supportedlanguages.size();i++) {
        final String code=Settings.supportedlanguages.get(i);
        Locale loc=Locale.forLanguageTag(code);
        String name=loc.getDisplayLanguage(loc);
        if(name.length()>0)
            name=name.substring(0,1).toUpperCase(loc)+name.substring(1);
        langs.add(name+" ("+code+")");
        }
    var locales=AppCompatDelegate.getApplicationLocales();
    int prepos;
    if(locales.isEmpty()||(prepos=Settings.supportedlanguages.indexOf(locales.get(0).getLanguage()))<1)
        prepos=0;
    final int pos=prepos;
    page.choice(screen,context.getString(R.string.languagename),langs,pos,position -> {
        if(position!=pos) {
            var newlocale=(position==0)?LocaleListCompat.getEmptyLocaleList():LocaleListCompat.forLanguageTags(Settings.supportedlanguages.get(position));
            AppCompatDelegate.setApplicationLocales(newlocale);
            }
        });

    LinearLayout look=page.section(R.string.jm_data_look);
    page.link(look,R.drawable.ic_jm_palette,context.getString(R.string.colors),null,() -> {
        page.close();
        if(settings!=null)
            settings.finish();
        SetColors.show(context);
        });
    page.link(look,R.drawable.ic_jm_contrast,context.getString(R.string.theme),themeName(),() -> ThemePage.show(context));
    page.link(look,R.drawable.ic_jm_vaccines,"IOB",context.getString(R.string.jm_data_iobsummary),() -> tk.glucodata.IOB.mkview(context));

    page.onClose(() -> {
        var newthreshold=M3Page.text(threshold);
        if(!thresstring.equals(newthreshold)) {
            float thres=str2float(newthreshold);
            if(thres>0.8f||thres<0.0f)
                Applic.argToaster(context,"A threshold should 0.0 - 0.8",Toast.LENGTH_LONG);
            else
                setthreshold(thres);
            }
        Natives.setfixatex(!fixatex.isChecked());
        Natives.setfixatey(!fixatey.isChecked());
        Natives.setGraphRange(str2float(M3Page.text(glow)),str2float(M3Page.text(ghigh)));
        Natives.setTargetRange(str2float(M3Page.text(tlow)),str2float(M3Page.text(thigh)));
        });
    }

static String themeName() {
    int idx=Natives.getTheme();
    AppTheme[] all=AppTheme.values();
    return (idx>=0&&idx<all.length?all[idx]:AppTheme.DEFAULT).toString();
    }

private static android.view.View rowOf(android.view.View sw) {
    android.view.View v=sw;
    while(v.getParent() instanceof android.view.View) {
        android.view.View p=(android.view.View)v.getParent();
        if(p.getId()==R.id.jm_row_end)
            return (android.view.View)p.getParent();
        v=p;
        }
    return sw;
    }

private static LinearLayout pair(M3Page page,LinearLayout parent) {
    LinearLayout pair=new LinearLayout(page.ctx);
    pair.setOrientation(LinearLayout.HORIZONTAL);
    for(int i=0;i<2;i++) {
        LinearLayout col=new LinearLayout(page.ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        pair.addView(col,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        }
    page.custom(parent,pair);
    return pair;
    }

/* {orientation option has an effect, rotate-text option has an effect}:
 * the rule of displaysettings' updateOrientationOptionVisibility. */
private static boolean[] orientationEffects(MainActivity context) {
    final boolean managedProfile;
    if(android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.N) {
        var userManager=(android.os.UserManager)context.getSystemService(android.content.Context.USER_SERVICE);
        managedProfile=userManager!=null&&userManager.isManagedProfile();
        }
    else
        managedProfile=false;
    final var config=context.getResources().getConfiguration();
    final boolean largeScreen=config.smallestScreenWidthDp>=600;
    final int shortSide=Math.min(config.screenWidthDp,config.screenHeightDp);
    final int longSide=Math.max(config.screenWidthDp,config.screenHeightDp);
    final boolean elongated=shortSide>0&&((float)longSide/shortSide)>=1.45f;
    final boolean nearlySquare=largeScreen&&!elongated;
    final boolean systemAutoRotate=managedProfile||android.provider.Settings.System.getInt(
            context.getContentResolver(),
            android.provider.Settings.System.ACCELEROMETER_ROTATION,1)!=0;
    final boolean effectiveAutoRotate=Natives.getRotate()&&systemAutoRotate;
    final boolean android16LargeRestriction=android.os.Build.VERSION.SDK_INT>=36
            &&context.getApplicationInfo().targetSdkVersion>=36;
    final boolean orientationHasEffect;
    if(nearlySquare)
        orientationHasEffect=false;
    else if(!effectiveAutoRotate&&largeScreen&&elongated&&android16LargeRestriction)
        orientationHasEffect=false;
    else if(!effectiveAutoRotate)
        orientationHasEffect=true;
    else
        orientationHasEffect=!Natives.getRotateText();
    final boolean rotateTextHasEffect=effectiveAutoRotate&&!nearlySquare;
    return new boolean[]{orientationHasEffect,rotateTextHasEffect};
    }
}
