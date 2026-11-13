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
/*      Sun Sep 21 10:44:11 CEST 2025                                                */

package tk.glucodata;

import static tk.glucodata.Log.doLog;

import android.bluetooth.BluetoothDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;

import tk.glucodata.m3.M3Page;

/*
 * Settings of one glucose meter as a Material 3 page: from which date/time
 * readings are added, the label for blood glucose, "Ask before saving
 * readings" for a shared meter (MeterAsk), Save, "Don't use"
 * (the old Cancel button: makes the meter inactive) and Delete.
 * Back closes without saving.  Same Natives calls as before.
 */
class MeterConfig {
static private final String LOG_ID="MeterConfig";

static void config(MainActivity context,int meterIndex,View parent,BluetoothDevice device,Runnable changed) {
    if(doLog)
        Log.i(LOG_ID,"MeterConfig "+meterIndex);
    String deviceName=Natives.GlucoseMeterDeviceName(meterIndex);
    if(deviceName==null)
        deviceName="Error: no device name";
    final M3Page page=M3Page.open(context,deviceName);
    page.help(R.string.GlucoseMeter);
    page.action(R.drawable.ic_jm_delete,context.getString(R.string.delete),() ->
        M3Page.confirm(context,context.getString(R.string.delete),context.getString(R.string.deletemeter),context.getString(R.string.delete),() -> {
            Natives.GlucoseMeterRemoveIndex(meterIndex);
            page.dismiss();
            if(changed!=null)
                changed.run();
            BluetoothGlucoseMeter.restartDevices();
            }));

    final long[] newtime={Natives.GlucoseMeterGetLastTime(meterIndex)};
    LinearLayout from=page.section(context.getString(R.string.bloodafter).replace("\"","").trim().replaceAll(":$",""));
    ViewGroup date=page.link(from,R.drawable.ic_jm_event,context.getString(R.string.jm_sensors_date),SensorUi.date(context,newtime[0]),null);
    ViewGroup time=page.link(from,R.drawable.ic_jm_schedule,context.getString(R.string.jm_sensors_time),SensorUi.time(context,newtime[0]),null);
    Runnable showTime=() -> {
        M3Page.setSummary(date,SensorUi.date(context,newtime[0]));
        M3Page.setSummary(time,SensorUi.time(context,newtime[0]));
        };
    date.setOnClickListener(v -> SensorUi.pickDate(context,newtime[0],t -> { newtime[0]=t; showTime.run(); }));
    time.setOnClickListener(v -> SensorUi.pickTime(context,newtime[0],t -> { newtime[0]=t; showTime.run(); }));

    LinearLayout blood=page.section(R.string.jm_sensors_section_blood);
    final ArrayList<String> labels=Natives.getLabels();
    final int[] bloodpos={Natives.getbloodvar()};
    page.choice(blood,tk.glucodata.settings.CalibrationPage.label(context,R.string.bloodvar),labels,bloodpos[0],i -> {
        bloodpos[0]=i;
        Natives.setbloodvar((byte)i);
        });

    /* Shared meter: ask which readings are the user's before saving (MeterAsk). */
    final String meter=Natives.GlucoseMeterDeviceName(meterIndex);
    if(meter!=null) {
        LinearLayout shared=page.section(R.string.jm_meterask_section);
        final boolean[] wantOff={false};
        page.toggle(shared,R.drawable.ic_jm_person,context.getString(R.string.jm_meterask_toggle),context.getString(R.string.jm_meterask_toggle_summary),() -> MeterAsk.enabled(meter),on -> {
            if(!on&&MeterAsk.pendingCount(meter)>0) {
                /* Not off while readings wait, they would be saved or lost
                 * unseen: ask now, and it goes off once they are chosen. */
                wantOff[0]=true;
                shared.post(page::refresh); // the switch back on
                MeterAsk.show(context,meter,() -> {
                    wantOff[0]=false;
                    page.toast(context.getString(R.string.jm_meterask_still_on));
                    });
                return;
                }
            wantOff[0]=false;
            MeterAsk.setEnabled(meter,on);
            });
        SensorUi.summaryLines(shared.getChildAt(shared.getChildCount()-1),4);
        final ViewGroup waiting=page.item(shared,R.drawable.ic_jm_inbox,context.getString(R.string.jm_meterask_choose),null,() -> MeterAsk.show(context,meter,null));
        final Runnable showWaiting=() -> {
            final int n=MeterAsk.pendingCount(meter);
            waiting.setVisibility(n>0?View.VISIBLE:View.GONE);
            M3Page.setSummary(waiting,context.getResources().getQuantityString(R.plurals.jm_meterask_waiting,n,n));
            };
        showWaiting.run();
        page.refresher(showWaiting);
        MeterAsk.onChanged(() -> {
            if(wantOff[0]&&MeterAsk.pendingCount(meter)==0) {
                wantOff[0]=false;
                MeterAsk.setEnabled(meter,false);
                }
            if(page.isOpen())
                page.refresh();
            });
        page.onClose(() -> MeterAsk.onChanged(null));
        }

    page.bottomBar(
        page.button(M3Page.Style.TONAL,context.getString(R.string.jm_sensors_dont_use),0,() -> {
            page.dismiss();
            Natives.GlucoseMeterSetActive(meterIndex,false);
            Applic.argToaster(context,context.getString(R.string.jm_sensors_meter_not_used),android.widget.Toast.LENGTH_LONG);
            if(changed!=null)
                changed.run();
            BluetoothGlucoseMeter.restartDevices();
            }),
        page.button(M3Page.Style.FILLED,context.getString(R.string.save),0,() -> {
            if(bloodpos[0]>=(labels.size()-1)) {
                page.toast(context.getString(R.string.specifyblood).replace("\"",""));
                return;
                }
            Log.i(LOG_ID,"save");
            page.dismiss();
            Natives.GlucoseMeterSetLastTime(meterIndex,newtime[0]);
            if(device!=null) {
                BluetoothGlucoseMeter.addDevice(meterIndex,device);
                DeviceList.closeOpen(); // back to the meter list, as the old double Back
                }
            if(changed!=null)
                changed.run();
            }));
    context.finepermission();
    }
}
