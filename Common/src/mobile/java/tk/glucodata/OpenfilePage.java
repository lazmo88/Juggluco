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

import static android.text.Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE;
import static android.text.Html.fromHtml;

import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import tk.glucodata.m3.M3Page;

/*
 * Openfile.showchoice as a Material 3 page: explains where to get the Libre
 * library file, lets the user pick it (Open) or continue without a sensor.
 */
public final class OpenfilePage {
private OpenfilePage() {}
private static M3Page shown=null;

public static void show(MainActivity act,Object o,boolean getback) {
    final Openfile file=(Openfile)o;
    if(shown!=null&&shown.isOpen())
        return;
    final M3Page page=M3Page.open(act,R.string.jm_share_lib_title);
    shown=page;
    page.onClose(() -> { if(shown==page) shown=null; });
    LinearLayout sec=page.section((CharSequence)null);
    TextView text=page.note(sec,fromHtml(act.getString(R.string.getlib),TO_HTML_PARAGRAPH_LINES_CONSECUTIVE));
    text.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
    text.setTextIsSelectable(true);
    text.setMovementMethod(LinkMovementMethod.getInstance());
    text.setLinksClickable(true);
    if(file.mess!=null&&!file.mess.isEmpty()) {
        LinearLayout st=page.section(R.string.jm_share_lv_sec_status);
        TextView status=page.note(st,file.mess);
        status.setTextColor(page.color(com.google.android.material.R.attr.colorError));
        }
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.withoutsensor),0,() -> {
            page.dismiss();
            if(getback) {
                Natives.setusebluetooth(false);
                help.help(R.string.introhelp,act);
                }
            else
                act.openfile=null;
            }),
        page.button(M3Page.Style.FILLED,act.getString(R.string.open),R.drawable.ic_jm_folder_open,() -> {
            page.dismiss();
            file.openlib(act);
            }));
    }
}
