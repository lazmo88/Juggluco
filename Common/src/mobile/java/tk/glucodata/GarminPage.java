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

import static tk.glucodata.util.timestring;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputFilter;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.autocomplete.MaterialAutoCompleteTextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import tk.glucodata.m3.M3Page;
import tk.glucodata.nums.AllData;

/*
 * Kerfstok (Garmin) status and configuration (GarminStatus) as Material 3
 * pages.  Same AllData calls; every control applies at once, as before.
 */
final class GarminPage {
private static final String LOG_ID="GarminPage";
private final MainActivity act;
private final AllData alldata;
private final M3Page page;
private long selectedPeerId;
private boolean updating=false;
private List<AllData.GarminDeviceInfo> shownPeers=java.util.Collections.emptyList();

private final MaterialAutoCompleteTextView deviceView;
private final TextView statusHead,statusView;
private final MaterialSwitch active,glucose,numbers;
private final MaterialButtonToggleGroup transport,l3;
private final View l3Box;
private final MaterialButton sync,next;

static void show(MainActivity act,AllData alldata) {
    new GarminPage(act,alldata);
    }

private AllData.GarminDeviceInfo current() {
    try {
        for(var info:alldata.getGarminDeviceInfos())
            if(info.id==selectedPeerId) return info;
        }
    catch(Throwable e) { Log.stack(LOG_ID,"current",e); }
    return null;
    }

private void needDevice() {
    page.toast(act.getString(R.string.garmin_select_device));
    }

private GarminPage(MainActivity act,AllData alldata) {
    this.act=act;
    this.alldata=alldata;
    alldata.refreshGarminDevices(act);
    selectedPeerId=alldata.getNumbersDeviceId();
    page=M3Page.open(act,"Kerfstok");
    page.help(R.string.kerfstok);

    LinearLayout dev=page.section(R.string.jm_share_garmin_sec_watch);
    deviceView=page.choice(dev,act.getString(R.string.watch),new ArrayList<CharSequence>(),-1,i -> {
        if(updating) return;
        var infos=alldata.getGarminDeviceInfos();
        if(i>=0&&i<infos.size()) {
            selectedPeerId=infos.get(i).id;
            show();
            }
        });
    active=page.toggle(dev,R.drawable.ic_jm_power_settings_new,act.getString(R.string.active),null,() -> { var i=current(); return i!=null&&i.active; },on -> {
        if(updating) return;
        if(!alldata.setGarminActive(act,selectedPeerId,on)) needDevice();
        show();
        });
    glucose=page.toggle(dev,R.drawable.ic_jm_bloodtype,act.getString(R.string.glucose),act.getString(R.string.jm_share_garmin_glucose_summary),() -> { var i=current(); return i!=null&&i.glucose; },on -> {
        if(updating) return;
        if(!alldata.setGarminGlucose(act,selectedPeerId,on)) needDevice();
        show();
        });
    numbers=page.toggle(dev,R.drawable.ic_jm_medication,act.getString(R.string.use_for_numbers),null,() -> { var i=current(); return i!=null&&i.numbers; },this::numbersChanged);
    String l3label=(act.getString(R.string.directsensor)+act.getString(R.string.connectionl)).trim()+" (Libre3)";
    l3=page.segmented(dev,l3label,new CharSequence[]{capital(act.getString(R.string.phone)),capital(act.getString(R.string.watch))},0,i -> {
        if(updating) return;
        boolean enable=i==1;
        if(!alldata.setGarminLibre3Direct(act,selectedPeerId,enable)) needDevice();
        else if(enable&&!GarminLibre3.switchCurrentSensor())
            alldata.setGarminLibre3Direct(act,selectedPeerId,false);
        show();
        });
    l3Box=(View)l3.getParent();

    LinearLayout conn=page.section(R.string.jm_share_garmin_sec_connection);
    int mode=alldata.getGarminTransportMode(act);
    transport=page.segmented(conn,act.getString(R.string.jm_share_host_method),new CharSequence[]{act.getString(R.string.transport_automatic),"Garmin Connect",act.getString(R.string.transport_bluetooth)},
        modeIndex(mode),i -> {
            if(updating) return;
            alldata.setGarminTransportMode(act,i==1?AllData.GARMIN_TRANSPORT_CONNECT:(i==2?AllData.GARMIN_TRANSPORT_DIRECT:AllData.GARMIN_TRANSPORT_AUTOMATIC));
            });
    sync=page.button(M3Page.Style.TONAL,act.getString(R.string.sync),R.drawable.ic_jm_sync,() -> alldata.sync(selectedPeerId));
    next=page.button(M3Page.Style.TONAL,act.getString(R.string.sendqueue),R.drawable.ic_jm_send,() -> alldata.nextmessage(selectedPeerId));
    MaterialButton reinit=page.button(M3Page.Style.TONAL,act.getString(R.string.reinit),R.drawable.ic_jm_restart_alt,() -> alldata.reinit(act,selectedPeerId));
    page.buttons(conn,sync,reinit,next);

    LinearLayout status=page.section(R.string.jm_share_lv_sec_status);
    statusHead=page.note(status,"");
    statusHead.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
    statusView=page.note(status,"");
    statusView.setTextIsSelectable(true);

    LinearLayout cfg=page.section(R.string.jm_share_garmin_sec_config);
    page.link(cfg,R.drawable.ic_jm_settings,act.getString(R.string.config),act.getString(R.string.jm_share_garmin_config_summary),() -> config(act,alldata,selectedPeerId));

    show();
    Runnable refresh=new Runnable() { public void run() {
        if(!page.isOpen()) return;
        refreshDetails();
        page.root.postDelayed(this,1000L);
        }};
    page.root.postDelayed(refresh,1000L);
    page.onClose(() -> page.root.removeCallbacks(refresh));
    page.refresher(this::show);
    }

private static String capital(String s) {
    if(s==null||s.isEmpty()) return s;
    return Character.toUpperCase(s.charAt(0))+s.substring(1);
    }
private static int modeIndex(int mode) {
    return mode==AllData.GARMIN_TRANSPORT_CONNECT?1:(mode==AllData.GARMIN_TRANSPORT_DIRECT?2:0);
    }
private static void check(MaterialButtonToggleGroup g,int i) {
    g.check(g.getChildAt(i).getId());
    }

private void numbersChanged(boolean on) {
    if(updating) return;
    if(selectedPeerId==-1L) {
        needDevice();
        show();
        return;
        }
    if(!on) {
        if(!alldata.setNumbersDeviceEnabled(act,selectedPeerId,false)) needDevice();
        show();
        return;
        }
    if(alldata.getNumbersDeviceId()==selectedPeerId) {
        show();
        return;
        }
    AllData.GarminDeviceInfo target=null;
    for(AllData.GarminDeviceInfo info:alldata.getGarminDeviceInfos())
        if(info.id==selectedPeerId) { target=info; break; }
    if(target==null) {
        needDevice();
        show();
        return;
        }
    final AllData.GarminDeviceInfo t=target;
    final boolean[] done={false};
    M3Page.dialog(act).setTitle(R.string.garmin_change_numbers_title)
        .setMessage(act.getString(R.string.garmin_change_numbers_message,alldata.getNumbersDeviceName(),t.name))
        .setNegativeButton(android.R.string.cancel,null)
        .setPositiveButton(R.string.garmin_change,(d,w) -> {
            done[0]=true;
            if(!alldata.setNumbersDevice(act,t.id)) needDevice();
            show();
            })
        .setOnDismissListener(d -> { if(!done[0]) show(); })
        .show();
    }

private int selectedPosition(List<AllData.GarminDeviceInfo> infos) {
    for(int i=0;i<infos.size();i++) if(infos.get(i).id==selectedPeerId) return i;
    return 0;
    }
private boolean samePeerRows(List<AllData.GarminDeviceInfo> infos) {
    if(infos.size()!=shownPeers.size()) return false;
    for(int i=0;i<infos.size();i++)
        if(infos.get(i).id!=shownPeers.get(i).id||!GarminStatus.displaystr(act,infos.get(i)).equals(GarminStatus.displaystr(act,shownPeers.get(i))))
            return false;
    return true;
    }

void show() {
    if(!page.isOpen()) return;
    updating=true;
    try {
        var infos=alldata.getGarminDeviceInfos();
        if(selectedPeerId==-1L) selectedPeerId=alldata.getNumbersDeviceId();
        int pos=selectedPosition(infos);
        @SuppressWarnings("unchecked") android.widget.ArrayAdapter<CharSequence> a=(android.widget.ArrayAdapter<CharSequence>)deviceView.getAdapter();
        a.clear();
        for(var info:infos) a.add(GarminStatus.displaystr(act,info));
        a.notifyDataSetChanged();
        if(!infos.isEmpty()) {
            selectedPeerId=infos.get(pos).id;
            M3Page.select(deviceView,GarminStatus.displaystr(act,infos.get(pos)));
            }
        else
            M3Page.select(deviceView,act.getString(R.string.garmin_no_device));
        shownPeers=infos;
        updateDetails(infos,pos);
        }
    catch(Throwable e) { Log.stack(LOG_ID,"show",e); }
    finally { updating=false; }
    }

private void refreshDetails() {
    try {
        var infos=alldata.getGarminDeviceInfos();
        if(!samePeerRows(infos)) { show(); return; }
        updating=true;
        updateDetails(infos,selectedPosition(infos));
        }
    catch(Throwable e) { Log.stack(LOG_ID,"refreshDetails",e); }
    finally { updating=false; }
    }

private static void rowVis(View sw,boolean on) {
    ((View)sw.getParent().getParent()).setVisibility(on?View.VISIBLE:View.GONE);
    }
private static void rowEnabled(View sw,boolean on) {
    View row=(View)sw.getParent().getParent();
    row.setEnabled(on);
    sw.setEnabled(on);
    row.setAlpha(on?1f:0.38f);
    }

private void updateDetails(List<AllData.GarminDeviceInfo> infos,int pos) {
    check(transport,modeIndex(alldata.getGarminTransportMode(act)));
    AllData.GarminDeviceInfo info=infos.isEmpty()?null:infos.get(pos);
    rowEnabled(active,info!=null);
    active.setChecked(info!=null&&info.active);
    rowEnabled(numbers,info!=null);
    numbers.setChecked(info!=null&&info.numbers);
    rowVis(glucose,info!=null);
    glucose.setChecked(info!=null&&info.glucose);
    if(info==null) statusHead.setText(R.string.garmin_no_device);
    else statusHead.setText(act.getString(R.string.garmin_connection_status,act.getString(info.direct?R.string.transport_bluetooth:R.string.garmin_connect)));
    StringBuilder b=new StringBuilder();
    if(info!=null) {
        Context c=act;
        String none=c.getString(R.string.garmin_none);
        b.append(c.getString(R.string.garmin_kerfstok_status,GarminStatus.communicationStatus(c,info.communicationStatus))).append('\n');
        b.append(c.getString(R.string.garmin_last_confirmed_reply,info.lastAcknowledged==0?none:timestring(info.lastAcknowledged))).append('\n');
        b.append(c.getString(R.string.garmin_glucose_confirmed,info.lastGlucoseAcknowledged==0?none:timestring(info.lastGlucoseAcknowledged)));
        if(info.lastGlucoseAcknowledged!=0)
            b.append('\n').append(c.getString(R.string.garmin_glucose_sample,timestring(info.acknowledgedGlucoseTime*1000L)));
        b.append('\n').append(c.getString(R.string.garmin_glucose_ack,c.getString(info.timestampedGlucoseAck?R.string.garmin_ack_timestamped:R.string.garmin_ack_legacy)));
        b.append('\n').append(c.getString(R.string.garmin_last_sent,info.lastSend==0?none:timestring(info.lastSend)));
        b.append('\n').append(c.getString(R.string.garmin_last_received,info.lastReceived==0?none:timestring(info.lastReceived)));
        if(info.lastStatus!=null&&info.lastStatusTime>=info.lastSend)
            b.append('\n').append(c.getString(R.string.garmin_transport_result,GarminStatus.transportResult(c,info.lastStatus)));
        if(info.lastError!=null)
            b.append('\n').append(c.getString(R.string.garmin_last_error,GarminStatus.lastError(c,info.lastError)));
        }
    statusView.setText(b.toString());
    statusView.setVisibility(b.length()>0?View.VISIBLE:View.GONE);
    check(l3,(info!=null&&info.libre3Direct)?1:0);
    l3Box.setVisibility((info!=null&&info.libre3Installed)?View.VISIBLE:View.GONE);
    boolean l3pos=info!=null&&info.active&&info.libre3Installed;
    for(int i=0;i<l3.getChildCount();i++) l3.getChildAt(i).setEnabled(l3pos);
    boolean selectedNumbers=info!=null&&info.numbers;
    next.setVisibility(info!=null&&info.active&&selectedNumbers&&alldata.waiting(selectedPeerId)&&!alldata.isSending()?View.VISIBLE:View.GONE);
    sync.setVisibility(info!=null?View.VISIBLE:View.GONE);
    sync.setEnabled(info!=null&&info.active&&selectedNumbers);
    }

/* Old kerfstokconfig dialog. */
static void config(MainActivity act,AllData alldata,long peerId) {
    final M3Page page=M3Page.open(act,R.string.jm_share_garmin_config_title);
    page.help(R.string.garminconfig);
    LinearLayout sec=page.section((CharSequence)null);
    page.link(sec,R.drawable.ic_jm_open_in_new,act.getString(AllData.appmissing<0?R.string.watchappinstalled:R.string.getkerfstok),act.getString(R.string.jm_share_garmin_store_summary),() -> {
        Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://apps.garmin.com/en-US/apps/b6348ccc-86d8-4780-8013-d9e19fed5260"));
        try {
            if(intent.resolveActivity(act.getPackageManager())!=null) act.startActivity(intent);
            else page.toast(act.getString(R.string.jm_share_no_browser));
            }
        catch(Throwable th) { Log.stack(LOG_ID,"garmin",th); }
        });
    page.link(sec,R.drawable.ic_jm_badge,act.getString(R.string.garmin_app_id_button),Natives.getgarminid(),() -> appId(act,alldata,page));
    page.link(sec,R.drawable.ic_jm_bolt,act.getString(R.string.shutcuts),null,() -> new Shortcuts().mkshortlistview(act));
    page.toggle(sec,R.drawable.ic_jm_dark_mode,act.getString(R.string.darkmode),null,() -> alldata.getKerfstokBlack(act,peerId),on -> alldata.setcolor(act,peerId,on));
    if(alldata.canConfigureGarminAlarms(peerId))
        page.link(sec,R.drawable.ic_jm_notifications,act.getString(R.string.alarms),null,() -> GarminAlarms.show(act,alldata,page.root,peerId));
    }

/* Old setidview. */
static void appId(MainActivity act,AllData alldata,M3Page parent) {
    final String defaultid=Natives.getdefaultid();
    final String garminid=Natives.getgarminid();
    final boolean[] useDefault={defaultid.equals(garminid)};
    final M3Page page=M3Page.open(act,R.string.garmin_watch_app_id);
    LinearLayout sec=page.section((CharSequence)null);
    final TextInputEditText edit=page.field(sec,act.getString(R.string.garmin_watch_app_id),garminid,M3Page.TEXT,null,null);
    edit.setFilters(new InputFilter[]{(source,start,end,dest,dstart,dend) -> {
        StringBuilder builder=new StringBuilder();
        for(int i=start;i<end;i++) {
            if(Character.digit(source.charAt(i),16)==-1) return "";
            builder.append(Character.toUpperCase(source.charAt(i)));
            }
        return builder.toString();
        }});
    edit.setEnabled(!useDefault[0]);
    page.toggle(sec,R.drawable.ic_jm_restart_alt,act.getString(R.string.defaultname),null,() -> useDefault[0],on -> {
        useDefault[0]=on;
        if(on) edit.setText(defaultid);
        edit.setEnabled(!on);
        });
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            boolean changed=false;
            if(useDefault[0]) {
                if(!defaultid.equals(garminid)) changed=Natives.setgarminid(null);
                }
            else {
                String id=M3Page.text(edit);
                if(id.length()!=32) {
                    M3Page.error(edit,act.getString(R.string.garmin_id_length,id.length()));
                    return;
                    }
                if(!garminid.equals(id)&&!(changed=Natives.setgarminid(id))) {
                    M3Page.error(edit,act.getString(R.string.garmin_id_change_failed));
                    return;
                    }
                }
            if(changed) alldata.restartGarmin(act);
            page.dismiss();
            parent.dismiss();
            }));
    }
}
