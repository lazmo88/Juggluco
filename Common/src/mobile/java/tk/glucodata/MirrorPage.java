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

import static tk.glucodata.Natives.getInvertColors;

import android.graphics.Paint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import tk.glucodata.m3.M3Page;
import tk.glucodata.settings.ExchangePage;

/*
 * Mirror (Backup.realmkbackupview) as a Material 3 page: this phone's
 * addresses and port, the list of connections, and the mirror options.
 * Tapping a connection shows its status (old showhostinfo) with Off, QR and
 * Modify.  Close does what the old Close did (reset network if changed,
 * update the service, back to the menu).
 */
public final class MirrorPage {
private MirrorPage() {}
private static final java.text.DateFormat hhmm=new java.text.SimpleDateFormat("HH:mm",java.util.Locale.US);

/* One line summary of a connection, as the old list row. */
static String hostText(MainActivity act,int pos) {
    String ICElabelstr=Natives.getICElabel(pos);
    if(ICElabelstr!=null&&ICElabelstr.length()<16)
        return act.getString(R.string.ICElabeltooshort);
    StringBuilder sb=new StringBuilder();
    String[] names=Natives.getbackupIPs(pos);
    int transport=Natives.getbackuptransport(pos);
    if(transport==BleMirror.TRANSPORT_AUTOMATIC||transport==BleMirror.TRANSPORT_TCP) {
        if(ICElabelstr==null) {
            sb.append((names!=null&&names.length!=0)?names[0]:(Natives.detectIP(pos)?"Detect":"---"));
            if(!Natives.getbackuphostpassive(pos))
                sb.append(':').append(Natives.getbackuphostport(pos));
            }
        else
            sb.append("ICE");
        }
    else if(transport==BleMirror.TRANSPORT_BLUETOOTH)
        sb.append(act.getString(R.string.transport_bluetooth));
    else
        sb.append(act.getString(R.string.transport_messages));
    return sb.toString();
    }
static String hostDetails(MainActivity act,int pos) {
    StringBuilder sb=new StringBuilder();
    if(Natives.getbackuphostnums(pos)) sb.append(act.getString(R.string.amountsname)).append(", ");
    if(Natives.getbackuphostscans(pos)) sb.append(act.getString(R.string.scansname)).append(", ");
    if(Natives.getbackuphoststream(pos)) sb.append(act.getString(R.string.streamname)).append(", ");
    if(sb.length()>0) sb.insert(0,"→ ").setLength(sb.length()-2);
    if((Natives.getbackuphostreceive(pos)&2)!=0) {
        if(sb.length()>0) sb.append("  ");
        sb.append("← ").append(act.getString(R.string.jm_share_mirror_receives));
        }
    long date=Natives.lastuptodate(pos);
    if(date!=0L) {
        if(sb.length()>0) sb.append('\n');
        sb.append("⇋ ").append(bluediag.datestr(date));
        }
    if(Natives.getHostDeactivated(pos)) {
        if(sb.length()>0) sb.append('\n');
        sb.append(act.getString(R.string.off));
        }
    return sb.toString();
    }

public static void show(MainActivity act,Backup backup,boolean lightback) {
    backup.configchanged=false;
    String[] thishost=Backup.gethostnames();
    if(thishost[3]!=null)
        Natives.networkpresent();
    final M3Page page=M3Page.open(act,R.string.mirror);
    page.help(R.string.connectionoverview);

    /* This phone */
    LinearLayout phone=page.section(R.string.jm_share_mirror_this);
    page.item(phone,R.drawable.ic_jm_wifi,"Wi-Fi",thishost[1]==null?"—":thishost[1],null);
    if(thishost[2]!=null)
        page.item(phone,R.drawable.ic_jm_lan,"Bluetooth PAN",thishost[2],null);
    if(thishost[0]!=null)
        page.item(phone,R.drawable.ic_jm_lan,"Wi-Fi Direct",thishost[0],null);
    final TextInputEditText port=page.field(phone,act.getString(R.string.port),Natives.getreceiveport(),M3Page.INTEGER,null,value -> {
        Natives.setreceiveport(value);
        page.toast(act.getString(R.string.saved));
        });
    page.link(phone,R.drawable.ic_jm_router,act.getString(R.string.turnserver),null,() -> turnServer(act));
    String err=Natives.serverError();
    if(err!=null&&err.length()>0)
        page.item(phone,R.drawable.ic_jm_error,act.getString(R.string.jm_share_web_error),err,null);

    /* Connections */
    LinearLayout hosts=page.section(R.string.jm_share_mirror_connections);
    final LinearLayout rows=new LinearLayout(page.ctx);
    rows.setOrientation(LinearLayout.VERTICAL);
    page.custom(hosts,rows);
    final Runnable fill=() -> {
        rows.removeAllViews();
        int n=Natives.backuphostNr();
        if(n==0)
            page.empty(rows,R.drawable.ic_jm_devices,act.getString(R.string.jm_share_mirror_none),act.getString(R.string.jm_share_mirror_none_body));
        for(int i=0;i<n;i++) {
            final int pos=i;
            String label=Natives.getbackuplabel(pos);
            String main=hostText(act,pos);
            String title=label!=null&&label.length()>0?label:main;
            String details=hostDetails(act,pos);
            String summary=(label!=null&&label.length()>0)?main+(details.isEmpty()?"":"\n"+details):details;
            ViewGroup row=page.link(rows,R.drawable.ic_jm_sync_alt,title,summary,() -> hostInfo(act,backup,page,pos));
            TextView t=row.findViewById(R.id.jm_row_title);
            if(Natives.getHostDeactivated(pos))
                t.setPaintFlags(t.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);
            }
        };
    fill.run();
    page.refresher(fill);
    /* The old host editor reports changes to this adapter. */
    backup.hostadapt=backup.new HostViewAdapter(page.root);
    backup.hostadapt.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
        @Override public void onChanged() { fill.run(); }
        @Override public void onItemRangeChanged(int a,int b) { fill.run(); }
        @Override public void onItemRangeInserted(int a,int b) { fill.run(); }
        @Override public void onItemRangeRemoved(int a,int b) { fill.run(); }
        });
    MaterialButton add=page.button(M3Page.Style.FILLED,act.getString(R.string.addconnectionbutton),R.drawable.ic_jm_add,() -> backup.addhostview(act,page.root));
    if(BuildConfig.minSDK>=20)
        page.buttons(hosts,page.button(M3Page.Style.TONAL,act.getString(R.string.autoqr),R.drawable.ic_jm_qr_code,() -> autoQR(act,backup)),add);
    else
        page.buttons(hosts,add);

    /* Options */
    LinearLayout opts=page.section(R.string.jm_share_mirror_options);
    page.toggle(opts,R.drawable.ic_jm_block,act.getString(R.string.dontchangeamounts),null,Natives::staticnum,isChecked -> {
        Natives.setstaticnum(isChecked);
        ActivityHealth.settingsChanged(act);
        if(isChecked)
            BluetoothGlucoseMeter.stopDevices();
        else
            BluetoothGlucoseMeter.startDevices();
        });
    if(android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.M)
        page.link(opts,R.drawable.ic_jm_power_settings_new,act.getString(R.string.dozemode),null,() -> Battery.batteryscreen(act,page.root));
    page.item(opts,R.drawable.ic_jm_sync,act.getString(R.string.sync),act.getString(R.string.jm_share_mirror_sync_summary),() -> {
        Applic.switchSync();
        page.toast(act.getString(R.string.sync));
        });
    page.item(opts,R.drawable.ic_jm_restart_alt,act.getString(R.string.reinit),act.getString(R.string.jm_share_mirror_reinit_summary),() -> {
        MessageSender.reinit();
        page.toast(act.getString(R.string.reinit));
        });

    page.onClose(() -> {
        if(lightback) act.lightBars(!getInvertColors());
        if(backup.configchanged) {
            Natives.resetnetwork();
            Applic.wakemirrors();
            }
        Applic.updateservice(act,Natives.getusebluetooth());
        act.showui=false;
        Applic.app.getHandler().postDelayed(act::hideSystemUI,1);
        if(M3Page.top(act)==null&&Menus.on)
            Menus.show(act);
        ExchangePage.refreshTop(act);
        });
    }

/* Old showhostinfo: status of one connection, Off, QR and Modify. */
static void hostInfo(MainActivity act,Backup backup,M3Page parent,int pos) {
    String label=Natives.getbackuplabel(pos);
    final M3Page page=M3Page.open(act,label!=null&&label.length()>0?label:hostText(act,pos));
    page.onClose(parent::refresh);
    LinearLayout sec=page.section((CharSequence)null);
    page.toggle(sec,R.drawable.ic_jm_block,act.getString(R.string.off),null,() -> Natives.getHostDeactivated(pos),isChecked -> {
        Natives.setHostDeactivated(pos,isChecked);
        BleMirror.configurationChanged();
        });
    page.link(sec,R.drawable.ic_jm_edit,act.getString(R.string.modify),null,() -> {
        page.dismiss();
        backup.changehostview(act,pos,parent.root);
        });
    if(BuildConfig.minSDK>=20)
        page.link(sec,R.drawable.ic_jm_qr_code,"QR",act.getString(R.string.jm_share_mirror_qr_summary),() -> QRmake.show(act,Natives.getbackJson(pos)));
    LinearLayout status=page.section(R.string.jm_share_lv_sec_status);
    String mirrorInfo=Natives.mirrorStatus(pos);
    final String bluetoothStatus=BleMirror.statusForConnection(pos);
    if(bluetoothStatus!=null)
        mirrorInfo+="<p><b>Bluetooth mirror:</b> "+TextUtils.htmlEncode(bluetoothStatus)+"</p>";
    TextView info=page.note(status,"");
    util.sethtml(info,mirrorInfo);
    info.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
    info.setTextIsSelectable(true);
    }

/* Old makeAutoQR as a Material dialog. */
static void autoQR(MainActivity act,Backup backup) {
    CharSequence[] items={
        act.getString(R.string.sendto)+" · "+act.getString(R.string.homenet),
        act.getString(R.string.sendto)+" · "+act.getString(R.string.internet),
        act.getString(R.string.receivefrom)+" · "+act.getString(R.string.homenet),
        act.getString(R.string.receivefrom)+" · "+act.getString(R.string.internet)};
    M3Page.dialog(act).setTitle(R.string.autoqr)
        .setItems(items,(d,which) -> {
            switch(which) {
                case 0: backup.makeQR(act,Natives.makeHomeSender()); break;
                case 1: backup.makeQR(act,Natives.makeICESender()); break;
                case 2: Backup.dowhenasked(act,false,false,false,() -> backup.makeQR(act,Natives.makeHomeReceiver())); break;
                case 3: Backup.dowhenasked(act,false,false,false,() -> backup.makeQR(act,Natives.makeICEReceiver())); break;
                }
            })
        .setNeutralButton(R.string.helpname,(d,w) -> help.help(R.string.autoqrmessage,act))
        .setNegativeButton(R.string.cancel,null)
        .show();
    }

/* TurnServer.show as a Save/Cancel page. */
public static void turnServer(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.turnserver);
    page.help(R.string.turnservers);
    final boolean absent=Natives.TurnServerNR()==0;
    LinearLayout sec=page.section((CharSequence)null);
    final TextInputEditText host=page.field(sec,act.getString(R.string.hostname),absent?"":Natives.getTurnHost(0),M3Page.URL,null,null);
    final TextInputEditText port=page.field(sec,act.getString(R.string.port),absent?"":(""+Natives.getTurnPort(0)),M3Page.INTEGER,null,null);
    final TextInputEditText user=page.field(sec,act.getString(R.string.username),absent?"":Natives.getTurnUser(0),M3Page.TEXT,null,null);
    final TextInputEditText pass=page.field(sec,act.getString(R.string.password),absent?"":Natives.getTurnPassword(0),M3Page.PASSWORD,null,null);
    tk.glucodata.settings.ExchangePage.plain(pass);
    if(!absent)
        page.buttons(sec,page.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,() ->
            M3Page.confirm(act,act.getString(R.string.delete)+"?",null,act.getString(R.string.delete),() -> {
                Natives.deleteTurnServer(0);
                page.dismiss();
                })));
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            String portstr=M3Page.text(port);
            int portnum;
            try { portnum=Integer.parseInt(portstr); }
            catch(Throwable e) {
                M3Page.error(port,portstr+act.getString(R.string.invalidport));
                return;
                }
            if(portnum>65535) {
                M3Page.error(port,act.getString(R.string.portrange));
                return;
                }
            Natives.setTurnPort(0,portnum);
            Natives.setTurnHost(0,String.valueOf(host.getText()));
            Natives.setTurnUser(0,String.valueOf(user.getText()));
            Natives.setTurnPassword(0,String.valueOf(pass.getText()));
            page.dismiss();
            }));
    }
}
