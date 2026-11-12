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

import static tk.glucodata.settings.Settings.float2string;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Arrays;
import java.util.List;

import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.RingTonePage;
import tk.glucodata.SuperGattCallback;
import tk.glucodata.m3.M3Page;

/*
 * Alarms (Settings.alarmsettings) and the advanced alarms
 * (Settings.advancedalarm) as Material 3 pages.
 *
 * Same semantics as the old screens: the values are written when the page is
 * closed (Back, Up) and before switching profile, with the same setters
 * (Natives.setalarms / setAdvancedAlarms).  The sound type applies at once.
 * Invalid numbers are shown under the field and keep the page open (the old
 * screen showed a toast and, on Back, silently dropped every change).
 */
public final class AlarmsPage {
private AlarmsPage() {}
private static final String LOG_ID="AlarmsPage";

static String unit(Context ctx) {
    return ctx.getString(Natives.getunit()==1?R.string.mmolL:R.string.mgdL);
    }

static List<String> profileNames(Context ctx) {
    final String profile=ctx.getString(R.string.profile);
    return Arrays.asList(ctx.getString(R.string.defaultname),profile+"1",profile+"2",profile+"3",profile+"4",profile+"5");
    }

/* One alarm: switch row, then the threshold and the sound, which are only
 * shown while the alarm is on. */
static final class Alarm {
    MaterialSwitch on;
    TextInputEditText value;
    ViewGroup sound;
    float old;

    boolean isOn() {
        return on.isChecked();
        }
    /* Value to store, or NaN (error shown) if the alarm is on and invalid. */
    float get(Context ctx) {
        M3Page.error(value,null);
        float v=M3Page.number(value);
        if(Float.isNaN(v)) {
            if(!isOn())
                return old; // not used while off: keep what was there
            M3Page.error(value,ctx.getString(R.string.jm_invalid_number));
            }
        return v;
        }
    }

static View fieldLayout(TextInputEditText edit) {
    View v=edit;
    while(v!=null&&!(v instanceof TextInputLayout))
        v=(View)v.getParent();
    return v;
    }

/* Child rows are indented to the text of the switch row. */
static void indent(M3Page page,View view) {
    if(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
        ((ViewGroup.MarginLayoutParams)view.getLayoutParams()).setMarginStart(page.dp(56));
        view.requestLayout();
        }
    else
        view.setPaddingRelative(page.dp(56),view.getPaddingTop(),view.getPaddingEnd(),view.getPaddingBottom());
    }

/* Row that opens the sound page of an alarm kind; its summary follows. */
static ViewGroup soundRow(M3Page page,LinearLayout parent,int kind,String label,boolean indented) {
    final MainActivity act=page.act;
    final ViewGroup[] row={null};
    row[0]=page.link(parent,indented?0:R.drawable.ic_jm_music_note,act.getString(R.string.jm_alerts_sound_title),RingTonePage.summary(act,kind),() -> {
        page.hideKeyboard();
        M3Page sub=RingTonePage.open(act,kind,label);
        if(sub!=null&&sub!=page)
            sub.onClose(() -> M3Page.setSummary(row[0],RingTonePage.summary(act,kind)));
        });
    if(indented)
        indent(page,row[0]);
    return row[0];
    }

static Alarm alarm(M3Page page,LinearLayout sec,int icon,int title,boolean has,float value,int fieldLabel,int kind) {
    final MainActivity act=page.act;
    final Alarm alarm=new Alarm();
    alarm.old=value;
    final View[] deps=new View[2];
    alarm.on=page.toggle(sec,icon,act.getString(title),null,() -> has,on -> {
        for(View v:deps)
            v.setVisibility(on?View.VISIBLE:View.GONE);
        if(!on)
            M3Page.error(alarm.value,null);
        });
    alarm.value=page.field(sec,act.getString(fieldLabel),float2string(value),M3Page.DECIMAL,unit(act),null);
    deps[0]=fieldLayout(alarm.value);
    indent(page,deps[0]);
    alarm.sound=soundRow(page,sec,kind,act.getString(title),true);
    deps[1]=alarm.sound;
    for(View v:deps)
        v.setVisibility(has?View.VISIBLE:View.GONE);
    return alarm;
    }

public static void show(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.alarms);
    page.help(R.string.alarmhelp);

    /* Profile: what follows belongs to the active profile. */
    LinearLayout prof=page.section(R.string.jm_talk_section_profile);
    final List<String> profiles=profileNames(act);
    final int curProfile=Natives.getProfile();
    final MaterialAutoCompleteTextView[] profMenu={null};
    final Runnable[] saveHolder={null};
    profMenu[0]=page.choice(prof,act.getString(R.string.jm_alerts_active_profile),profiles,curProfile,i -> {
        if(i==curProfile)
            return;
        if(saveHolder[0]==null) {
            M3Page.select(profMenu[0],profiles.get(curProfile));
            return;
            }
        saveHolder[0].run();
        });
    ViewGroup schedules=page.link(prof,R.drawable.ic_jm_schedule,act.getString(R.string.schedules),schedulesSummary(act),() -> {
        page.hideKeyboard();
        ProfileSchedulesPage.show(act);
        M3Page sub=M3Page.top(act);
        if(sub!=null&&sub!=page)
            sub.onClose(() -> M3Page.setSummary(page.list.findViewWithTag(LOG_ID+"schedules"),schedulesSummary(act)));
        });
    schedules.setTag(LOG_ID+"schedules");

    /* Glucose */
    LinearLayout gl=page.section(R.string.jm_alerts_section_glucose);
    final Alarm low=alarm(page,gl,R.drawable.ic_jm_arrow_downward,R.string.lowglucosealarm,Natives.hasalarmlow(),Natives.alarmlow(),R.string.jm_alerts_below,0);
    final Alarm high=alarm(page,gl,R.drawable.ic_jm_arrow_upward,R.string.highglucosealarm,Natives.hasalarmhigh(),Natives.alarmhigh(),R.string.jm_alerts_above,1);
    page.link(gl,R.drawable.ic_jm_tune,act.getString(R.string.advanced),act.getString(R.string.jm_alerts_advanced_summary),() -> {
        page.hideKeyboard();
        showAdvanced(act);
        });

    /* Loss of signal and value notification */
    LinearLayout other=page.section(R.string.jm_alerts_section_other);
    final View[] lossDeps=new View[2];
    MaterialSwitch loss=page.toggle(other,R.drawable.ic_jm_sensors_off,act.getString(R.string.lossofsignal),null,Natives::hasalarmloss,on -> {
        for(View v:lossDeps)
            v.setVisibility(on?View.VISIBLE:View.GONE);
        });
    TextInputEditText losswait=page.field(other,act.getString(R.string.lossofsignalalarm),String.valueOf(Natives.readalarmsuspension(4)),M3Page.INTEGER,act.getString(R.string.jm_unit_minutes),null);
    lossDeps[0]=fieldLayout(losswait);
    indent(page,lossDeps[0]);
    lossDeps[1]=soundRow(page,other,4,act.getString(R.string.lossofsignal),true);
    for(View v:lossDeps)
        v.setVisibility(loss.isChecked()?View.VISIBLE:View.GONE);

    final View[] valueDeps=new View[1];
    MaterialSwitch isvalue=page.toggle(other,R.drawable.ic_jm_notifications_active,act.getString(R.string.valueavailablenotification),act.getString(R.string.jm_alerts_value_summary),
        Natives::hasvaluealarm,on -> valueDeps[0].setVisibility(on?View.VISIBLE:View.GONE));
    valueDeps[0]=soundRow(page,other,2,act.getString(R.string.valuenotification),true);
    valueDeps[0].setVisibility(isvalue.isChecked()?View.VISIBLE:View.GONE);

    /* How alarms are played: applies at once, as the old radio buttons. */
    LinearLayout out=page.section(R.string.jm_alerts_section_output);
    page.segmented(out,act.getString(R.string.alarmis),new CharSequence[]{act.getString(R.string.alarm),act.getString(R.string.notification),act.getString(R.string.media)},
        Natives.getalarmSoundType(),Natives::setalarmSoundType);
    page.note(out,act.getString(R.string.jm_alerts_output_summary));

    /* The old saver. */
    final M3Page.CloseCheck saver=() -> {
        final boolean hasloss=loss.isChecked();
        M3Page.error(losswait,null);
        short wa=0;
        if(hasloss) {
            String str=M3Page.text(losswait);
            try {
                wa=Short.parseShort(str);
                }
            catch(Throwable e) {
                Log.i(LOG_ID,"parseShort "+str);
                M3Page.error(losswait,act.getString(R.string.cantsetminutes)+str);
                return false;
                }
            }
        float lowv=low.get(act),highv=high.get(act);
        if(Float.isNaN(lowv)||Float.isNaN(highv))
            return false;
        if(hasloss&&wa!=Natives.readalarmsuspension(4)) {
            Natives.writealarmsuspension(4,wa);
            if(SuperGattCallback.glucosealarms!=null)
                SuperGattCallback.glucosealarms.setLossAlarm();
            }
        Natives.setalarms(lowv,highv,low.isOn(),high.isOn(),isvalue.isChecked(),hasloss);
        return true;
        };
    page.closeCheck(saver);
    saveHolder[0]=() -> {
        final int pos=profiles.indexOf(profMenu[0].getText().toString());
        if(!saver.canClose()) {
            M3Page.select(profMenu[0],profiles.get(curProfile));
            return;
            }
        page.dismiss();
        Settings.setProfile(act,pos);
        show(act);
        };
    }

static String schedulesSummary(Context ctx) {
    final int n=Natives.nrScheduledProfiles();
    if(n==0)
        return ctx.getString(R.string.jm_alerts_no_schedules);
    return ctx.getResources().getQuantityString(R.plurals.jm_alerts_schedules_count,n,n);
    }

/* Settings.advancedalarm(): very low, very high and predicted alarms. */
static void showAdvanced(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.advanced);
    page.subtitle(act.getString(R.string.alarms));
    page.help(R.string.advancedAlarmshelp);
    LinearLayout very=page.section(R.string.jm_alerts_section_very);
    final Alarm verylow=alarm(page,very,R.drawable.ic_jm_keyboard_double_arrow_down,R.string.verylowglucosealarm,Natives.hasalarmverylow(),Natives.alarmverylow(),R.string.jm_alerts_below,5);
    final Alarm veryhigh=alarm(page,very,R.drawable.ic_jm_keyboard_double_arrow_up,R.string.veryhighglucosealarm,Natives.hasalarmveryhigh(),Natives.alarmveryhigh(),R.string.jm_alerts_above,6);
    LinearLayout soon=page.section(R.string.jm_alerts_section_predicted);
    final Alarm prelow=alarm(page,soon,R.drawable.ic_jm_trending_down,R.string.prelowglucosealarm,Natives.hasalarmprelow(),Natives.alarmprelow(),R.string.jm_alerts_soon_below,7);
    final Alarm prehigh=alarm(page,soon,R.drawable.ic_jm_trending_up,R.string.prehighglucosealarm,Natives.hasalarmprehigh(),Natives.alarmprehigh(),R.string.jm_alerts_soon_above,8);
    page.closeCheck(() -> {
        float vl=verylow.get(act),vh=veryhigh.get(act),pl=prelow.get(act),ph=prehigh.get(act);
        if(Float.isNaN(vl)||Float.isNaN(vh)||Float.isNaN(pl)||Float.isNaN(ph))
            return false;
        Natives.setAdvancedAlarms(vl,vh,verylow.isOn(),veryhigh.isOn(),prelow.isOn(),prehigh.isOn(),pl,ph);
        return true;
        });
    }
}
