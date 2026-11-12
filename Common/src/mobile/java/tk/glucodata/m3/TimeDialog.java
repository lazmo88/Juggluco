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

import android.widget.TimePicker;

import android.content.Context;
import android.content.DialogInterface;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

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
    picker.setIs24HourView(tk.glucodata.Applic.hour24); // the app's clock setting
    picker.setCurrentHour((minutes/60)%24); // setHour() needs API 23
    picker.setCurrentMinute(minutes%60);
    builder.setTitle(title)
        .setView(picker)
        .setNegativeButton(android.R.string.cancel,null)
        .setPositiveButton(android.R.string.ok,(d,w) -> {
            picker.clearFocus(); // commit a typed value
            picked.set(picker.getCurrentHour()*60+picker.getCurrentMinute());
            });
    show(builder);
    }

/*
 * Dialog buttons came out #FF00FF (AppCompat's "unresolved colour" fallback)
 * with M3Page.dialog() on the emulator, so the alerts screens show their
 * dialogs through here and give the buttons the theme's primary colour.
 */
public static AlertDialog show(MaterialAlertDialogBuilder builder) {
    AlertDialog dialog=builder.create();
    final Context ctx=builder.getContext();
    dialog.setOnShowListener(d -> {
        int primary=M3Page.color(ctx,androidx.appcompat.R.attr.colorPrimary);
        for(int which:new int[]{DialogInterface.BUTTON_POSITIVE,DialogInterface.BUTTON_NEGATIVE,DialogInterface.BUTTON_NEUTRAL}) {
            Button b=((AlertDialog)d).getButton(which);
            if(b!=null)
                b.setTextColor(primary);
            }
        });
    dialog.show();
    return dialog;
    }

public static void confirm(Context base,CharSequence title,@Nullable CharSequence message,CharSequence yes,Runnable onYes) {
    MaterialAlertDialogBuilder b=M3Page.dialog(base).setTitle(title).setPositiveButton(yes,(d,w) -> onYes.run()).setNegativeButton(android.R.string.cancel,null);
    if(message!=null)
        b.setMessage(message);
    show(b);
    }

public static void message(Context base,@Nullable CharSequence title,CharSequence message) {
    MaterialAlertDialogBuilder b=M3Page.dialog(base).setMessage(message).setPositiveButton(android.R.string.ok,null);
    if(title!=null)
        b.setTitle(title);
    show(b);
    }

/* Time of day in the app's 12/24 hour setting. */
public static String format(int minutes) {
    final int h=(minutes/60)%24,m=minutes%60;
    if(tk.glucodata.Applic.hour24)
        return String.format(Locale.US,"%02d:%02d",h,m);
    return String.format(Locale.US,"%d:%02d %s",(h%12==0)?12:h%12,m,h<12?"AM":"PM");
    }
}
