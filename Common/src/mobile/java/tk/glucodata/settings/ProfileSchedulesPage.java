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

import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.List;

import tk.glucodata.Applic;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.NumAlarm;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;
import tk.glucodata.m3.TimeDialog;

/*
 * Profile schedules (Settings.scheduleProfiles / changeProfile) as Material 3
 * pages: a list of "at hh:mm switch to profile" items, tap to edit, New to
 * add.  Each item is stored with the old setters when its Save is pressed.
 */
public final class ProfileSchedulesPage {
private ProfileSchedulesPage() {}

private static final int MAX=10;

static String profileName(MainActivity act,int pos) {
    List<String> names=AlarmsPage.profileNames(act);
    return pos>=0&&pos<names.size()?names.get(pos):String.valueOf(pos);
    }

public static void show(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.schedules);
    page.help(R.string.schedulehelp);
    final LinearLayout sec=page.section((CharSequence)null);
    final Runnable fill=() -> {
        sec.removeAllViews();
        final int n=Natives.nrScheduledProfiles();
        if(n==0) {
            page.empty(sec,R.drawable.ic_jm_schedule,act.getString(R.string.jm_alerts_no_schedules),act.getString(R.string.jm_alerts_schedules_empty));
            return;
            }
        for(int i=0;i<n;i++) {
            final int index=i;
            short[] minpro=Natives.getScheduleProfile(i);
            page.link(sec,R.drawable.ic_jm_schedule,TimeDialog.format(minpro[0]),profileName(act,minpro[1]),() -> edit(act,page,index));
            }
        };
    fill.run();
    page.refresher(fill);
    page.buttons(null,page.button(M3Page.Style.FILLED,act.getString(R.string.newname),R.drawable.ic_jm_add,() -> {
        if(Natives.nrScheduledProfiles()>=MAX) {
            page.toast(act.getString(R.string.jm_alerts_too_many_schedules));
            return;
            }
        edit(act,page,-1);
        }));
    }

/* Settings.changeProfile(): wasindex<0 is a new item. */
static void edit(MainActivity act,M3Page list,int wasindex) {
    final int index=wasindex<0?Natives.nrScheduledProfiles():wasindex;
    final short[] minpro=wasindex<0?new short[]{0,0}:Natives.getScheduleProfile(index);
    final M3Page page=M3Page.open(act,wasindex<0?R.string.jm_alerts_new_schedule:R.string.jm_alerts_schedule);
    page.help(R.string.schedulehelp);
    page.onClose(list::refresh);
    LinearLayout sec=page.section((CharSequence)null);
    final ViewGroup[] time={null};
    time[0]=page.link(sec,R.drawable.ic_jm_schedule,act.getString(R.string.jm_alerts_switch_at),TimeDialog.format(minpro[0]),() ->
        TimeDialog.pick(act,act.getString(R.string.jm_alerts_switch_at),minpro[0],min -> {
            minpro[0]=(short)min;
            M3Page.setSummary(time[0],TimeDialog.format(min));
            }));
    page.choice(sec,act.getString(R.string.jm_alerts_switch_to),AlarmsPage.profileNames(act),minpro[1],i -> minpro[1]=(short)i);

    final M3Page.Style delStyle=M3Page.Style.TEXT;
    if(wasindex>=0) {
        page.bottomBar(page.button(delStyle,act.getString(R.string.delete),R.drawable.ic_jm_delete,() ->
            M3Page.confirm(act,act.getString(R.string.jm_alerts_delete_schedule),
                TimeDialog.format(minpro[0])+" → "+profileName(act,minpro[1]),act.getString(R.string.delete),() -> {
                    Natives.removeScheduleProfile(index);
                    NumAlarm.handlealarm(Applic.app);
                    page.dismiss();
                    })));
        }
    page.bottomBar(page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
        int induit=Natives.setScheduleProfile(wasindex,minpro[0],minpro[1]);
        if(induit>=0) {
            NumAlarm.handlealarm(Applic.app);
            page.dismiss();
            }
        else
            page.toast(act.getString(R.string.jm_alerts_too_many_schedules));
        }));
    }
}
