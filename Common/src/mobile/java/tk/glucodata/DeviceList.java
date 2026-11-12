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

import static tk.glucodata.Natives.GlucoseMeterHasIndex;
import static tk.glucodata.MeterScanner.shouldUseDeviceAddress;
import static tk.glucodata.Log.doLog;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import tk.glucodata.m3.M3Page;

/*
 * "Find devices" for glucose meters (and AiDEX X sensors) as a Material 3
 * page: Bluetooth devices found by MeterScanner as list rows; tapping one
 * adds it like before (MeterConfig for meters, AidexXGattCallback for AiDEX X).
 */
public class DeviceList {
static private final String LOG_ID="DeviceList";

static class DeviceListViewHolder extends RecyclerView.ViewHolder {
    DeviceListViewHolder(View view,M3Page page) {
        super(view);
        view.setOnClickListener(v -> {
            int pos=getAbsoluteAdapterPosition();
            final var scan=BluetoothGlucoseMeter.scanner;
            if(scan!=null) {
                if(pos>=0&&pos<scan.deviceNames.size()) {
                    String deviceName=scan.deviceNames.get(pos);
                    if(showAidexX) {
                        AidexXGattCallback.addbyDeviceName(page.act,deviceName);
                        while(MainActivity.doonback())
                            ;
                        }
                    else {
                        var device=scan.devices.get(pos);
                        int meterIndex=Natives.GlucoseMeterGetIndex(deviceName,shouldUseDeviceAddress(deviceName,device)?device.getAddress():null);
                        if(doLog)
                            Log.i(LOG_ID,deviceName+" pos="+pos+" meterIndex="+meterIndex);
                        if(meterIndex>=0)
                            MeterConfig.config(page.act,meterIndex,null,device,MeterList::refreshOpen);
                        else
                            page.toast("Adding meter "+deviceName+" failed");
                        }
                    }
                else
                    Log.e(LOG_ID,"pos "+pos+" >=deviceNames "+scan.deviceNames.size());
                }
            else
                Log.e(LOG_ID,"scanner==null");
            });
        }
    }

static class DeviceListViewAdapter extends RecyclerView.Adapter<DeviceListViewHolder> {
    final M3Page page;
    final String newname;
    DeviceListViewAdapter(M3Page page) {
        this.page=page;
        this.newname=page.act.getString(R.string.newname);
        }
    @NonNull
    @Override
    public DeviceListViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int viewType) {
        View row=LayoutInflater.from(page.ctx).inflate(R.layout.material_settings_row,parent,false);
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        ((android.widget.ImageView)row.findViewById(R.id.jm_row_icon)).setImageResource(R.drawable.ic_jm_bluetooth);
        return new DeviceListViewHolder(row,page);
        }
    static private int aidexXindex(String name) {
        String serial=AidexXGattCallback.deviceName2name(name);
        return Natives.sensorIndex(serial);
        }
    @Override
    public void onBindViewHolder(@NonNull final DeviceListViewHolder holder,int pos) {
        View row=holder.itemView;
        var scan=BluetoothGlucoseMeter.scanner;
        if(scan==null||pos>=scan.devices.size())
            return;
        var device=scan.devices.get(pos);
        var name=scan.deviceNames.get(pos);
        var address=device.getAddress();
        int index=showAidexX?aidexXindex(name):GlucoseMeterHasIndex(name,shouldUseDeviceAddress(name,device)?address:null);
        M3Page.setTitle(row,name);
        SpannableStringBuilder sum=new SpannableStringBuilder(address);
        if(index<0) {
            sum.append("  ·  ");
            int start=sum.length();
            sum.append(newname);
            sum.setSpan(new ForegroundColorSpan(page.color(com.google.android.material.R.attr.colorPrimary)),start,sum.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        M3Page.setSummary(row,sum);
        }
    @Override
    public int getItemCount() {
        final MeterScanner scanner=BluetoothGlucoseMeter.scanner;
        if(scanner==null||scanner.adapter!=this)
            return 0;
        return scanner.devices.size();
        }
    }

static private boolean showAidexX=false;

static public void show(MainActivity act,Runnable onClose) {
    final M3Page page=M3Page.open(act,act.getString(R.string.finddevices).replace("\"",""));
    page.help(R.string.DeviceList);
    var deviceadapt=new DeviceListViewAdapter(page);
    LinearLayout kind=page.section((CharSequence)null);
    LinearLayout found=page.section(R.string.jm_sensors_section_found);
    View searching=page.empty(found,R.drawable.ic_jm_bluetooth_searching,act.getString(R.string.jm_sensors_searching),act.getString(R.string.jm_sensors_searching_body));
    RecyclerView recycle=new RecyclerView(page.ctx);
    recycle.setLayoutManager(new LinearLayoutManager(page.ctx));
    recycle.setNestedScrollingEnabled(false);
    recycle.setAdapter(deviceadapt);
    page.custom(found,recycle);
    Runnable empty=() -> {
        boolean none=deviceadapt.getItemCount()==0;
        searching.setVisibility(none?View.VISIBLE:View.GONE);
        recycle.setVisibility(none?View.GONE:View.VISIBLE);
        };
    deviceadapt.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
        @Override public void onChanged() { empty.run(); }
        });
    page.toggle(kind,R.drawable.ic_jm_sensors,"AiDEX X",act.getString(R.string.jm_sensors_aidex_summary),() -> showAidexX,isChecked -> {
        showAidexX=isChecked;
        BluetoothGlucoseMeter.startAdapterScanner(deviceadapt,isChecked);
        deviceadapt.notifyDataSetChanged();
        });
    BluetoothGlucoseMeter.startAdapterScanner(deviceadapt,showAidexX);
    empty.run();
    page.onClose(() -> {
        BluetoothGlucoseMeter.stopScanner();
        if(onClose!=null)
            onClose.run();
        });
    current=page;
    act.finepermission();
    }

static M3Page current=null;
static void closeOpen() {
    if(current!=null&&current.isOpen())
        current.dismiss();
    current=null;
    }
}
