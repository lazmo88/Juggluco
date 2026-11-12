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

package tk.glucodata;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.lang.ref.WeakReference;
import java.util.Locale;

import tk.glucodata.m3.M3Page;
import tk.glucodata.nums.AllData;
import tk.glucodata.nums.GarminAlarmConfig;
import tk.glucodata.settings.Shortcuts;

/*
 * Kerfstok watch alarms (GarminAlarms.show/alertSettings) as Material 3
 * pages.  Same config object, same checks; Save stores, Back discards.
 */
final class GarminAlarmsPage {
private GarminAlarmsPage() {}

private static String shown(int threshold,boolean mmol) {
    return String.format(Locale.getDefault(),mmol?"%.1f":"%.0f",threshold/(mmol?180.0:10.0));
    }
private static int integer(TextInputEditText e,int min,int max) {
    int v=Integer.parseInt(M3Page.text(e));
    if(v<min||v>max) throw new IllegalArgumentException();
    return v;
    }

static void show(MainActivity act,AllData all,long peerId) {
    final GarminAlarmConfig original=all.getGarminAlarms(act,peerId);
    if(original==null) return;
    final boolean mmol=Natives.getunit()==1;
    final int[] repeats={original.lowRepeat,original.highRepeat,original.lossRepeat};
    final int[] durations={original.lowDuration,original.highDuration,original.lossDuration};
    final int[] sounds={original.soundFlags};
    final int[] vibrations={original.vibrationFlags};
    final int[] tones={original.lowTone,original.highTone,original.lossTone};
    final boolean[] on={(original.flags&1)!=0,(original.flags&2)!=0,(original.flags&4)!=0};
    final String unit=act.getString(mmol?R.string.mmolL:R.string.mgdL);

    final M3Page page=M3Page.open(act,R.string.jm_share_garmin_alarms_title);
    page.help(R.string.garmin_alarms);
    final TextInputEditText[] values=new TextInputEditText[3];
    final View[] alertRows=new View[3];
    final int[] titles={R.string.lowglucosealarm,R.string.highglucosealarm,R.string.lossofsignalalarm};
    final int[] icons={R.drawable.ic_jm_arrow_downward,R.drawable.ic_jm_arrow_upward,R.drawable.ic_jm_signal_disconnected};
    for(int k=0;k<3;k++) {
        final int kind=k;
        LinearLayout sec=page.section(titles[k]);
        page.toggle(sec,icons[k],act.getString(R.string.active),null,() -> on[kind],c -> {
            on[kind]=c;
            values[kind].setEnabled(c);
            alertRows[kind].setEnabled(c);
            alertRows[kind].setAlpha(c?1f:0.38f);
            });
        String v=kind==0?shown(original.low,mmol):(kind==1?shown(original.high,mmol):Integer.toString(original.lossMinutes));
        values[k]=page.field(sec,act.getString(kind==2?R.string.jm_share_garmin_loss_after:R.string.jm_share_garmin_threshold),v,
            kind==2?M3Page.INTEGER:M3Page.DECIMAL,kind==2?act.getString(R.string.minutes):unit,null);
        values[k].setEnabled(on[k]);
        alertRows[k]=page.link(sec,R.drawable.ic_jm_vibration,act.getString(R.string.garmin_alarm_alert),null,
            () -> alertSettings(act,all,peerId,kind,repeats,durations,sounds,tones,vibrations));
        alertRows[k].setEnabled(on[k]);
        alertRows[k].setAlpha(on[k]?1f:0.38f);
        }
    LinearLayout st=page.section(R.string.jm_share_lv_sec_status);
    final TextView status=page.note(st,act.getString(all.garminAlarmStatus(peerId)));
    GarminAlarms.statusPeer=peerId;
    GarminAlarms.statusView=new WeakReference<>(status);
    page.onClose(() -> {
        if(GarminAlarms.statusView.get()==status) {
            GarminAlarms.statusView.clear();
            GarminAlarms.statusPeer=Long.MIN_VALUE;
            }
        });
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.closename),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            for(TextInputEditText e:values) M3Page.error(e,null);
            int field=0;
            try {
                String lowText=M3Page.text(values[0]),highText=M3Page.text(values[1]);
                field=0;
                int lo=!on[0]||lowText.equals(shown(original.low,mmol))?original.low:GarminAlarmConfig.threshold(Double.parseDouble(lowText.replace(',','.')),mmol);
                field=1;
                int hi=!on[1]||highText.equals(shown(original.high,mmol))?original.high:GarminAlarmConfig.threshold(Double.parseDouble(highText.replace(',','.')),mmol);
                field=2;
                int lossMinutes=on[2]?integer(values[2],1,4095):original.lossMinutes;
                GarminAlarmConfig c=new GarminAlarmConfig(original.revision,(on[0]?1:0)|(on[1]?2:0)|(on[2]?4:0),lo,hi,lossMinutes,
                    repeats[0],repeats[1],repeats[2],durations[0],durations[1],durations[2],
                    sounds[0],tones[0],tones[1],tones[2],vibrations[0]);
                if(!all.setGarminAlarms(act,peerId,c)) {
                    status.setText(R.string.garmin_alarm_save_failed);
                    return;
                    }
                status.setText(all.garminAlarmStatus(peerId));
                page.hideKeyboard();
                page.toast(act.getString(R.string.saved));
                }
            catch(IllegalArgumentException e) {
                M3Page.error(values[field],act.getString(R.string.garmin_alarm_invalid));
                }
            }));
    }

private static void alertSettings(MainActivity act,AllData all,long peerId,int kind,int[] repeats,int[] durations,int[] sounds,int[] tones,int[] vibrations) {
    final int support=all.garminAlarmSoundSupport(peerId);
    final boolean canEditSound=support==-1||support==2;
    final int vibrationSupport=all.garminAlarmVibrationSupport(peerId);
    final boolean canEditVibration=vibrationSupport==-1||vibrationSupport==2;
    final boolean[] sound={(sounds[0]&(1<<kind))!=0};
    final boolean[] vibration={(vibrations[0]&(1<<kind))!=0};
    final int[] tone={tones[kind]};
    final M3Page page=M3Page.open(act,new int[]{R.string.lowglucosealarm,R.string.highglucosealarm,R.string.lossofsignal}[kind]);
    page.help(R.string.garmin_alert);
    LinearLayout sec=page.section((CharSequence)null);
    final TextInputEditText duration=page.field(sec,act.getString(R.string.duraction),Integer.toString(durations[kind]),M3Page.INTEGER,act.getString(R.string.sec),null);
    final TextInputEditText repeat=page.field(sec,act.getString(R.string.minuteddeactivated),Integer.toString(repeats[kind]),M3Page.INTEGER,act.getString(R.string.minutes),null);
    MaterialSwitch s=page.toggle(sec,R.drawable.ic_jm_volume_up,act.getString(R.string.soundname),null,() -> sound[0],c -> sound[0]=c);
    s.setEnabled(canEditSound);
    final View toneBox=(View)page.choice(sec,act.getString(R.string.garmin_alarm_tone),act.getResources().getStringArray(R.array.garmin_alarm_tones),tone[0],i -> tone[0]=i).getParent().getParent();
    toneBox.setEnabled(canEditSound);
    MaterialSwitch vb=page.toggle(sec,R.drawable.ic_jm_vibration,act.getString(R.string.vibrationname),null,() -> vibration[0],c -> vibration[0]=c);
    vb.setEnabled(canEditVibration);
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            int r,d;
            try { d=integer(duration,0,65535); }
            catch(IllegalArgumentException e) { M3Page.error(duration,act.getString(R.string.garmin_alarm_invalid)); return; }
            try { r=integer(repeat,0,4095); }
            catch(IllegalArgumentException e) { M3Page.error(repeat,act.getString(R.string.garmin_alarm_invalid)); return; }
            repeats[kind]=r; durations[kind]=d;
            if(canEditSound) {
                sounds[0]=sound[0]?sounds[0]|(1<<kind):sounds[0]&~(1<<kind);
                tones[kind]=tone[0];
                }
            if(canEditVibration)
                vibrations[0]=vibration[0]?vibrations[0]|(1<<kind):vibrations[0]&~(1<<kind);
            page.dismiss();
            }));
    }
}
