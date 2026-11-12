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

import static tk.glucodata.Natives.getICEside;
import static tk.glucodata.Natives.getbackupHasHostname;
import static tk.glucodata.Natives.isWearOS;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;

import tk.glucodata.m3.M3Page;

/*
 * Add / modify mirror connection (Backup.makehostview + changehostview) as a
 * Material 3 page.  The fields, what is shown when, and the Save checks and
 * Natives.changebackuphost call are the old ones; only the layout is new.
 */
public final class MirrorHostPage {
private static final String LOG_ID="MirrorHostPage";
private static final int NRIPS=4;

private final MainActivity act;
private final Backup backup;
private final M3Page page;
private final int hostindex;

private int transport;          // BleMirror.TRANSPORT_*
private final int[] transportValues;
private boolean ice,side,hostname,detect,testip,haslabel,receive,amounts,scans,stream,restore,password;
private int direction;          // 0 passive only, 1 active only, 2 both
private int sendfrom;           // 0 start, 1 now, 2 screen position
private final boolean[] sendchecked;

private MaterialSwitch iceSwitch,labelSwitch;
private View iceLabelBox,sideBox,portBox,hostnameRow,detectRow,testipRow,directionBox,labelBox,startBox,passBox,ipHead;
private TextInputEditText iceLabel,port,label,pass;
private final TextInputEditText[] ips=new TextInputEditText[NRIPS];
private LinearLayout networkSec;

private static View box(TextInputEditText edit) {
    return (View)edit.getParent().getParent();
    }
private static View box(MaterialButtonToggleGroup g) {
    return (View)g.getParent();
    }
private static View row(MaterialSwitch sw) {
    return (View)sw.getParent().getParent();
    }
private static void vis(View v,boolean on) {
    if(v!=null)
        v.setVisibility(on?View.VISIBLE:View.GONE);
    }

public static void show(MainActivity act,Backup backup,int index) {
    new MirrorHostPage(act,backup,index);
    }

private MirrorHostPage(MainActivity act,Backup backup,int index) {
    this.act=act;
    this.backup=backup;
    this.hostindex=index;
    final boolean isnew=index<0;
    page=M3Page.open(act,isnew?act.getString(R.string.jm_share_host_new):act.getString(R.string.jm_share_host_edit));
    page.help(R.string.addconnection);

    /* Read stored values, as changehostview did. */
    String[] names=isnew?null:Natives.getbackupIPs(index);
    String portstr=isnew?Backup.defaultport:Natives.getbackuphostport(index);
    String passstr=isnew?"":Natives.getbackuppassword(index);
    String ICElabelstr=isnew?null:Natives.getICElabel(index);
    boolean hasICE=ICElabelstr!=null;
    final int savedTransport=isnew?BleMirror.TRANSPORT_AUTOMATIC:Natives.getbackuptransport(index);
    boolean messagesAllowed=(!isnew&&isWearOS(index))||savedTransport==BleMirror.TRANSPORT_MESSAGES;
    String labelstr=null;
    if(!isnew) {
        stream=Natives.getbackuphoststream(index);
        scans=Natives.getbackuphostscans(index);
        amounts=Natives.getbackuphostnums(index);
        receive=(Natives.getbackuphostreceive(index)&2)!=0;
        labelstr=Natives.getbackuplabel(index);
        haslabel=labelstr!=null;
        testip=Natives.getbackuptestip(index);
        boolean networkSaved=savedTransport==BleMirror.TRANSPORT_AUTOMATIC||savedTransport==BleMirror.TRANSPORT_TCP;
        if(!hasICE) {
            boolean ispassive=Natives.getbackuphostpassive(index);
            boolean isactive=Natives.getbackuphostactive(index);
            direction=isactive?1:(ispassive?0:2);
            if(networkSaved) {
                detect=Natives.detectIP(index);
                hostname=getbackupHasHostname(index);
                }
            }
        }
    if(isnew||hasICE) {
        hostname=false;
        detect=false;
        direction=2;
        testip=true;
        }
    ice=hasICE;
    side=hasICE&&getICEside(index);
    password=passstr!=null&&passstr.length()>0;
    transport=savedTransport;
    sendchecked=new boolean[]{amounts,scans,stream};
    ArrayList<CharSequence> tnames=new ArrayList<>();
    ArrayList<Integer> tvals=new ArrayList<>();
    tnames.add(act.getString(R.string.transport_automatic)); tvals.add(BleMirror.TRANSPORT_AUTOMATIC);
    tnames.add(act.getString(R.string.transport_tcp)); tvals.add(BleMirror.TRANSPORT_TCP);
    tnames.add(act.getString(R.string.transport_bluetooth)); tvals.add(BleMirror.TRANSPORT_BLUETOOTH);
    if(messagesAllowed) { tnames.add(act.getString(R.string.transport_messages)); tvals.add(BleMirror.TRANSPORT_MESSAGES); }
    transportValues=new int[tvals.size()];
    int tsel=0;
    for(int i=0;i<transportValues.length;i++) {
        transportValues[i]=tvals.get(i);
        if(transportValues[i]==transport) tsel=i;
        }

    /* Connection */
    LinearLayout conn=page.section(R.string.jm_share_host_sec_connection);
    page.choice(conn,act.getString(R.string.jm_share_host_method),tnames,tsel,i -> { transport=transportValues[i]; update(); });
    labelSwitch=page.toggle(conn,R.drawable.ic_jm_label,act.getString(R.string.jm_share_host_label_toggle),act.getString(R.string.jm_share_host_label_summary),
        () -> haslabel,on -> { haslabel=on; update(); });
    label=page.field(conn,act.getString(R.string.jm_share_host_label),labelstr==null?"":labelstr,M3Page.TEXT,null,null);
    labelBox=box(label);
    page.toggle(conn,R.drawable.ic_jm_key,act.getString(R.string.password),act.getString(R.string.jm_share_host_password_summary),
        () -> password,on -> { password=on; update(); });
    pass=page.field(conn,act.getString(R.string.password),passstr==null?"":passstr,M3Page.PASSWORD,null,null);
    tk.glucodata.settings.ExchangePage.plain(pass);
    passBox=box(pass);

    /* Network */
    networkSec=page.section(R.string.jm_share_host_sec_network);
    iceSwitch=page.toggle(networkSec,R.drawable.ic_jm_public,act.getString(R.string.ICE),act.getString(R.string.jm_share_host_ice_summary),
        () -> ice,on -> { ice=on; update(); });
    iceLabel=page.field(networkSec,act.getString(R.string.icelabel),hasICE?ICElabelstr:"",M3Page.TEXT,null,null);
    iceLabelBox=box(iceLabel);
    sideBox=box(page.segmented(networkSec,act.getString(R.string.jm_share_host_side),new CharSequence[]{"0","1"},side?1:0,i -> side=i==1));
    directionBox=box(page.segmented(networkSec,act.getString(R.string.jm_share_host_direction),
        new CharSequence[]{act.getString(R.string.passiveonly),act.getString(R.string.activeonly),act.getString(R.string.both)},direction,i -> {
            direction=i;
            if(i==1) detect=false;
            update();
            }));
    port=page.field(networkSec,act.getString(R.string.port),hasICE?Backup.defaultport:portstr,M3Page.INTEGER,null,null);
    portBox=box(port);
    hostnameRow=row(page.toggle(networkSec,R.drawable.ic_jm_dns,act.getString(R.string.hostname),act.getString(R.string.jm_share_host_hostname_summary),
        () -> hostname,on -> {
            if(on&&!hostname) page.toast("hostname is slow");
            hostname=on;
            update();
            }));
    testipRow=row(page.toggle(networkSec,R.drawable.ic_jm_lan,act.getString(R.string.testip),act.getString(R.string.jm_share_host_testip_summary),
        () -> testip,on -> { testip=on; update(); }));
    detectRow=row(page.toggle(networkSec,R.drawable.ic_jm_search,act.getString(R.string.detect),act.getString(R.string.jm_share_host_detect_summary),
        () -> detect,on -> { detect=on; update(); }));
    ipHead=page.note(networkSec,act.getString(R.string.jm_share_host_ips));
    for(int i=0;i<NRIPS;i++) {
        String v=(names!=null&&i<names.length&&!hasICE)?names[i]:"";
        ips[i]=page.field(networkSec,act.getString(R.string.jm_share_host_ip,i+1),v,M3Page.URL,null,null);
        }

    /* Data */
    LinearLayout data=page.section(R.string.jm_share_web_sec_data);
    page.toggle(data,R.drawable.ic_jm_download,act.getString(R.string.receivefrom),act.getString(R.string.jm_share_host_receive_summary),() -> receive,on -> receive=on);
    page.note(data,act.getString(R.string.jm_share_host_send_head));
    page.toggle(data,R.drawable.ic_jm_medication,act.getString(R.string.amountsname),null,() -> amounts,on -> { amounts=on; update(); });
    page.toggle(data,R.drawable.ic_jm_contactless,act.getString(R.string.scansname),null,() -> scans,on -> { scans=on; update(); });
    page.toggle(data,R.drawable.ic_jm_show_chart,act.getString(R.string.streamname),null,() -> stream,on -> { stream=on; update(); });
    if(Natives.backuphasrestore())
        page.toggle(data,R.drawable.ic_jm_restart_alt,"Restore",null,() -> restore,on -> restore=on);
    startBox=box(page.segmented(data,act.getString(R.string.jm_share_host_sendfrom),
        new CharSequence[]{act.getString(R.string.start),act.getString(R.string.now),util.timestring(Natives.getstarttime())},0,i -> sendfrom=i));
    if(!isnew&&(stream||scans||amounts))
        page.item(data,R.drawable.ic_jm_restart_alt,act.getString(R.string.resenddata),act.getString(R.string.resendwarning),
            () -> M3Page.confirm(act,act.getString(R.string.resenddata)+"?",act.getString(R.string.resendwarning),act.getString(R.string.ok),() -> {
                Natives.resetbackuphost(hostindex);
                backup.configchanged=true;
                }));

    MaterialButton save=page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
        if(save()>=0)
            page.dismiss();
        });
    if(!isnew) {
        MaterialButton delete=page.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,this::deleteConfirmation);
        page.bottomBar(delete,page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),save);
        }
    else
        page.bottomBar(page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),save);
    update();
    }

private boolean network() {
    return transport==BleMirror.TRANSPORT_AUTOMATIC||transport==BleMirror.TRANSPORT_TCP;
    }

/* Old refreshTransportUI + setICE + test1 visibility rules. */
private void update() {
    boolean network=network();
    if(!network&&!haslabel) {
        haslabel=true;
        labelSwitch.setChecked(true);
        }
    labelSwitch.setEnabled(network);
    if(!network&&ice) {
        ice=false;
        iceSwitch.setChecked(false);
        }
    vis(M3Page.sectionView(networkSec),network);
    vis(labelBox,haslabel);
    vis(passBox,password);
    vis(iceLabelBox,ice);
    vis(sideBox,ice);
    boolean plain=network&&!ice;
    vis(directionBox,plain);
    vis(portBox,plain&&direction!=0);
    vis(testipRow,plain&&direction!=1);
    boolean ipsShown=plain&&!(direction==0&&!testip);
    vis(detectRow,plain&&!hostname&&!(direction==1||(direction==0&&!testip)));
    vis(hostnameRow,plain);
    vis(ipHead,ipsShown);
    int ipmax=hostname?1:NRIPS-(detect&&direction!=1?1:0)-(haslabel?1:0);
    for(int i=0;i<NRIPS;i++)
        vis(box(ips[i]),ipsShown&&i<ipmax);
    boolean newsend=(!sendchecked[0]&&amounts)||(!sendchecked[1]&&scans)||(!sendchecked[2]&&stream);
    vis(startBox,newsend);
    }

private void deleteConfirmation() {
    String title=M3Page.text(label);
    if(title.isEmpty())
        title=M3Page.text(ips[0]);
    M3Page.dialog(act).setTitle(title.isEmpty()?" ":title).setMessage(R.string.deleteconnection)
        .setNegativeButton(R.string.cancel,null)
        .setPositiveButton(R.string.delete,(d,w) -> {
            if(hostindex>=0) {
                Natives.deletebackuphost(hostindex);
                if(backup.hostadapt!=null) backup.hostadapt.notifyItemRemoved(hostindex);
                BleMirror.configurationChanged();
                }
            page.dismiss();
            })
        .show();
    }

/* The old saver IntSupplier. */
private int save() {
    final boolean sender=amounts||stream||scans;
    if(!sender&&!receive) {
        page.toast(act.getString(R.string.specifyreceiveordata));
        return -15;
        }
    if(receive&&amounts&&stream&&scans) {
        Applic.argToaster(act,R.string.allsentnoreceive,Toast.LENGTH_LONG);
        return -15;
        }
    page.hideKeyboard();
    int hostnr=Natives.backuphostNr();
    final int selectedTransport=transport;
    final boolean usesNetwork=network();
    final boolean activeonly=direction==1,passiveonly=direction==0;
    boolean doice=usesNetwork&&ice;
    int struse=0;
    String[] names=null;
    final boolean dodetect=usesNetwork&&detect&&!activeonly;
    final String ICEstring=M3Page.text(iceLabel);
    final String selectedLabel=haslabel?String.valueOf(label.getText()):null;
    M3Page.error(label,null); M3Page.error(pass,null); M3Page.error(iceLabel,null); M3Page.error(ips[0],null);
    if((selectedTransport==BleMirror.TRANSPORT_MESSAGES||selectedTransport==BleMirror.TRANSPORT_BLUETOOTH)&&
            (selectedLabel==null||selectedLabel.trim().isEmpty())) {
        M3Page.error(label,act.getString(R.string.transport_needs_label));
        return -15;
        }
    final String passstr=pass.getText()==null?"":pass.getText().toString();
    if(selectedTransport==BleMirror.TRANSPORT_BLUETOOTH&&(!password||passstr.length()==0)) {
        if(!password) page.toast(act.getString(R.string.transport_needs_password));
        else M3Page.error(pass,act.getString(R.string.transport_needs_password));
        return -15;
        }
    if(doice) {
        if(ICEstring.length()<16) {
            M3Page.error(iceLabel,act.getString(R.string.ICElabeltooshort));
            return -16;
            }
        }
    else if(usesNetwork) {
        names=new String[NRIPS];
        if(testip||!passiveonly) {
            for(TextInputEditText e:ips) {
                if(box(e).getVisibility()!=View.VISIBLE) continue;
                String name=M3Page.text(e);
                if(name.length()!=0)
                    names[struse++]=name;
                }
            }
        int ipmax=NRIPS-(dodetect?1:0)-(haslabel?1:0);
        if(struse>=ipmax)
            struse=ipmax;
        if((testip&&!dodetect)||activeonly) {
            if(struse==0) {
                M3Page.error(ips[0],act.getString(R.string.specifyip));
                return -15;
                }
            }
        }
    boolean newsend=startBox.getVisibility()==View.VISIBLE;
    long starttime=(!newsend||sendfrom==0)?0L:(sendfrom==1?System.currentTimeMillis():Natives.getstarttime())/1000L;
    final boolean mirrorSide=doice?side:(hostindex>=0?Natives.getbackupside(hostindex):scans);
    final boolean ordinaryNearby=!doice&&!(hostindex>=0&&isWearOS(hostindex));
    final boolean bleReverse=ordinaryNearby&&hostindex>=0?Natives.getbackupblereverse(hostindex):false;
    final boolean bleclient;
    if(hostindex>=0&&isWearOS(hostindex))
        bleclient=false;
    else if(ordinaryNearby)
        bleclient=(!mirrorSide)^bleReverse;
    else if(hostindex>=0)
        bleclient=Natives.getbackupbleclient(hostindex);
    else
        bleclient=!scans;
    int pos=Natives.changebackuphost(hostindex,names,struse,dodetect,usesNetwork?M3Page.text(port):"0",amounts,stream,scans,restore,receive,activeonly,passiveonly,
        password?passstr:null,starttime,selectedLabel,usesNetwork&&testip,usesNetwork&&hostname,doice?ICEstring:null,mirrorSide,selectedTransport,bleclient);
    if(pos<0) {
        M3Page.message(act,act.getString(R.string.jm_share_host_save_failed),Backup.changehostError(act,pos));
        return pos;
        }
    if(ordinaryNearby)
        Natives.setbackupblereverse(pos,bleReverse);
    Log.i(LOG_ID,"saved mirror index="+pos+" transport="+BleMirror.transportName(selectedTransport));
    if(!receive&&!(amounts&&stream&&scans))
        Applic.argToaster(act,R.string.notalldata,Toast.LENGTH_LONG);
    backup.configchanged=true;
    final boolean needsBlePermission=!doice&&(selectedTransport==BleMirror.TRANSPORT_BLUETOOTH||
            (selectedTransport==BleMirror.TRANSPORT_AUTOMATIC&&!Natives.isWearOS(pos)));
    if(needsBlePermission)
        act.finepermission();
    BleMirror.configurationChanged(pos,true);
    if(needsBlePermission) {
        final String blocker=BleMirror.blockingStatusForConnection(pos);
        if(blocker!=null)
            Applic.argToaster(act,blocker,Toast.LENGTH_LONG);
        }
    if(backup.hostadapt!=null) {
        if(pos==hostnr)
            backup.hostadapt.notifyItemInserted(pos);
        else
            backup.hostadapt.notifyItemChanged(pos);
        }
    return pos;
    }
}
