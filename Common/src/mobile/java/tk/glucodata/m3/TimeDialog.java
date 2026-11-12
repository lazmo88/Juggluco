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

import android.text.format.DateFormat;
import android.widget.TimePicker;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

import tk.glucodata.MainActivity;

/*
 * Time of day picker for the alerts screens (reminders, profile schedules):
 * a MaterialAlertDialog around the platform clock picker, in the page's M3
 * colours.  MaterialTimePicker is not used: it is a DialogFragment that takes
 * its theme from the activity, which still has the old Juggluco theme.
 */
@SuppressWarnings("deprecation")
public final class TimeDialog {
private TimeDialog() {}

public interface Picked { void set(int minutes); }

/* minutes: minutes after midnight. */
public static void pick(MainActivity act,CharSequence title,int minutes,Picked picked) {
    MaterialAlertDialogBuilder builder=M3Page.dialog(act);
    TimePicker picker=new TimePicker(builder.getContext());
    picker.setIs24HourView(DateFormat.is24HourFormat(act));
    picker.setCurrentHour((minutes/60)%24); // setHour() needs API 23
    picker.setCurrentMinute(minutes%60);
    builder.setTitle(title)
        .setView(picker)
        .setNegativeButton(android.R.string.cancel,null)
        .setPositiveButton(android.R.string.ok,(d,w) -> {
            picker.clearFocus(); // commit a typed value
            picked.set(picker.getCurrentHour()*60+picker.getCurrentMinute());
            })
        .show();
    }

/* hh:mm as the old screens showed it. */
public static String format(int minutes) {
    return String.format(Locale.US,"%02d:%02d",minutes/60,minutes%60);
    }
}
