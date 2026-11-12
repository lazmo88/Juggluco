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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Build;
import android.text.Html;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Calendar;

import tk.glucodata.m3.M3Page;

/* Small helpers shared by the Material 3 sensor area pages (sensors,
 * calibration, meters, pens, sensor start dialogs).  Phone only. */
public final class SensorUi {
private SensorUi() {}

public interface TimeSet { void set(long millis); }

/* Juggluco's sensor texts are HTML. */
public static Spanned html(@Nullable String text) {
    if(text==null)
        text="";
    if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N)
        return Html.fromHtml(text,Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE);
    return Html.fromHtml(text);
    }

/* Let a row's summary use more than the default two lines. */
public static void summaryLines(View row,int lines) {
    TextView summary=row.findViewById(R.id.jm_row_summary);
    if(summary!=null)
        summary.setMaxLines(lines);
    }

public static String date(Context ctx,long millis) {
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.DEFAULT).format(millis);
    }
public static String time(Context ctx,long millis) {
    return DateFormat.getTimeFormat(ctx).format(millis);
    }

/* Date picker in the page's Material theme; keeps the time of day. */
public static void pickDate(MainActivity act,long millis,TimeSet set) {
    final Calendar cal=Calendar.getInstance();
    cal.setTimeInMillis(millis);
    DatePickerDialog dialog=new DatePickerDialog(M3Page.themed(act),(view,year,month,day) -> {
        cal.set(Calendar.YEAR,year);
        cal.set(Calendar.MONTH,month);
        cal.set(Calendar.DAY_OF_MONTH,day);
        set.set(cal.getTimeInMillis());
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH));
    dialog.show();
    }

/* Time picker in the page's Material theme; keeps the date. */
public static void pickTime(MainActivity act,long millis,TimeSet set) {
    final Calendar cal=Calendar.getInstance();
    cal.setTimeInMillis(millis);
    TimePickerDialog dialog=new TimePickerDialog(M3Page.themed(act),(view,hour,minute) -> {
        cal.set(Calendar.HOUR_OF_DAY,hour);
        cal.set(Calendar.MINUTE,minute);
        set.set(cal.getTimeInMillis());
        },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),DateFormat.is24HourFormat(act));
    dialog.show();
    }

/* Body for a MaterialAlertDialog: optional message plus a switch row. */
public static MaterialSwitch dialogSwitch(Context themed,LinearLayout box,CharSequence label,boolean checked) {
    MaterialSwitch sw=new MaterialSwitch(themed);
    sw.setText(label);
    sw.setChecked(checked);
    sw.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    sw.setTextColor(M3Page.color(themed,com.google.android.material.R.attr.colorOnSurface));
    sw.setMinHeight(M3Page.dp(themed,48));
    box.addView(sw,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return sw;
    }

public static LinearLayout dialogBox(Context themed) {
    LinearLayout box=new LinearLayout(themed);
    box.setOrientation(LinearLayout.VERTICAL);
    final int side=M3Page.dp(themed,24);
    box.setPadding(side,M3Page.dp(themed,8),side,0);
    box.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return box;
    }

public static TextView dialogText(Context themed,LinearLayout box,CharSequence text) {
    TextView view=new TextView(themed);
    view.setText(text);
    view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    view.setTextColor(M3Page.color(themed,com.google.android.material.R.attr.colorOnSurfaceVariant));
    view.setPadding(0,0,0,M3Page.dp(themed,8));
    box.addView(view,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return view;
    }
}
