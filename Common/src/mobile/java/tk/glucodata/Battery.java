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
/*                                                                                   */
/*      Fri Jan 27 15:32:11 CET 2023                                                 */


package tk.glucodata;

import static tk.glucodata.MainActivity.IGNORE_BATTERY_OPTIMIZATION_SETTINGS;

import android.content.Intent;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;

import tk.glucodata.m3.M3Page;

/* Battery optimisation ("Doze mode?") as a Material 3 page.  The switch shows
 * whether Android ignores battery optimisations for Juggluco; tapping it opens
 * Android's setting, as the old screen did (the page closes and is shown again
 * when Android returns, see MainActivity IGNORE_BATTERY_OPTIMIZATION_SETTINGS). */
class Battery {
static private final String LOG_ID="Battery";

static void batteryscreen(MainActivity context,View parent) {
    if(android.os.Build.VERSION.SDK_INT<android.os.Build.VERSION_CODES.M)
        return;
    M3Page top=M3Page.top(context);
    if(top!=null&&top.isOpen()&&top.toolbar.getTitle()!=null&&top.toolbar.getTitle().toString().equals(context.getString(R.string.jm_sensors_battery)))
        top.dismiss(); // returning from Android's setting: show it fresh
    final M3Page page=M3Page.open(context,R.string.jm_sensors_battery);
    LinearLayout state=page.section(R.string.jm_sensors_section_status);
    ViewGroup row=page.row(state,R.drawable.ic_jm_battery_saver,context.getString(R.string.dozeupdate),null);
    MaterialSwitch sw=new MaterialSwitch(page.ctx);
    sw.setClickable(false);
    sw.setFocusable(false);
    sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    M3Page.end(row).addView(sw);
    Runnable show=() -> {
        boolean free=SensorsPage.ignoringBattery(context);
        sw.setChecked(free);
        M3Page.setSummary(row,context.getString(free?R.string.jm_sensors_battery_free:R.string.jm_sensors_battery_restricted));
        };
    show.run();
    page.refresher(show);
    row.setContentDescription(context.getString(R.string.dozeupdate));
    row.setOnClickListener(v -> {
        page.dismiss();
        try {
            context.startActivityForResult(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            }
        catch(Throwable th) {
            Log.stack(LOG_ID,"ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",th);
            }
        });
    page.note(state,context.getString(R.string.jm_sensors_battery_hint));

    LinearLayout about=page.section(R.string.jm_sensors_section_about);
    String html=context.getString(R.string.battery);
    int h=html.indexOf("</h1>");
    if(h>=0)
        html=html.substring(h+5);
    TextView info=page.note(about,SensorUi.html(html));
    info.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    info.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
    info.setLinkTextColor(page.color(com.google.android.material.R.attr.colorPrimary));
    info.setMovementMethod(LinkMovementMethod.getInstance());
    info.setPadding(page.dp(16),page.dp(12),page.dp(16),page.dp(12));
    }
}
