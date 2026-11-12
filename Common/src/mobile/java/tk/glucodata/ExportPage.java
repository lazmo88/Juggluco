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
import android.widget.TextView;

import com.google.android.material.textfield.TextInputEditText;

import tk.glucodata.m3.M3Page;
import tk.glucodata.settings.ExchangePage;

/*
 * Export (Dialogs.showexport) as a Material 3 page: number of days, the
 * calibrated/extended options and one row per kind of export.  Each row asks
 * Android where to save (ACTION_CREATE_DOCUMENT) exactly as before; the result
 * that MainActivity writes to dialogs.exportlabel is shown in the Result card.
 */
public final class ExportPage {
private ExportPage() {}

public static void show(MainActivity act,Dialogs dialogs,boolean fromMenu) {
    final M3Page page=M3Page.open(act,R.string.jm_share_export_title);
    page.help(R.string.helpexport);

    LinearLayout range=page.section(R.string.jm_share_export_sec_range);
    long hour24=1000L*60*60*24;
    long endtime=Natives.getendtime();
    long daysnr=(endtime-Natives.oldestdatatime()+hour24-1)/hour24;
    final TextInputEditText days=page.field(range,act.getString(R.string.days),Long.toString(daysnr),M3Page.DECIMAL,null,null);
    page.toggle(range,R.drawable.ic_jm_tune,act.getString(R.string.calibrated),act.getString(R.string.jm_share_export_calibrated_summary),
        () -> dialogs.isCalibrated,on -> dialogs.isCalibrated=on);

    LinearLayout result=page.section(R.string.jm_share_export_sec_result);
    final TextView label=page.note(result,act.getString(R.string.exporthelp));
    label.setTextIsSelectable(true);
    dialogs.exportlabel=label;

    LinearLayout glucose=page.section(R.string.jm_share_export_sec_glucose);
    row(page,glucose,dialogs,days,label,R.drawable.ic_jm_contactless,R.string.scansname,1,R.string.jm_share_export_tsv);
    row(page,glucose,dialogs,days,label,R.drawable.ic_jm_history,R.string.historyname,3,R.string.jm_share_export_tsv);
    row(page,glucose,dialogs,days,label,R.drawable.ic_jm_show_chart,R.string.streamname,2,R.string.jm_share_export_tsv);
    row(page,glucose,dialogs,days,label,R.drawable.ic_jm_cloud,R.string.libreviewname,5,R.string.jm_share_export_csv);

    LinearLayout other=page.section(R.string.jm_share_export_sec_other);
    row(page,other,dialogs,days,label,R.drawable.ic_jm_medication,R.string.amountsname,0,R.string.jm_share_export_tsv);
    row(page,other,dialogs,days,label,R.drawable.ic_jm_restaurant,R.string.mealsname,4,R.string.jm_share_export_html);
    row(page,other,dialogs,days,label,R.drawable.ic_jm_directions_run,R.string.activity_import,6,R.string.jm_share_export_tsv);
    page.toggle(other,0,act.getString(R.string.activity_export_extended),null,
        () -> dialogs.activitiesExtended,on -> dialogs.activitiesExtended=on);

    page.onClose(() -> {
        if(fromMenu&&M3Page.top(act)==null&&Menus.on)
            Menus.show(act);
        ExchangePage.refreshTop(act);
        });
    }

/* Old exportbutton(): parse days, then the same exporter call per type. */
private static void row(M3Page page,LinearLayout parent,Dialogs dialogs,TextInputEditText days,TextView label,int icon,int name,int type,int format) {
    final MainActivity act=page.act;
    final String prefix=act.getString(name);
    page.link(parent,icon,prefix,act.getString(format),() -> {
        float daynr=M3Page.number(days);
        if(Float.isNaN(daynr)) {
            M3Page.error(days,act.getString(R.string.jm_share_export_days_bad));
            label.setText("I don't understand '"+M3Page.text(days)+"'");
            return;
            }
        M3Page.error(days,null);
        page.hideKeyboard();
        dialogs.exportlabel=label;
        label.setText(R.string.jm_share_export_waiting);
        switch(type) {
            case 4: Dialogs.algexporter(act,type,prefix,".html",daynr);break;
            case 5: Dialogs.algexporter(act,type|(dialogs.isCalibrated?8:0),prefix,".csv",daynr);break;
            case 6: Dialogs.exporter(act,dialogs.activitiesExtended?7:6,prefix+(dialogs.activitiesExtended?"-extended":""),daynr);break;
            default: Dialogs.exporter(act,type|(dialogs.isCalibrated?8:0),prefix,daynr);
            }
        });
    }
}
