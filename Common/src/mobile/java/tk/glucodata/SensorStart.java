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

import android.widget.LinearLayout;

import com.google.android.material.materialswitch.MaterialSwitch;

import tk.glucodata.m3.M3Page;

/* Messages after a sensor was started by NFC scan, as MaterialAlertDialogs
 * (were cream boxes over the graph: ScanNfcV.newsensor and
 * MainActivity.activateresult). */
public final class SensorStart {
private SensorStart() {}
private static final String LOG_ID="SensorStart";

/* ScanNfcV.newsensor: sensor ready (or ready in n minutes), with the option to
 * put the sensor end date in the calendar app.  Same fields as before. */
public static void newSensor(MainActivity act,String text,String name) {
    long timedata=Natives.getSensorEndData(name);
    final long endtime=(timedata&0xFFFFFFFFL)*1000L;
    final boolean stillused=endtime>System.currentTimeMillis();
    var themed=M3Page.themed(act);
    LinearLayout box=SensorUi.dialogBox(themed);
    SensorUi.dialogText(themed,box,text);
    final MaterialSwitch calendar=stillused?SensorUi.dialogSwitch(themed,box,act.getString(R.string.addsensorenddate),(timedata>>32)!=0L):null;
    var dialog=M3Page.dialog(act)
        .setTitle(act.getString(R.string.sensor)+" "+name)
        .setIcon(R.drawable.ic_jm_check_circle)
        .setView(box)
        .setPositiveButton(R.string.ok,(d,w) -> {
            if(stillused&&calendar!=null&&calendar.isChecked())
                ScanNfcV.insertcalendar(act,name,endtime);
            else
                ScanNfcV.askcalendar=false;
            })
        .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.setOnCancelListener(d -> ScanNfcV.askcalendar=false);
    dialog.show();
    }

/* MainActivity.activateresult */
public static void activateResult(MainActivity act,boolean res) {
    var dialog=M3Page.dialog(act)
        .setTitle(act.getString(res?R.string.jm_sensors_activated:R.string.jm_sensors_activation_failed))
        .setIcon(res?R.drawable.ic_jm_check_circle:R.drawable.ic_jm_error)
        .setMessage(res?R.string.jm_sensors_activated_body:R.string.jm_sensors_activation_failed_body)
        .setPositiveButton(R.string.ok,(d,w) -> act.requestRender())
        .setOnCancelListener(d -> act.requestRender())
        .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
    }
}
