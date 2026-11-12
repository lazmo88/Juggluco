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

import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED;
import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static tk.glucodata.Log.doLog;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tk.glucodata.m3.M3Page;

/*
 * Sensors (menu → Sensor) as Material 3 pages: the list of sensors with the
 * Bluetooth settings, and one details page per sensor.
 *
 * Replaces on phones: bluediag (Bluetooth diagnostics), bluediag.nosensors /
 * showsensormessage, MirrorSensors (sensors without Bluetooth on this phone)
 * and Sensors (sensor info: hide, warm-up, activate, calibrations).  The same
 * Natives/SensorBluetooth calls are made as in those classes.
 */
public final class SensorsPage {
private SensorsPage() {}
private static final String LOG_ID="SensorsPage";

private static M3Page listPage=null;
private static M3Page detailPage=null;
/* Set while the list page closes only to be rebuilt, so the menu sheet is
 * not reopened underneath it. */
private static boolean rebuilding=false;

/* ---------- list ---------- */

/* bluediag.start() on phones. */
public static void start(MainActivity act) {
    final boolean use=Natives.getusebluetooth();
    ArrayList<SuperGattCallback> gatts=SensorBluetooth.mygatts();
    if((gatts==null||gatts.isEmpty())&&use) {
        Applic.app.initbluetooth(true,act,true);
        gatts=SensorBluetooth.mygatts();
        }
    if(gatts!=null&&!gatts.isEmpty()&&!use)
        Applic.dontusebluetooth();
    show(act);
    }

/* Close and build the list again, e.g. after Bluetooth was turned on/off. */
static void reload(MainActivity act) {
    if(listPage!=null&&listPage.isOpen()) {
        rebuilding=true;
        listPage.dismiss();
        rebuilding=false;
        }
    start(act);
    }

private static BluetoothAdapter adapter(Context ctx) {
    BluetoothManager manager=(BluetoothManager)ctx.getSystemService(Context.BLUETOOTH_SERVICE);
    return manager==null?null:manager.getAdapter();
    }

private static String bluetoothState(Context ctx) {
    BluetoothAdapter adapter=adapter(ctx);
    return ctx.getString(adapter==null?R.string.nobluetooth:(adapter.isEnabled()?R.string.bluetoothenabled:R.string.bluetoothdisabled));
    }

static boolean ignoringBattery(Context ctx) {
    if(Build.VERSION.SDK_INT<Build.VERSION_CODES.M)
        return true;
    PowerManager pm=(PowerManager)ctx.getSystemService(Activity.POWER_SERVICE);
    return pm!=null&&pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

private static ArrayList<SuperGattCallback> gatts() {
    if(!Natives.getusebluetooth())
        return null;
    ArrayList<SuperGattCallback> gatts=SensorBluetooth.mygatts();
    return gatts==null||gatts.isEmpty()?null:gatts;
    }

static void show(MainActivity act) {
    if(listPage!=null&&listPage.isOpen()) {
        listPage.refresh();
        return;
        }
    final M3Page page=M3Page.open(act,R.string.sensors);
    listPage=page;
    final ArrayList<SuperGattCallback> gatts=gatts();
    page.help(gatts!=null?R.string.sensorhelp:R.string.sensormirror);
    final boolean use=Natives.getusebluetooth();

    /* Sensors */
    LinearLayout sensors=page.section(R.string.jm_sensors_section_sensors);
    Runnable fillSensors=() -> fillSensors(page,sensors);
    fillSensors.run();
    page.refresher(fillSensors);

    /* Bluetooth */
    LinearLayout blue=page.section(R.string.jm_sensors_section_bluetooth);
    ViewGroup state=page.item(blue,R.drawable.ic_jm_bluetooth,bluetoothState(act),null,null);
    page.refresher(() -> {
        M3Page.setTitle(state,bluetoothState(act));
        BluetoothAdapter ad=adapter(act);
        ((android.widget.ImageView)state.findViewById(R.id.jm_row_icon)).setImageResource(ad!=null&&ad.isEnabled()?R.drawable.ic_jm_bluetooth:R.drawable.ic_jm_bluetooth_disabled);
        });
    page.toggle(blue,R.drawable.ic_jm_bluetooth_searching,act.getString(R.string.use_bluetooth),act.getString(R.string.jm_sensors_use_bluetooth_summary),
        Natives::getusebluetooth,on -> {
            if(doLog) {Log.i(LOG_ID,"usebluetooth "+on);};
            if(on!=Natives.getusebluetooth()) {
                act.setbluetoothmain(on);
                act.requestRender();
                page.root.post(() -> reload(act));
                }
            });
    if(use) {
        page.toggle(blue,R.drawable.ic_jm_speed,act.getString(R.string.high_priority),act.getString(R.string.jm_sensors_priority_summary),
            Natives::getpriority,on -> {
                if(Natives.getpriority()!=on)
                    setpriorities(on,SensorBluetooth.mygatts());
                });
        page.toggle(blue,R.drawable.ic_jm_sync,act.getString(R.string.jm_sensors_autoconnect),act.getString(R.string.jm_sensors_autoconnect_summary),
            () -> SuperGattCallback.autoconnect,SensorBluetooth::setAutoconnect);
        if(gatts==null&&Natives.optionStreamHistory(0L))
            page.toggle(blue,R.drawable.ic_jm_history,act.getString(R.string.streamhistory),act.getString(R.string.jm_sensors_streamhistory_summary),
                Natives::getStreamHistory,Natives::setStreamHistory);
        }
    if(Build.VERSION.SDK_INT>=23) {
        ViewGroup perm=page.link(blue,R.drawable.ic_jm_location_on,act.getString(R.string.jm_sensors_permission),act.getString(R.string.jm_sensors_permission_summary),() -> {
            String[] noperm=Applic.noPermissions(act);
            if(noperm.length==0) {
                page.refresh();
                return;
                }
            bluediag.returntoblue=true;
            rebuilding=true;
            page.dismiss();
            rebuilding=false;
            act.requestPermissions(noperm,MainActivity.BLUETOOTH_PERMISSION_REQUEST_CODE);
            });
        Runnable showPerm=() -> perm.setVisibility(Applic.noPermissions(act).length>0?View.VISIBLE:View.GONE);
        showPerm.run();
        page.refresher(showPerm);
        act.setfineres(() -> page.refresh());

        ViewGroup battery=page.link(blue,R.drawable.ic_jm_battery_saver,act.getString(R.string.jm_sensors_battery),null,() -> Battery.batteryscreen(act,null));
        Runnable batterySummary=() -> M3Page.setSummary(battery,act.getString(ignoringBattery(act)?R.string.jm_sensors_battery_free:R.string.jm_sensors_battery_restricted));
        batterySummary.run();
        page.refresher(batterySummary);
        }
    TextView scanLog=page.note(blue,"");
    Runnable showScan=() -> {
        String text=scanLog();
        scanLog.setText(text);
        scanLog.setVisibility(text==null?View.GONE:View.VISIBLE);
        };
    showScan.run();
    page.refresher(showScan);

    /* Same 29 s update as the old diagnostics screen. */
    final ScheduledFuture<?> scheduled=Applic.scheduler.scheduleAtFixedRate(() -> act.runOnUiThread(() -> {
        if(page.isOpen())
            page.refresh();
        }),29,29,TimeUnit.SECONDS);
    page.onClose(() -> {
        scheduled.cancel(false);
        act.setfineres(null);
        if(listPage==page)
            listPage=null;
        if(!rebuilding&&Menus.on)
            Menus.show(act);
        });
    }

private static void fillSensors(M3Page page,LinearLayout sensors) {
    final MainActivity act=page.act;
    sensors.removeAllViews();
    final ArrayList<SuperGattCallback> gatts=gatts();
    if(gatts!=null) {
        for(SuperGattCallback gatt:gatts) {
            String name=gatt.SerialNumber!=null?gatt.SerialNumber:"Error";
            ViewGroup row=page.link(sensors,R.drawable.ic_jm_sensors,name,gattSummary(act,gatt),() -> showGatt(act,gatt));
            SensorUi.summaryLines(row,3);
            }
        return;
        }
    long[] ptrs=Natives.activeSensorPtrs();
    if(ptrs!=null&&ptrs.length>0) {
        for(long ptr:ptrs) {
            String name=Natives.namefromSensorptr(ptr);
            page.link(sensors,R.drawable.ic_jm_sensors,name!=null?name:"Error",
                act.getString(Natives.getusebluetooth()?R.string.jm_sensors_no_connection:R.string.jm_sensors_bluetooth_off),() -> showSensor(act,ptr,null));
            }
        return;
        }
    page.empty(sensors,R.drawable.ic_jm_sensors_off,act.getString(R.string.jm_sensors_none),act.getString(R.string.jm_sensors_none_body));
    }

private static String streamingText(Context ctx,SuperGattCallback gatt) {
    final int id;
    if(gatt.sensorgen<=2)
        id=gatt.streamingEnabled()?R.string.streamingenabled:R.string.streamingnotenabled;
    else
        id=gatt.streamingEnabled()?R.string.sensorstreamed:R.string.sensornotstreamed;
    return ctx.getString(id).trim();
    }

private static String gattSummary(Context ctx,SuperGattCallback gatt) {
    if(gatt.charcha[0]!=0L)
        return ctx.getString(R.string.jm_sensors_last_value,bluediag.datestr(gatt.charcha[0]));
    String streaming=streamingText(ctx,gatt);
    if(!streaming.isEmpty())
        return streaming;
    return ctx.getString(R.string.jm_sensors_no_value_yet);
    }

/* The scan messages of the old screen (bluediag.showall). */
private static String scanLog() {
    SensorBluetooth blue=SensorBluetooth.blueone;
    if(blue==null||blue.scantime==0L)
        return null;
    long lasttime=0;
    final List<bluediag.Pair> messages=new ArrayList<>();
    final ArrayList<SuperGattCallback> gatts=SensorBluetooth.mygatts();
    boolean found=false;
    if(gatts!=null) {
        for(SuperGattCallback gatt:gatts) {
            if(gatt.foundtime>=blue.scantime) {
                if(gatt.foundtime>lasttime)
                    lasttime=gatt.foundtime;
                found=true;
                bluediag.put(messages,gatt.foundtime,": Found "+gatt.mygetDeviceName()+"\n");
                }
            }
        }
    if(!found)
        bluediag.put(messages,blue.scantime,": Start search for sensors\n");
    if(lasttime==0L||lasttime>(System.currentTimeMillis()-5*60*1000)) {
        if(blue.scantimeouttime>blue.scantime)
            bluediag.put(messages,blue.scantimeouttime,": timeout\n");
        Collections.sort(messages,new bluediag.onkey());
        StringBuilder builder=new StringBuilder();
        for(bluediag.Pair entry:messages) {
            builder.append(bluediag.datestr(entry.key));
            builder.append(entry.value);
            }
        if(builder.length()>0)
            builder.deleteCharAt(builder.length()-1);
        return builder.toString();
        }
    return null;
    }

private static void setpriorities(boolean isChecked,ArrayList<SuperGattCallback> gatts) {
    Natives.setpriority(isChecked);
    if(gatts!=null) {
        final int use_priority=isChecked?CONNECTION_PRIORITY_HIGH:CONNECTION_PRIORITY_BALANCED;
        for(SuperGattCallback g:gatts) {
            try {
                var ga=g.mBluetoothGatt;
                if(ga!=null)
                    ga.requestConnectionPriority(use_priority);
                }
            catch(Throwable th) {
                Log.stack(LOG_ID,"setpriorities",th);
                }
            }
        }
    }

/* ---------- one sensor ---------- */

static void showGatt(MainActivity act,SuperGattCallback gatt) {
    detail(act,gatt,Natives.getsensorptr(gatt.dataptr),null);
    }

/* Sensors.show / GlucoseCurve.showsensorinfo and sensors without Bluetooth. */
public static void showSensor(MainActivity act,long sensorptr,String text) {
    SuperGattCallback match=null;
    ArrayList<SuperGattCallback> gatts=gatts();
    if(gatts!=null&&sensorptr!=0L) {
        for(SuperGattCallback g:gatts)
            if(g.dataptr!=0L&&Natives.getsensorptr(g.dataptr)==sensorptr) {
                match=g;
                break;
                }
        }
    detail(act,match,sensorptr,text);
    }

private static Spanned row(Context ctx,long[] times,String info) {
    SpannableStringBuilder b=new SpannableStringBuilder();
    if(times[0]==0L&&times[1]==0L) {
        b.append(ctx.getString(R.string.jm_sensors_not_yet));
        return b;
        }
    boolean failureOld=false;
    for(int i=0;i<2;i++) {
        long tim=times[i];
        if(tim==0L)
            continue;
        if(b.length()>0)
            b.append('\n');
        int start=b.length();
        b.append(ctx.getString(i==0?R.string.last_success:R.string.last_failure)).append(": ").append(bluediag.datestr(tim));
        if(tim<times[(~i)&1]) {
            b.setSpan(new StrikethroughSpan(),start,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(i==1)
                failureOld=true;
            }
        }
    if(info!=null&&!info.isEmpty()&&times[1]!=0L) {
        b.append('\n');
        int start=b.length();
        b.append(info);
        if(failureOld)
            b.setSpan(new StrikethroughSpan(),start,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    return b;
    }

private static String sensorType(Context ctx,SuperGattCallback gatt) {
    if(gatt.sensorgen==2)
        return ctx.getString(R.string.jm_sensors_us_sensor);
    if(gatt.sensorgen==0x10&&Natives.siNewSI(gatt.dataptr)) {
        switch(Natives.getSiSubtype(gatt.dataptr)) {
            case 0: return ctx.getString(R.string.eusibionics);
            case 1: return ctx.getString(R.string.hematonix);
            case 2: return ctx.getString(R.string.chsibionics);
            case 3: return ctx.getString(R.string.sibionics2);
            }
        }
    return null;
    }

static private final int MAX_VALUE=255;
static private final int MAX_PROGRESS=1000;
private static final double X_MIN=0.15;
/* Same mapping as the old warm-up seek bar (Sensors). */
static int progressToValue(float progress,int minValue) {
    var t=(double)progress/MAX_PROGRESS;
    var x=X_MIN+(1.0-X_MIN)*t;
    var X_MIN3=X_MIN*X_MIN*X_MIN;
    var normalized=(x*x*x-X_MIN3)/(1.0-X_MIN3);
    return (int)Math.round(minValue+(MAX_VALUE-minValue)*normalized);
    }
static int valueToProgress(double value,int minValue) {
    var normalized=(value-minValue)/(MAX_VALUE-minValue);
    var X_MIN3=X_MIN*X_MIN*X_MIN;
    var x=Math.cbrt(normalized*(1.0-X_MIN3)+X_MIN3);
    var t=(x-X_MIN)/(1.0-X_MIN);
    return (int)Math.round(t*MAX_PROGRESS);
    }

private static void useAgain(MainActivity act,long sensorptr) {
    if(Natives.useAgain(sensorptr)) {
        SensorLifecycle.added(sensorptr);
        var res=SensorBluetooth.updateDevices();
        SuperGattCallback.glucosealarms.setLossAlarm();
        if(res)
            act.finepermission();
        else
            act.systemlocation();
        }
    Applic.wakemirrors();
    act.requestRender();
    }

private static void detail(MainActivity act,SuperGattCallback gatt,long sensorptr,String text) {
    if(detailPage!=null&&detailPage.isOpen())
        detailPage.dismiss();
    String name=gatt!=null?gatt.SerialNumber:(sensorptr!=0L?Natives.namefromSensorptr(sensorptr):null);
    final M3Page page=M3Page.open(act,name!=null?name:act.getString(R.string.sensor));
    detailPage=page;
    page.help(R.string.sensorinfo);

    /* Sensor information text, as in the old Info screen. */
    if(gatt!=null||sensorptr!=0L||(text!=null&&!text.isEmpty())) {
        LinearLayout info=page.section(R.string.jm_sensors_section_info);
        TextView infoText=page.note(info,"");
        infoText.setTextIsSelectable(true);
        infoText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        infoText.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
        infoText.setLineSpacing(0,1.15f);
        Runnable showInfo=() -> {
            String t=gatt!=null?gatt.getinfo():(sensorptr!=0L?Natives.sensortextfromSensorptr(sensorptr):text);
            if((t==null||t.isEmpty())&&text!=null)
                t=text;
            infoText.setText(SensorUi.html(t));
            };
        showInfo.run();
        page.refresher(showInfo);
        }

    /* Bluetooth connection (bluediag.showinfo) */
    if(gatt!=null) {
        LinearLayout con=page.section(R.string.jm_sensors_section_connection);
        ViewGroup address=page.item(con,R.drawable.ic_jm_bluetooth,"",null,null);
        ViewGroup streaming=page.item(con,R.drawable.ic_jm_sensors,"",null,null);
        ViewGroup since=page.item(con,R.drawable.ic_jm_schedule,act.getString(R.string.jm_sensors_since),null,null);
        ViewGroup connection=page.item(con,R.drawable.ic_jm_sync_alt,act.getString(R.string.connection),null,null);
        ViewGroup key=page.item(con,R.drawable.ic_jm_key,act.getString(R.string.unlock_key),null,null);
        ViewGroup glucose=page.item(con,R.drawable.ic_jm_water_drop,act.getString(R.string.glucose_value),null,null);
        for(View v:new View[]{address,connection,key,glucose})
            SensorUi.summaryLines(v,6);
        Runnable showCon=() -> {
            M3Page.setTitle(address,gatt.mActiveDeviceAddress==null?act.getString(R.string.jm_sensors_address_unknown):gatt.mActiveDeviceAddress);
            StringBuilder sum=new StringBuilder();
            String type=sensorType(act,gatt);
            if(type!=null)
                sum.append(type);
            if(gatt.readrssi<0) {
                if(sum.length()>0)
                    sum.append('\n');
                sum.append(act.getString(R.string.jm_sensors_rssi,gatt.readrssi));
                }
            M3Page.setSummary(address,sum);
            String st=streamingText(act,gatt);
            streaming.setVisibility(st.isEmpty()?View.GONE:View.VISIBLE);
            M3Page.setTitle(streaming,st);
            M3Page.setSummary(since,bluediag.datestr(gatt.starttime));
            M3Page.setSummary(connection,row(act,gatt.constatchange,gatt.constatstatusstr));
            M3Page.setSummary(key,row(act,gatt.wrotepass,gatt.handshake));
            M3Page.setSummary(glucose,row(act,gatt.charcha,null));
            };
        showCon.run();
        page.refresher(showCon);
        }

    /* Settings of this sensor (old Info screen and bluediag options) */
    LinearLayout set=page.section(R.string.jm_sensors_section_settings);
    if(sensorptr!=0L) {
        page.toggle(set,R.drawable.ic_jm_visibility_off,act.getString(R.string.hide),act.getString(R.string.jm_sensors_hide_summary),
            () -> Natives.getHidefromSensorptr(sensorptr),on -> {
                Natives.setHidefromSensorptr(sensorptr,on);
                act.requestRender();
                });
        final int minvalue=Natives.getMinimalWarmup(sensorptr);
        page.slider(set,act.getString(R.string.jm_sensors_warmup),0,MAX_PROGRESS,1,valueToProgress(Natives.getManualWarmupMinutes(sensorptr),minvalue),
            v -> act.getString(R.string.jm_sensors_minutes,progressToValue(v,minvalue)),v -> {
                int warmup=progressToValue(v,minvalue);
                if(warmup<=minvalue)
                    warmup=0;
                Natives.setManualWarmupMinutes(sensorptr,warmup);
                act.requestRender();
                });
        page.note(set,act.getString(R.string.jm_sensors_warmup_summary));
        }
    if(gatt!=null) {
        if(Natives.optionStreamHistory(gatt.dataptr))
            page.toggle(set,R.drawable.ic_jm_history,act.getString(R.string.streamhistory),act.getString(R.string.jm_sensors_streamhistory_summary),
                Natives::getStreamHistory,Natives::setStreamHistory);
        else if(gatt.sensorgen==3||gatt.sensorgen==0x30||gatt.sensorgen==0x40)
            page.toggle(set,R.drawable.ic_jm_alarm,act.getString(R.string.jm_sensors_alarmclock),act.getString(R.string.jm_sensors_alarmclock_summary),
                Natives::getalarmclock,Natives::setalarmclock);
        }
    if(sensorptr!=0L&&(Natives.calibrateNR(sensorptr,0)>0||Natives.calibrateNR(sensorptr,1)>0))
        page.link(set,R.drawable.ic_jm_tune,act.getString(R.string.calibrations),null,() -> CalibrateListPage.show(act,sensorptr));
    if(set.getChildCount()==0)
        M3Page.sectionView(set).setVisibility(View.GONE);

    /* Actions */
    LinearLayout acts=page.section(R.string.jm_sensors_section_actions);
    final String serial=name;
    if(sensorptr!=0L&&!Natives.activeSensor(sensorptr)) {
        page.item(acts,R.drawable.ic_jm_replay,act.getString(R.string.use),act.getString(R.string.jm_sensors_use_summary),() -> {
            useAgain(act,sensorptr);
            page.dismiss();
            reloadIfOpen(act);
            });
        }
    if(gatt!=null) {
        page.item(acts,R.drawable.ic_jm_bluetooth_searching,act.getString(R.string.forget),act.getString(R.string.jm_sensors_forget_summary),() -> {
            gatt.searchforDeviceAddress();
            gatt.close();
            SensorBluetooth.startscan();
            page.toast(act.getString(R.string.jm_sensors_searching));
            page.refresh();
            });
        if(!Natives.optionStreamHistory(gatt.dataptr)&&gatt.sensorgen==0x10&&Natives.getSiSubtype(gatt.dataptr)==3) {
            page.item(acts,R.drawable.ic_jm_restart_alt,act.getString(R.string.resetname),act.getString(R.string.jm_sensors_reset_summary),() ->
                M3Page.confirm(act,act.getString(R.string.resettitle),act.getString(R.string.resetmessage),act.getString(R.string.resetname),() -> {
                    if(gatt.sensorgen!=0x10||Natives.getSiSubtype(gatt.dataptr)!=3)
                        return;
                    Log.i(LOG_ID,"resetbutton");
                    Natives.setResetSibionics2(gatt.dataptr,true);
                    page.toast(act.getString(R.string.jm_sensors_reset_done));
                    }));
            }
        if(gatt.sensorgen==0x50) {
            page.item(acts,R.drawable.ic_jm_link_off,act.getString(R.string.divorcename),act.getString(R.string.jm_sensors_unpair_summary),() ->
                M3Page.confirm(act,gatt.SerialNumber,act.getString(R.string.divorcemessage),act.getString(R.string.divorcename),() -> {
                    var aid=(AidexXGattCallback)gatt;
                    aid.startUnpair(new UnpairOverlayHost(act,R.string.releasingsensor),res -> {
                        aid.finishSensor();
                        SensorBluetooth.sensorEnded(aid.SerialNumber);
                        act.requestRender();
                        return true;
                        });
                    page.dismiss();
                    }));
            page.item(acts,R.drawable.ic_jm_cleaning_services,act.getString(R.string.clear),act.getString(R.string.jm_sensors_clear_summary),() ->
                M3Page.confirm(act,gatt.SerialNumber,act.getString(R.string.clearmessage),act.getString(R.string.clear),() -> {
                    var aid=(AidexXGattCallback)gatt;
                    aid.startClear(new UnpairOverlayHost(act,R.string.clearingmemory),res -> {
                        act.requestRender();
                        return true;
                        });
                    page.dismiss();
                    }));
            }
        }
    if(gatt!=null||sensorptr!=0L) {
        page.item(acts,R.drawable.ic_jm_stop_circle,act.getString(R.string.finish),act.getString(R.string.jm_sensors_finish_summary),() ->
            M3Page.confirm(act,serial!=null?serial:act.getString(R.string.sensor),act.getString(R.string.finishsensormessage),act.getString(R.string.finish),() -> {
                if(gatt!=null) {
                    gatt.finishSensor();
                    SensorBluetooth.sensorEnded(gatt.SerialNumber);
                    }
                else {
                    Log.i(LOG_ID,"confirmFinish");
                    Natives.finishfromSensorptr(sensorptr);
                    SensorBluetooth.sensorEnded(serial);
                    }
                act.requestRender();
                page.dismiss();
                reloadIfOpen(act);
                }));
        }
    if(acts.getChildCount()==0)
        M3Page.sectionView(acts).setVisibility(View.GONE);

    final ScheduledFuture<?> scheduled=Applic.scheduler.scheduleAtFixedRate(() -> act.runOnUiThread(() -> {
        if(page.isOpen())
            page.refresh();
        }),29,29,TimeUnit.SECONDS);
    page.onClose(() -> {
        scheduled.cancel(false);
        if(detailPage==page)
            detailPage=null;
        if(listPage!=null&&listPage.isOpen())
            listPage.refresh();
        });
    }

private static void reloadIfOpen(MainActivity act) {
    if(listPage!=null&&listPage.isOpen())
        reload(act);
    }
}
