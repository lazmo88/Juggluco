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

import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;

import tk.glucodata.m3.M3Page;

/*
 * Wear OS watch configuration (Wearos.show) as a Material 3 page.
 * Pick a watch, then where amounts are entered and which device connects to
 * the sensor.  Save applies a changed choice exactly like the old Close
 * button; Defaults resets the watch; Back discards.  The choices follow the
 * watch's state every second until you change one, as before.
 */
public final class WearosPage {
private WearosPage() {}

private static String capital(String s) {
    if(s==null||s.isEmpty()) return s;
    return Character.toUpperCase(s.charAt(0))+s.substring(1);
    }

private static MaterialButtonToggleGroup group(M3Page page,LinearLayout parent,String label) {
    MaterialButtonToggleGroup g=page.segmented(parent,label,new CharSequence[]{capital(page.act.getString(R.string.phone)),capital(page.act.getString(R.string.watch))},-1,i -> {});
    g.setSelectionRequired(false);
    return g;
    }
private static int id(MaterialButtonToggleGroup g,int i) {
    return g.getChildAt(i).getId();
    }
private static boolean checked(MaterialButtonToggleGroup g,int i) {
    return ((MaterialButton)g.getChildAt(i)).isChecked();
    }
private static void enable(MaterialButtonToggleGroup g,boolean on) {
    for(int i=0;i<g.getChildCount();i++)
        g.getChildAt(i).setEnabled(on);
    }

/* Wearos.remake for the two segmented groups. */
private static void remake(MaterialButtonToggleGroup sensor,MaterialButtonToggleGroup nums,Wearos.WatchTarget node,boolean setChoices) {
    int dirval,numsval;
    if(node==null) {
        dirval=-1;
        numsval=-1;
        }
    else {
        String name=Wearos.makenodename(node);
        dirval=Natives.directsensorwatch(name);
        numsval=Natives.hasWatchNums(name);
        if(dirval==0&&!Natives.getusebluetooth()) {
            dirval=-1;
            numsval=-1;
            }
        }
    if(dirval<0) {
        enable(sensor,false);
        if(setChoices) sensor.clearChecked();
        }
    else {
        if(setChoices||(!checked(sensor,0)&&!checked(sensor,1)))
            sensor.check(id(sensor,dirval!=0?1:0));
        enable(sensor,true);
        }
    if(numsval<0) {
        enable(nums,false);
        if(setChoices) nums.clearChecked();
        }
    else {
        if(setChoices||(!checked(nums,0)&&!checked(nums,1)))
            nums.check(id(nums,numsval!=0?1:0));
        enable(nums,true);
        }
    }

public static void show(MainActivity act) {
    final M3Page page=M3Page.open(act,"Wear OS");
    page.help(R.string.wearosinfo);
    page.onClose(act::hideSystemUI);
    final ArrayList<Wearos.WatchTarget> nodeslist=Wearos.getnodeslist();
    LinearLayout sec=page.section((CharSequence)null);
    if(nodeslist.isEmpty()) {
        page.empty(sec,R.drawable.ic_jm_watch,act.getString(R.string.nowatchesfound),null);
        return;
        }
    final int[] pos={0};
    final boolean[] changed={false};
    final boolean[] refreshing={false};
    ArrayList<String> names=new ArrayList<>();
    for(var node:nodeslist)
        names.add(node.displayName.equals(node.label)?node.label:node.displayName+" - "+node.label);
    LinearLayout settings=page.section((CharSequence)null);
    final MaterialButtonToggleGroup nums=group(page,settings,act.getString(R.string.enternums));
    final MaterialButtonToggleGroup sensor=group(page,settings,(act.getString(R.string.directsensor)+act.getString(R.string.connectionl)).trim());
    final MaterialButton defaults=page.button(M3Page.Style.OUTLINED,act.getString(R.string.defaults),0,() -> {});
    MaterialButtonToggleGroup.OnButtonCheckedListener user=(g,id,isChecked) -> {
        if(!refreshing[0]) {
            changed[0]=true;
            defaults.setEnabled(false);
            }
        };
    Runnable select=() -> {
        refreshing[0]=true;
        try { remake(sensor,nums,nodeslist.get(pos[0]),true); }
        catch(Throwable e) { Log.stack("WearosPage",e); }
        refreshing[0]=false;
        changed[0]=false;
        defaults.setEnabled(true);
        };
    page.choice(sec,act.getString(R.string.watch),names,0,i -> { pos[0]=i; select.run(); });
    nums.addOnButtonCheckedListener(user);
    sensor.addOnButtonCheckedListener(user);
    select.run();

    defaults.setOnClickListener(v -> {
        var sender=MessageSender.getMessageSender();
        if(sender==null) return;
        var nod=nodeslist.get(pos[0]);
        String name=Wearos.makenodename(nod);
        Runnable setdef=() -> {
            sender.toDefaults(name);
            Natives.setWearosdefaults(name,nod.galaxy);
            var main=MainActivity.thisone;
            Applic.setbluetooth(main==null?Applic.app:main,true);
            page.dismiss();
            };
        if(Natives.directsensorwatch(name)<0)
            Wearos.confirmunsynced(act,setdef);
        else
            setdef.run();
        });
    page.bottomBar(defaults,page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
        if(sensor.getChildAt(1).isEnabled()&&changed[0]) {
            final boolean watchnums=checked(nums,1);
            final boolean watchdirect=checked(sensor,1);
            var node=nodeslist.get(pos[0]);
            var name=Wearos.makenodename(node);
            int currentDirect=Natives.directsensorwatch(name);
            if(currentDirect<0||Natives.hasWatchNums(name)<0||(currentDirect==0&&!Natives.getusebluetooth())) {
                Natives.switchSync();
                return;
                }
            final boolean wasdirect=currentDirect!=0;
            byte[] netinfo=Natives.getmynetinfo(name,true,watchdirect?1:-1,node.galaxy,watchnums?1:-1,false);
            Runnable doswitch=() -> Applic.switchbluetooth(name,netinfo,watchdirect);
            if(Natives.hasAidexX()) {
                if(!wasdirect&&watchdirect)
                    SensorBluetooth.afterUnpair(act,res -> doswitch.run());
                else if(wasdirect&&!watchdirect)
                    Wearos.returnSensorToPhone(act,name,netinfo);
                else
                    doswitch.run();
                }
            else
                doswitch.run();
            }
        page.dismiss();
        }));

    Runnable refreshStatus=new Runnable() {
        @Override public void run() {
            if(!page.isOpen())
                return;
            refreshing[0]=true;
            try { remake(sensor,nums,nodeslist.get(pos[0]),!changed[0]); }
            catch(Throwable e) { Log.stack("WearosPage",e); }
            finally { refreshing[0]=false; }
            page.root.postDelayed(this,1000L);
            }
        };
    page.root.postDelayed(refreshStatus,1000L);
    page.onClose(() -> page.root.removeCallbacks(refreshStatus));
    Natives.switchSync();
    }
}
