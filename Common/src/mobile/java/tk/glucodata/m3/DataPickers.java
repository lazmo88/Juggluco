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

package tk.glucodata.m3;

import android.text.format.DateFormat;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.Calendar;
import java.util.TimeZone;

import tk.glucodata.MainActivity;
import tk.glucodata.R;

import static tk.glucodata.Natives.getInvertColors;

/* Material date and time pickers for the data screens (amounts, list, graph
 * date).  The activity is AppCompat, so the pickers get their own full M3
 * themes (values/m3_styles_data.xml) that follow Juggluco's Dark mode switch. */
public final class DataPickers {
private DataPickers() {}

public interface DateSet { void set(int year,int month,int day); }
public interface TimeSet { void set(int hour,int minute); }

/* month is 0-based like Calendar.MONTH. */
public static void date(MainActivity act,CharSequence title,long localMillis,DateSet set,Runnable dismissed) {
    Calendar local=Calendar.getInstance();
    local.setTimeInMillis(localMillis);
    Calendar utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    utc.clear();
    utc.set(local.get(Calendar.YEAR),local.get(Calendar.MONTH),local.get(Calendar.DAY_OF_MONTH));
    MaterialDatePicker<Long> picker=MaterialDatePicker.Builder.datePicker()
        .setTitleText(title)
        .setSelection(utc.getTimeInMillis())
        .setTheme(getInvertColors()?R.style.JmData_Calendar_Dark:R.style.JmData_Calendar_Light)
        .build();
    final boolean[] chosen={false};
    picker.addOnPositiveButtonClickListener(sel -> {
        if(sel==null)
            return;
        chosen[0]=true;
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(sel);
        set.set(c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH));
        });
    picker.addOnDismissListener(d -> {
        if(!chosen[0]&&dismissed!=null)
            dismissed.run();
        });
    picker.show(act.getSupportFragmentManager(),"jm_data_date");
    }

public static void time(MainActivity act,CharSequence title,int hour,int minute,TimeSet set) {
    MaterialTimePicker picker=new MaterialTimePicker.Builder()
        .setTimeFormat(DateFormat.is24HourFormat(act)?TimeFormat.CLOCK_24H:TimeFormat.CLOCK_12H)
        .setHour(hour)
        .setMinute(minute)
        .setTitleText(title)
        .setTheme(getInvertColors()?R.style.JmData_Clock_Dark:R.style.JmData_Clock_Light)
        .build();
    picker.addOnPositiveButtonClickListener(v -> set.set(picker.getHour(),picker.getMinute()));
    picker.show(act.getSupportFragmentManager(),"jm_data_time");
    }
}
