/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
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
/*                                                                                   */
/*      Sun Sep 21 14:02:17 CEST 2025                                                */
package tk.glucodata;

import static tk.glucodata.BluetoothGlucoseMeter.getExistingGatt;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.materialswitch.MaterialSwitch;

import tk.glucodata.m3.M3Page;

/*
 * Glucose meters (Settings → Exchange data → Glucose Meters) as a Material 3
 * page: one row per known meter (tap: its settings, switch: active), and
 * "Find devices" to add one.  Same Natives / BluetoothGlucoseMeter calls as
 * the old grid.  Also registered as PhoneUi "meters".
 */
public class MeterList {

/* One meter row; GlucoseMeterGatt updates it while the page is open. */
static final class MeterView {
    final M3Page page;
    final ViewGroup row;
    final MaterialSwitch active;
    int meterIndex=-1;
    MeterView(M3Page page,LinearLayout parent,int index) {
        this.page=page;
        meterIndex=index;
        final MainActivity act=page.act;
        final boolean[] on={Natives.GlucoseMeterGetActive(index)};
        active=page.toggleLink(parent,R.drawable.ic_jm_bloodtype,"",null,() -> on[0],isChecked -> {
            if(meterIndex>=0) {
                if(Natives.GlucoseMeterSetActive(meterIndex,isChecked)) {
                    on[0]=isChecked;
                    if(isChecked) {
                        var gatt=BluetoothGlucoseMeter.addDevice(meterIndex,null);
                        gatt.view=this;
                        }
                    else
                        BluetoothGlucoseMeter.removeDevice(meterIndex);
                    }
                }
            },() -> MeterConfig.config(act,meterIndex,null,null,() -> page.refresh()));
        row=(ViewGroup)parent.getChildAt(parent.getChildCount()-1);
        SensorUi.summaryLines(row,6);
        setdata(index);
        }
    void postUpdate() {
        row.post(() -> setdata(meterIndex));
        }
    void setdata(int index) {
        meterIndex=index;
        Context context=page.act;
        String name=Natives.GlucoseMeterDeviceName(index);
        String addr=Natives.GlucoseMeterDeviceAddress(index);
        boolean a=Natives.GlucoseMeterGetActive(index);
        long lastused=Natives.GlucoseMeterGetLastTime(index);
        String addtext=(addr!=null?addr:"")+(lastused>0L?("\n"+context.getString(R.string.last).trim()+" "+bluediag.datestr(lastused)):"");
        if(a) {
            var gatt=getExistingGatt(index);
            if(gatt!=null) {
                gatt.view=this;
                if(gatt.connectedTime>gatt.disconnectedTime) {
                    addtext+="\n"+context.getString(R.string.isconnected)+": "+bluediag.datestr(gatt.connectedTime);
                    if(gatt.receivedTime==0L)
                        addtext+=gatt.isBonded?"\nBonded":"\nNot bonded";
                    }
                else {
                    if(gatt.disconnectedTime>0L) {
                        addtext+="\n"+context.getString(R.string.isdisconnected)+": "+bluediag.datestr(gatt.disconnectedTime);
                        if(gatt.receivedTime==0L)
                            addtext+=gatt.isBonded?"\nWas bonded":"\nWas not bonded";
                        }
                    }
                if(gatt.receivedTime>0L)
                    addtext+="\n"+context.getString(gatt.newvalues?R.string.newdata:R.string.nonewdata)+": "+bluediag.datestr(gatt.receivedTime);
                }
            }
        if(MeterAsk.enabled(name))
            addtext+="\n"+context.getString(R.string.jm_meterask_list_on);
        M3Page.setTitle(row,name!=null?name:"?");
        M3Page.setSummary(row,addtext.trim());
        active.setContentDescription(context.getString(R.string.active));
        boolean was=page.updating;
        page.updating=true;
        active.setChecked(a);
        page.updating=was;
        }
    Context getContext() {
        return page.ctx;
        }
    }

static M3Page current=null;

static void refreshOpen() {
    if(current!=null&&current.isOpen())
        current.refresh();
    }

static public void show(MainActivity act,View parent) {
    if(Natives.staticnum()) {
        help.help(R.string.staticnum,act);
        return;
        }
    if(current!=null&&current.isOpen()) {
        current.refresh();
        return;
        }
    final M3Page page=M3Page.open(act,act.getString(R.string.meterlist).replace("\"",""));
    current=page;
    page.help(R.string.GlucoseMeterList);
    LinearLayout list=page.section(R.string.jm_sensors_section_meters);
    Runnable fill=() -> {
        BluetoothGlucoseMeter.zeroViews();
        list.removeAllViews();
        final int n=Natives.GlucoseMeterCount();
        for(int i=0;i<n;i++)
            new MeterView(page,list,i);
        if(n==0)
            page.empty(list,R.drawable.ic_jm_bloodtype,act.getString(R.string.jm_sensors_no_meters),act.getString(R.string.jm_sensors_no_meters_body));
        };
    fill.run();
    page.refresher(fill);
    page.buttons(null,page.button(M3Page.Style.FILLED,act.getString(R.string.finddevices).replace("\"",""),R.drawable.ic_jm_add,() -> DeviceList.show(act,page::refresh)));
    /* TEST ONLY - DO NOT MERGE: fake sync of the meter TESTMETER (MeterAskTest). */
    page.buttons(null,page.button(M3Page.Style.OUTLINED,"TEST fake sync",0,() -> MeterAskTest.fakeSync(act,false)),
        page.button(M3Page.Style.OUTLINED,"TEST resend last",0,() -> MeterAskTest.fakeSync(act,true)));
    page.onClose(() -> {
        BluetoothGlucoseMeter.zeroViews();
        if(current==page)
            current=null;
        });
    }
}
