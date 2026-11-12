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

import static tk.glucodata.Natives.setshowcalibratedstream;

import android.widget.LinearLayout;

import java.util.ArrayList;

import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;

/*
 * Settings → Calibration as a Material 3 page.  Values apply immediately,
 * like the old screen (Calibration.show), with the same setters.
 */
public final class CalibrationPage {
private CalibrationPage() {}

/* Label name without the old screen's trailing colon. */
static String label(MainActivity act,int id) {
    String s=act.getString(id).trim();
    if(s.endsWith(":"))
        s=s.substring(0,s.length()-1).trim();
    return s;
    }

public static void show(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.calibration);
    page.help(R.string.calibrationhelp);

    LinearLayout use=page.section(R.string.jm_sensors_section_calibrate);
    page.toggle(use,R.drawable.ic_jm_tune,act.getString(R.string.active),act.getString(R.string.jm_sensors_cal_active_summary),
        Natives::getDoCalibrate,on -> {
            Natives.setDoCalibrate(on);
            if(on)
                setshowcalibratedstream(true);
            act.requestRender();
            });
    page.toggle(use,R.drawable.ic_jm_show_chart,act.getString(R.string.calibrate_a),act.getString(R.string.jm_sensors_cal_slope_summary),
        Natives::getCalibrateA,Natives::setCalibrateA);
    page.toggle(use,R.drawable.ic_jm_history,act.getString(R.string.calibratepast),act.getString(R.string.jm_sensors_cal_past_summary),
        Natives::getCalibratePast,on -> {
            Natives.setCalibratePast(on);
            act.requestRender();
            });
    page.toggle(use,R.drawable.ic_jm_list,act.getString(R.string.allvalues),act.getString(R.string.jm_sensors_cal_all_summary),
        Natives::getAllValues,on -> {
            Natives.setAllValues(on);
            act.requestRender();
            });

    LinearLayout blood=page.section(R.string.jm_sensors_section_blood);
    ArrayList<String> labels=Natives.getLabels();
    page.choice(blood,label(act,R.string.bloodvar),labels,Natives.getbloodvar(),i -> Natives.setbloodvar((byte)i));
    page.note(blood,act.getString(R.string.jm_sensors_cal_blood_summary));
    }
}
