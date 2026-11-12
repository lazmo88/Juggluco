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

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;

import tk.glucodata.Applic;
import tk.glucodata.Consumer;
import tk.glucodata.EverSense;
import tk.glucodata.HealthConnection;
import tk.glucodata.JugglucoSend;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.PhoneUi;
import tk.glucodata.R;
import tk.glucodata.SendLikexDrip;
import tk.glucodata.XInfuus;
import tk.glucodata.m3.M3Page;

/*
 * "Exchange data" (Settings.exchanges) as a Material 3 hub.
 *
 * Same switches and destinations as the old screen, grouped in sections.
 * Broadcast rows open a chooser of the apps that listen (Broadcasts.getselected
 * semantics); the row is on when at least one app is chosen.
 */
public final class ExchangePage {
private ExchangePage() {}

/* Row with a status switch that is not touched directly: tapping the row
 * opens what really changes the value.  The switch is re-read on refresh. */
public static MaterialSwitch statusRow(M3Page page,LinearLayout parent,int icon,CharSequence title,M3Page.BoolGet get,Runnable open) {
    ViewGroup row=page.row(parent,icon,title,null);
    MaterialSwitch sw=new MaterialSwitch(page.ctx);
    sw.setClickable(false);
    sw.setFocusable(false);
    sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    sw.setChecked(get.get());
    M3Page.end(row).addView(sw);
    row.setOnClickListener(v -> open.run());
    row.setContentDescription(title);
    page.refresher(() -> sw.setChecked(get.get()));
    return sw;
    }

private static String joined(String[] names) {
    if(names==null||names.length==0)
        return null;
    StringBuilder b=new StringBuilder();
    for(String n:names) {
        if(b.length()>0)
            b.append(", ");
        b.append(n);
        }
    return b.toString();
    }

private static CharSequence broadcastSummary(MainActivity act,boolean on,String[] receivers) {
    String names=on?joined(receivers):null;
    if(names==null)
        return act.getString(R.string.jm_share_off);
    return act.getString(R.string.jm_share_sending_to,names);
    }

/* Material replacement of Broadcasts.getselected(): multi choice dialog of the
 * apps listening to the action.  saveproc gets the new selection, or null when
 * nothing changes, exactly as before. */
static void chooseReceivers(MainActivity act,String title,String[] selected,ArrayList<String> names,Consumer<String[]> saveproc) {
    final int len=names.size();
    if(len==0) {
        M3Page.message(act,title,act.getString(R.string.jm_share_broadcast_none,title));
        saveproc.accept(new String[0]);
        return;
        }
    final CharSequence[] items=names.toArray(new CharSequence[0]);
    final boolean[] checked=new boolean[len];
    boolean onechecked=false;
    for(int i=0;i<len;i++) {
        checked[i]=Broadcasts.isElement(names.get(i),selected);
        onechecked=onechecked||checked[i];
        }
    final boolean finalonechecked=onechecked;
    final boolean[] saved={false};
    M3Page.dialog(act)
        .setTitle(act.getString(R.string.jm_share_broadcast_pick,title))
        .setMultiChoiceItems(items,checked,(d,which,isChecked) -> checked[which]=isChecked)
        .setNegativeButton(R.string.cancel,null)
        .setPositiveButton(R.string.save,(d,w) -> {
            saved[0]=true;
            ArrayList<String> out=new ArrayList<>();
            for(int i=0;i<len;i++)
                if(checked[i])
                    out.add(names.get(i));
            saveproc.accept(out.toArray(new String[0]));
            })
        .setOnDismissListener(d -> {
            if(saved[0])
                return;
            if(selected!=null&&selected.length!=0&&!finalonechecked)
                saveproc.accept(new String[0]);
            else
                saveproc.accept(null);
            })
        .show();
    }

private static void broadcastRow(M3Page page,LinearLayout sec,MainActivity act,CharSequence title,String name,String action,
        M3Page.BoolGet on,java.util.function.Supplier<String[]> receivers,Consumer<String[]> store) {
    MaterialSwitch sw=statusRow(page,sec,R.drawable.ic_jm_cell_tower,title,on,() -> {
        chooseReceivers(act,name,receivers.get(),Broadcasts.actionListeners(action),newselected -> {
            if(newselected!=null)
                store.accept(newselected);
            page.refresh();
            });
        });
    View row=(View)sw.getParent().getParent();
    M3Page.setSummary(row,broadcastSummary(act,on.get(),receivers.get()));
    page.refresher(() -> M3Page.setSummary(row,broadcastSummary(act,on.get(),receivers.get())));
    }

public static void show(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.jm_share_title);
    page.help(R.string.exchangehelp);

    /* Broadcasts */
    LinearLayout broad=page.section(R.string.jm_share_sec_broadcasts);
    broadcastRow(page,broad,act,act.getString(R.string.xdripbroadcast),"xDrip",SendLikexDrip.ACTION,
        Natives::getxbroadcast,Natives::xdripRecepters,sel -> { Natives.setxdripRecepters(sel); SendLikexDrip.setreceivers(); });
    broadcastRow(page,broad,act,act.getString(R.string.jm_share_glucodata),"Glucodata",JugglucoSend.ACTION,
        Natives::getJugglucobroadcast,Natives::glucodataRecepters,sel -> { Natives.setglucodataRecepters(sel); JugglucoSend.setreceivers(); });
    broadcastRow(page,broad,act,act.getString(R.string.patchedlibrebroadcast),"Patched Libre",XInfuus.glucoseaction,
        Natives::getlibrelinkused,Natives::librelinkRecepters,sel -> { Natives.setlibrelinkRecepters(sel); XInfuus.setlibrenames(); });
    broadcastRow(page,broad,act,act.getString(R.string.everSensebroadcast),"EverSense",EverSense.glucoseaction,
        Natives::geteverSensebroadcast,Natives::everSenseRecepters,sel -> { Natives.seteverSenseRecepters(sel); EverSense.setreceivers(); });

    /* Cloud services */
    LinearLayout cloud=page.section(R.string.jm_share_sec_cloud);
    View uploader=page.link(cloud,R.drawable.ic_jm_cloud_upload,act.getString(R.string.uploader),null,
        () -> tk.glucodata.NightPost.config(act,page.root));
    page.refresher(() -> M3Page.setSummary(uploader,onOff(act,Natives.getuseuploader(),R.string.jm_share_uploader_summary)));
    View libreview=page.link(cloud,R.drawable.ic_jm_cloud,act.getString(R.string.libreviewname),null,
        () -> tk.glucodata.LibreviewPage.show(act,null));
    page.refresher(() -> M3Page.setSummary(libreview,act.getString(Natives.getuselibreview()?R.string.jm_share_on:R.string.jm_share_off)));
    page.link(cloud,R.drawable.ic_jm_upload,"Dexcom Clarity",act.getString(R.string.jm_share_clarity_summary),
        () -> tk.glucodata.Clarity.show(act,page.root));

    /* Watches & wearables */
    LinearLayout wear=page.section(R.string.jm_share_sec_wearables);
    page.link(wear,R.drawable.ic_jm_watch,act.getString(R.string.jm_share_watch_title),act.getString(R.string.jm_share_watch_summary),
        () -> tk.glucodata.WatchPage.show(act));
    if(Build.VERSION.SDK_INT>=28) {
        page.toggle(wear,R.drawable.ic_jm_favorite,"Health Connect",act.getString(R.string.jm_share_health_summary),
            Natives::gethealthConnect,isChecked -> {
                Natives.sethealthConnect(isChecked);
                if(isChecked) {
                    MainActivity.tryHealth=5;
                    HealthConnection.Companion.init(act);
                    }
                else {
                    MainActivity.tryHealth=0;
                    HealthConnection.Companion.stop();
                    }
                });
        if(tk.glucodata.ActivityHealth.available())
            page.link(wear,R.drawable.ic_jm_directions_run,act.getString(R.string.activity_import),act.getString(R.string.jm_share_activities_summary),
                () -> tk.glucodata.ActivitySettings.show(act,page.root));
        }
    page.link(wear,R.drawable.ic_jm_bloodtype,act.getString(R.string.meterlist),act.getString(R.string.jm_share_meters_summary),
        () -> {
            if(!PhoneUi.show("meters",act))
                tk.glucodata.MeterList.show(act,page.root);
            });

    /* Local network */
    LinearLayout local=page.section(R.string.jm_share_sec_local);
    page.link(local,R.drawable.ic_jm_sync_alt,act.getString(R.string.mirror),act.getString(R.string.jm_share_mirror_summary),
        () -> (new tk.glucodata.Backup()).realmkbackupview(act,false));
    View web=page.link(local,R.drawable.ic_jm_dns,act.getString(R.string.webserver),null,
        () -> tk.glucodata.Nightscout.show(act,page.root));
    page.refresher(() -> M3Page.setSummary(web,Natives.getusexdripwebserver()?act.getString(R.string.jm_share_webserver_on,Natives.gethttpport()):act.getString(R.string.jm_share_off)));

    /* Files */
    LinearLayout files=page.section(R.string.jm_share_sec_files);
    page.link(files,R.drawable.ic_jm_download,act.getString(R.string.export),act.getString(R.string.jm_share_export_summary),
        () -> {
            var c=Applic.app.curve;
            if(c!=null)
                c.dialogs.showexport(act,c.getWidth(),c.getHeight(),page.root);
            });

    page.refresh();
    }

/* Password fields: keep the normal typeface (Android switches them to monospace). */
public static void plain(com.google.android.material.textfield.TextInputEditText e) {
    e.setTypeface(android.graphics.Typeface.DEFAULT);
    android.view.ViewParent p=e.getParent();
    while(p!=null&&!(p instanceof com.google.android.material.textfield.TextInputLayout))
        p=p.getParent();
    if(p!=null)
        ((com.google.android.material.textfield.TextInputLayout)p).setTypeface(android.graphics.Typeface.DEFAULT);
    }

/* Re-read the hub when a sub page closes. */
public static void refreshTop(MainActivity act) {
    M3Page top=M3Page.top(act);
    if(top!=null)
        top.refresh();
    }

private static CharSequence onOff(MainActivity act,boolean on,int description) {
    return act.getString(on?R.string.jm_share_on:R.string.jm_share_off)+" · "+act.getString(description);
    }
}
