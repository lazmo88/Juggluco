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

import static tk.glucodata.Natives.getCalibrator;
import static tk.glucodata.Natives.hasHistory;

import android.content.res.ColorStateList;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import tk.glucodata.m3.M3Page;

/*
 * Calibrations of one sensor (CalibrateList) as a Material 3 page: Stream /
 * History, one row per calibration (tap: show that time in the graph, bin:
 * delete), Settings opens the calibration settings.  Same Natives calls.
 */
public final class CalibrateListPage {
private CalibrateListPage() {}
private static final String LOG_ID="CalibrateListPage";

public static void show(MainActivity act,long sensorptr) {
    final M3Page page=M3Page.open(act,R.string.calibrations);
    String name=Natives.namefromSensorptr(sensorptr);
    if(name!=null)
        page.subtitle(name);
    page.help(R.string.calibrationslist);
    page.action(R.drawable.ic_jm_settings,act.getString(R.string.settings),() -> tk.glucodata.settings.CalibrationPage.show(act));
    final boolean[] render={false};
    final int[] which={0};
    if(hasHistory(sensorptr)) {
        LinearLayout kind=page.section((CharSequence)null);
        CharSequence[] items={act.getString(R.string.streamname),act.getString(R.string.historyname)};
        page.segmented(kind,null,items,0,i -> {
            which[0]=i;
            page.refresh();
            });
        }
    LinearLayout list=page.section(R.string.jm_sensors_section_calibrations);
    Runnable fill=() -> {
        list.removeAllViews();
        final int nr=Natives.calibrateNR(sensorptr,which[0]);
        int shown=0;
        for(int pos=0;pos<nr;pos++) {
            CaliPara para=new CaliPara();
            if(!getCalibrator(sensorptr,which[0],pos,para))
                continue;
            final int index=pos;
            final String calistr=String.format(Locale.getDefault(),"a = %.2f   b = %.2f",para.a,para.b);
            final String timestr=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(para.time));
            final long time=para.time;
            ViewGroup row=page.item(list,R.drawable.ic_jm_tune,timestr,calistr,() -> {
                Natives.setStartTime(time-5*60000);
                render[0]=true;
                while(MainActivity.doonback())
                    ;
                });
            MaterialButton delete=page.button(M3Page.Style.TEXT,"",R.drawable.ic_jm_delete,() ->
                M3Page.confirm(act,act.getString(R.string.delete_calibration),timestr+"\n\n"+calistr,act.getString(R.string.delete),() -> {
                    if(Natives.removeCalibrator(sensorptr,which[0],index)) {
                        render[0]=true;
                        page.refresh();
                        }
                    }));
            delete.setContentDescription(act.getString(R.string.delete));
            delete.setIconPadding(0);
            delete.setIconTint(ColorStateList.valueOf(page.color(com.google.android.material.R.attr.colorOnSurfaceVariant)));
            delete.setMinWidth(page.dp(48));
            delete.setMinimumWidth(page.dp(48));
            delete.setPadding(page.dp(12),0,page.dp(12),0);
            M3Page.end(row).addView(delete);
            shown++;
            }
        if(shown==0)
            page.empty(list,R.drawable.ic_jm_tune,act.getString(R.string.jm_sensors_no_calibrations),act.getString(R.string.jm_sensors_no_calibrations_body));
        };
    fill.run();
    page.refresher(fill);
    page.onClose(() -> {
        if(render[0])
            act.requestRender();
        });
    }
}
