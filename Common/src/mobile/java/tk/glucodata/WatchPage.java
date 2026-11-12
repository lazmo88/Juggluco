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

import static tk.glucodata.MessageSender.initwearos;

import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.materialswitch.MaterialSwitch;

import tk.glucodata.m3.M3Page;
import tk.glucodata.settings.ExchangePage;

/*
 * Watch integrations (Watch.show) as a Material 3 page.  Every switch applies
 * at once, as the old dialog did.  Kerfstok used to be committed by OK; there
 * is no OK any more, so it is applied when switched (same calls, same order).
 */
public final class WatchPage {
private WatchPage() {}

public static void show(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.jm_share_watch_title);
    page.action(R.drawable.ic_jm_help,act.getString(R.string.helpname),() -> help.help(R.string.watchinfo,act));
    page.onClose(() -> {
        act.hideSystemUI();
        if(M3Page.top(act)==null&&Menus.on)
            Menus.show(act);
        ExchangePage.refreshTop(act);
        });

    /* Watch apps on the phone */
    LinearLayout apps=page.section(R.string.jm_share_watch_sec_apps);
    page.toggle(apps,R.drawable.ic_jm_watch,"Watchdrip",act.getString(R.string.jm_share_watchdrip_summary),
        () -> SuperGattCallback.doWearInt,isChecked -> {
            Natives.setwatchdrip(isChecked);
            tk.glucodata.watchdrip.set(isChecked);
            });
    page.toggle(apps,R.drawable.ic_jm_devices_wearables,"GadgetBridge",act.getString(R.string.jm_share_gadget_summary),
        () -> SuperGattCallback.doGadgetbridge,isChecked -> {
            Natives.setgadgetbridge(isChecked);
            SuperGattCallback.doGadgetbridge=isChecked;
            });
    if(Watch.TestBridge)
        page.item(apps,R.drawable.ic_jm_send,act.getString(R.string.jm_share_test_gadget),null,Watch::testGadgetbridge);

    /* Notifications */
    LinearLayout notif=page.section(R.string.jm_share_watch_sec_notify);
    page.toggle(notif,R.drawable.ic_jm_notifications,act.getString(R.string.notify),act.getString(R.string.jm_share_notify_summary),
        () -> Notify.alertwatch,isChecked -> Applic.app.setnotify(isChecked));
    page.toggle(notif,R.drawable.ic_jm_vibration,act.getString(R.string.separate),act.getString(R.string.jm_share_separate_summary),
        () -> Notify.alertseparate,isChecked -> {
            Notify.alertseparate=isChecked;
            Natives.setSeparate(isChecked);
            });

    /* Direct connections */
    LinearLayout direct=page.section(R.string.jm_share_watch_sec_direct);
    final boolean useWearos=Applic.useWearos();
    if(useWearos) {
        var sender=MessageSender.getMessageSender();
        if(sender!=null) sender.finddevices();
        Natives.switchSync();
        }
    final boolean[] wasWearos={useWearos};
    page.toggleLink(direct,R.drawable.ic_jm_watch,"Wear OS",act.getString(R.string.jm_share_wearos_summary),
        Applic::useWearos,isChecked -> {
            Watch.setuseWearos(isChecked);
            if(isChecked) {
                if(!wasWearos[0])
                    initwearos(Applic.app);
                else {
                    var sender=MessageSender.getMessageSender();
                    if(sender!=null) sender.finddevices();
                    }
                Natives.switchSync();
                }
            },
        () -> Wearos.show(act,page.root));

    page.toggleLink(direct,R.drawable.ic_jm_dns,act.getString(R.string.webserver),act.getString(R.string.jm_share_webserver_summary),
        Natives::getusexdripwebserver,Natives::setusexdripwebserver,
        () -> Nightscout.show(act,page.root));

    final boolean[] garminOn={Natives.getusegarmin()};
    page.toggleLink(direct,R.drawable.ic_jm_watch,"Kerfstok",act.getString(R.string.jm_share_kerfstok_summary),
        Natives::getusegarmin,isChecked -> {
            if(isChecked==garminOn[0])
                return;
            if(isChecked)
                Applic.app.numdata.reinit(act);
            Natives.setusegarmin(isChecked);
            if(!isChecked) {
                Natives.sethasgarmin(false);
                Applic.app.numdata.stop();
                }
            garminOn[0]=isChecked;
            },
        () -> {
            if(Natives.getusegarmin())
                new GarminStatus(act,Applic.app.numdata,page.root);
            else
                page.toast(act.getString(R.string.jm_share_off));
            });
    }
}
