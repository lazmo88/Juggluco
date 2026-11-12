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

package tk.glucodata;

import tk.glucodata.m3.M3Page;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.LinearLayout;

/* Phone About (GlucoseCurve.doabout): version facts as list rows (tap copies
 * all of them), then the about text with its links. */
final class AboutPage {
private AboutPage() {}

static void show(MainActivity act) {
    M3Page page=M3Page.open(act,R.string.aboutname);
    final String facts="Version Code: "+BuildConfig.VERSION_CODE+"\nVersion Name: "+BuildConfig.VERSION_NAME+"\n"+Natives.getCPUarch()+"\nBuild time: "+BuildTime.TIME;
    final Runnable copy=() -> {
        ClipboardManager clip=(ClipboardManager)act.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clip!=null) {
            clip.setPrimaryClip(ClipData.newPlainText("Juggluco",facts));
            page.toast(act.getString(R.string.jm_data_copied));
            }
        };
    page.action(R.drawable.ic_jm_content_copy,act.getString(R.string.jm_data_copy),copy);
    LinearLayout sec=page.section(R.string.jm_data_version);
    page.item(sec,R.drawable.ic_jm_info,"Version Name",BuildConfig.VERSION_NAME,copy);
    page.item(sec,R.drawable.ic_jm_tag,"Version Code",String.valueOf(BuildConfig.VERSION_CODE),copy);
    page.item(sec,R.drawable.ic_jm_memory,act.getString(R.string.jm_data_architecture),Natives.getCPUarch(),copy);
    page.item(sec,R.drawable.ic_jm_build,"Build time",BuildTime.TIME,copy);
    HelpPage.htmlBody(page,act.getString(R.string.about));
    }
}
