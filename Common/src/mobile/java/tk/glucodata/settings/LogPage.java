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

import static tk.glucodata.Log.doLog;

import android.content.Intent;
import android.text.format.Formatter;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;

/*
 * Settings > Logging on the phone: the app's trace log and the logcat copy.
 * Same switches and actions as LogConfig (which the watch still uses); only
 * present in builds with logging compiled in.
 */
public final class LogPage {
private LogPage() {}

private static final String LOG_ID="LogPage";

public static void show(MainActivity act) {
    if(!doLog)
        return;
    final M3Page page=M3Page.open(act,R.string.logging);
    page.help(R.string.loghelp);
    page.note(null,act.getString(R.string.jm_log_note));

    final LinearLayout trace=page.section("trace.log");
    page.toggle(trace,R.drawable.ic_jm_bug_report,act.getString(R.string.logging),null,Natives::islogging,Natives::dolog);
    final ViewGroup traceSize=page.row(trace,R.drawable.ic_jm_description,act.getString(R.string.filesize),size(act,Natives.getLogfilesize()));
    page.buttons(trace,
        page.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,() -> {
            Natives.zeroLog();
            M3Page.setSummary(traceSize,size(act,Natives.getLogfilesize()));
            }),
        page.button(M3Page.Style.TONAL,act.getString(R.string.save),R.drawable.ic_jm_save,() -> save(act,"trace.log",MainActivity.REQUEST_SAVE_LOG)));

    final LinearLayout logcat=page.section("logcat");
    page.toggle(logcat,R.drawable.ic_jm_bug_report,act.getString(R.string.logging),null,Natives::islogcat,Natives::dologcat);
    final ViewGroup logcatSize=page.row(logcat,R.drawable.ic_jm_description,act.getString(R.string.filesize),size(act,Natives.getLogcatfilesize()));
    page.buttons(logcat,
        page.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,() -> {
            Natives.zeroLogcat();
            M3Page.setSummary(logcatSize,size(act,Natives.getLogcatfilesize()));
            }),
        page.button(M3Page.Style.TONAL,act.getString(R.string.save),R.drawable.ic_jm_save,() -> save(act,"logcat.txt",MainActivity.REQUEST_SAVE_LOGCAT)));

    /* The files grow while the page is open. */
    page.refresher(() -> {
        M3Page.setSummary(traceSize,size(act,Natives.getLogfilesize()));
        M3Page.setSummary(logcatSize,size(act,Natives.getLogcatfilesize()));
        });
    }

private static String size(MainActivity act,long bytes) {
    return Formatter.formatShortFileSize(act,Math.max(0L,bytes));
    }

/* Android's "save as" picker; MainActivity writes the file when it returns. */
private static void save(MainActivity act,String filename,int request) {
    final Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    intent.putExtra(Intent.EXTRA_TITLE,filename);
    intent.putExtra(Intent.EXTRA_LOCAL_ONLY,true);
    try {
        act.startActivityForResult(intent,request);
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"ACTION_CREATE_DOCUMENT",th);
        }
    }
}
