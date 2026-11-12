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

import static tk.glucodata.settings.Settings.float2string;

import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import tk.glucodata.m3.M3Page;
import tk.glucodata.m3.TimeDialog;

/*
 * Reminders (setNumAlarm.mkviews) as Material 3 pages: the list of
 * reminders ("at least this amount under this label between start and
 * end"), tap to edit, New to add, and the reminder sound.  Each reminder is
 * stored with the old setters when its Save is pressed; closing the list
 * reschedules the alarms (NumAlarm.handlealarm) as before.
 */
public final class RemindersPage {
private RemindersPage() {}
private static final String LOG_ID="RemindersPage";

/* The amount labels a reminder can use: all but the last, as the old
 * LabelAdapter(...,1). */
static List<String> labels() {
    ArrayList<String> all=Natives.getLabels();
    if(all==null||all.isEmpty())
        return new ArrayList<>();
    return new ArrayList<>(all.subList(0,all.size()-1));
    }

public static void show(MainActivity act) {
    setNumAlarm.issaved=false;
    final M3Page page=M3Page.open(act,R.string.remindersname);
    page.help(R.string.reminders);
    page.onClose(() -> NumAlarm.handlealarm(act.getApplication()));
    final LinearLayout sec=page.section((CharSequence)null);
    final Runnable fill=() -> {
        sec.removeAllViews();
        final int n=Natives.getNumAlarmCount();
        if(n==0) {
            page.empty(sec,R.drawable.ic_jm_alarm,act.getString(R.string.jm_alerts_no_reminders),act.getString(R.string.jm_alerts_reminders_empty));
            return;
            }
        final ArrayList<String> all=Natives.getLabels();
        for(int i=0;i<n;i++) {
            final int pos=i;
            Object[] alarmobj=Natives.getNumAlarm(i);
            float value=(Float)alarmobj[0];
            short[] rest=(short[])alarmobj[1];
            final short type=rest[3];
            final String lab=(type<all.size())?all.get(type):"UNLABELED";
            page.link(sec,R.drawable.ic_jm_alarm,lab,act.getString(R.string.jm_alerts_reminder_summary,float2string(value),TimeDialog.format(rest[0]),TimeDialog.format(rest[1])),() -> edit(act,page,pos));
            }
        };
    fill.run();
    page.refresher(fill);
    page.buttons(null,page.button(M3Page.Style.FILLED,act.getString(R.string.newname),R.drawable.ic_jm_add,() -> edit(act,page,-1)));

    LinearLayout sound=page.section(R.string.soundname);
    final ViewGroup[] ring={null};
    ring[0]=page.link(sound,R.drawable.ic_jm_music_note,act.getString(R.string.jm_alerts_sound_title),RingTonePage.summary(act,3),() -> {
        M3Page sub=RingTonePage.open(act,3,null);
        if(sub!=null&&sub!=page)
            sub.onClose(() -> M3Page.setSummary(ring[0],RingTonePage.summary(act,3)));
        });
    }

/* setNumAlarm.mkitemlayout + fillitemlayout/emptyitemlayout; pos<0 is new. */
static void edit(MainActivity act,M3Page list,int alarmpos) {
    final List<String> labels=labels();
    final int[] labelsel={labels.isEmpty()?-1:0};
    final int[] minutes={0,0};
    String valuestr="";
    if(alarmpos>=0) {
        Object[] alarmobj=Natives.getNumAlarm(alarmpos);
        float flvalue=(Float)alarmobj[0];
        short[] rest=(short[])alarmobj[1];
        minutes[0]=rest[0];
        minutes[1]=rest[1];
        labelsel[0]=rest[3];
        valuestr=float2string(flvalue);
        }
    final M3Page page=M3Page.open(act,alarmpos<0?R.string.jm_alerts_new_reminder:R.string.jm_alerts_reminder);
    page.help(R.string.reminders);
    page.onClose(list::refresh);

    LinearLayout what=page.section((CharSequence)null);
    final MaterialAutoCompleteTextView label=page.choice(what,act.getString(R.string.jm_alerts_label),labels,labelsel[0],i -> labelsel[0]=i);
    final TextInputEditText value=page.field(what,act.getString(R.string.jm_alerts_amount),valuestr,M3Page.DECIMAL,null,null);
    if(labels.isEmpty())
        page.note(what,act.getString(R.string.jm_alerts_no_labels));

    LinearLayout when=page.section(R.string.jm_alerts_section_window);
    final ViewGroup[] rows={null,null};
    final int[] titles={R.string.start,R.string.jm_alerts_end};
    final int[] icons={R.drawable.ic_jm_schedule,R.drawable.ic_jm_alarm};
    for(int i=0;i<2;i++) {
        final int ind=i;
        rows[i]=page.link(when,icons[i],act.getString(titles[i]),TimeDialog.format(minutes[i]),() -> {
            page.hideKeyboard();
            TimeDialog.pick(act,act.getString(titles[ind]),minutes[ind],min -> {
                minutes[ind]=min;
                M3Page.setSummary(rows[ind],TimeDialog.format(min));
                });
            });
        }
    page.note(when,act.getString(R.string.jm_alerts_window_note));

    if(alarmpos>=0) {
        page.bottomBar(page.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,() -> {
            page.hideKeyboard();
            Object[] alarmobj=Natives.getNumAlarm(alarmpos);
            short type=((short[])alarmobj[1])[3];
            ArrayList<String> all=Natives.getLabels();
            String lab=type<all.size()?all.get(type):"";
            TimeDialog.confirm(act,act.getString(R.string.deletereminder),lab+" "+float2string((Float)alarmobj[0]),act.getString(R.string.delete),() -> {
                Natives.delNumAlarm(alarmpos);
                page.dismiss();
                });
            }));
        }
    page.bottomBar(page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
        setNumAlarm.issaved=true;
        page.hideKeyboard();
        M3Page.error(value,null);
        if(labelsel[0]<0) {
            Log.e(LOG_ID,"labelsel="+labelsel[0]);
            android.view.View v=label;
            while(v!=null&&!(v instanceof com.google.android.material.textfield.TextInputLayout))
                v=(android.view.View)v.getParent();
            if(v!=null)
                ((com.google.android.material.textfield.TextInputLayout)v).setError(act.getString(R.string.jm_alerts_choose_label));
            return;
            }
        float val=M3Page.number(value);
        if(Float.isNaN(val)) {
            M3Page.error(value,act.getString(R.string.jm_invalid_number));
            return;
            }
        if(minutes[0]==minutes[1]) {
            page.toast(act.getString(R.string.jm_alerts_times_differ));
            return;
            }
        if(alarmpos>=0)
            Natives.delNumAlarm(alarmpos);
        Natives.setNumAlarm(labelsel[0],val,minutes[0],minutes[1]);
        page.dismiss();
        }));
    }
}
