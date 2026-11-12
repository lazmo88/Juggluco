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

package tk.glucodata.m3;

import tk.glucodata.MainActivity;
import tk.glucodata.PhoneUi;
import tk.glucodata.R;
import tk.glucodata.RingTones;

/* Alarms, reminders, speech, notifications, floating glucose, shortcuts, profiles. */
final class AlertScreens {
private AlertScreens() {}

static void register() {
    PhoneUi.register("talker",(act,args) -> tk.glucodata.TalkerPage.show(act));
    /* Settings.alarmsettings(): alarms, with advanced alarms as a sub page. */
    PhoneUi.register("settings.alarms",(act,args) -> tk.glucodata.settings.AlarmsPage.show(act));
    /* Settings.scheduleProfiles(): from Alarms and Speech. */
    PhoneUi.register("settings.scheduleprofiles",(act,args) -> tk.glucodata.settings.ProfileSchedulesPage.show(act));
    /* RingTones.mkviews(): args = the RingTones, its label (null: reminders). */
    PhoneUi.register("ringtones",(act,args) -> tk.glucodata.RingTonePage.show(act,(RingTones)args[0],(String)args[1]));
    /* setNumAlarm.mkviews(): reminders. */
    PhoneUi.register("reminders",(act,args) -> tk.glucodata.RemindersPage.show(act));
    /* Floating.shoulduseadb(): no overlay permission screen on this phone. */
    PhoneUi.register("floating.overlaypermission",AlertScreens::overlayPermission);
    /* FloatingConfig and Shortcuts are phone-only classes and open their
     * Material 3 pages directly. */
    }

private static void overlayPermission(MainActivity act,Object[] args) {
    M3Page.message(act,act.getString(R.string.overlaypermission),
        act.getString(R.string.jm_alerts_needs_permission)+"\n\n"+act.getString(R.string.overlaypermissionmessage));
    }
}
