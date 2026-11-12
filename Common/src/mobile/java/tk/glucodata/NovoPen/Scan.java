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
/*                                                                                   */
/*      Sun Apr 16 20:56:20 CEST 2023                                                 */


package tk.glucodata.NovoPen;

import static tk.glucodata.Dialogs.fdatename;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.novopentype;
import static tk.glucodata.Natives.savenovopen;
import static tk.glucodata.Natives.setnovopenttimeandtype;
import static tk.glucodata.Log.showbytes;
import static tk.glucodata.ScanNfcV.failure;
import static tk.glucodata.ScanNfcV.getvibrator;
import static tk.glucodata.ScanNfcV.startvibration;

import android.nfc.Tag;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;

import tk.glucodata.NovoPen.opennov.OpContext;
import tk.glucodata.NovoPen.opennov.OpenNov;

import tk.glucodata.Applic;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.SensorUi;
import tk.glucodata.help;
import tk.glucodata.m3.M3Page;

/* NovoPen / NFC insulin pen scan result as a Material 3 page: from which time
 * doses are added, which insulin label, Cancel / Save.  Same Natives calls and
 * confirmations as before. */
public class Scan {
    static final private String LOG_ID="Scan";
    static public void onTag(MainActivity act, Tag tag) {
        {if(doLog){showbytes("onTag", tag.getId());};}
        var vibrator = getvibrator(act);
        startvibration(vibrator);
        var openNov = new OpenNov();
        var op = openNov.processTag(tag);
        vibrator.cancel();
        if (op == null) {
            failure(vibrator);
            Log.e(LOG_ID, "processTag failed");
        } else {
            if (op.specification == null) {
                Log.e(LOG_ID, "op.specification==null");
            } else {
                    if (op.doses == null) {
                        Log.e(LOG_ID, "op.eventReport.doses==null");
                    } else {
                        Applic.RunOnUiThread(() -> setInsulin(act, op));
                        return;
                }
            }
        }

        final var failread =act.getString(R.string.penfailed);
        Applic.Toaster(failread);
    }
    private static void earlytimeconfirmation(MainActivity act) {
        M3Page.message(act,null,"To get older values you have to scan again");
    }
private static void changeTypeconfirmation(MainActivity act,String type,Runnable save) {
        M3Page.confirm(act,act.getString(R.string.changeinsulin)+type+"?",null,act.getString(R.string.ok),save);
    }

static void setInsulin(MainActivity context, OpContext op) {
    if(Natives.staticnum()) {
            help.help(R.string.staticnum,context);
            return;
           }
    String serial=op.specification.getSerial();
    final long typetime =novopentype(serial);
    int type;
    long lasttime;
    if(typetime!=-1L) {
        type=(int)(typetime>>32);
        long sectime=(typetime&0xFFFFFFFFL);
        lasttime=sectime*1000L;
        final String datestr=fdatename.format(lasttime)      ;
        {if(doLog) {Log.i(LOG_ID,"type= "+type+" Last time: "+String.format("typetime=%X seconds=%X %d",typetime,sectime,sectime)+ " "+datestr);};};
        }
    else {
        type=-1;
        lasttime=0L;
        }
    final ArrayList<String> labels=Natives.getLabels();
    /* as LabelAdapter(..,1): the last label is not offered */
    final ArrayList<String> shown=new ArrayList<>(labels.subList(0,Math.max(0,labels.size()-1)));
    int[] selected={type<0?0:type};

    final M3Page page=M3Page.open(context,R.string.jm_sensors_pen_title);
    page.subtitle("PEN"+serial);
    long[] newtime={lasttime};

    LinearLayout from=page.section(context.getString(R.string.dosesprior).trim().replaceAll(":$",""));
    ViewGroup date=page.link(from,R.drawable.ic_jm_event,context.getString(R.string.jm_sensors_date),SensorUi.date(context,newtime[0]),null);
    ViewGroup time=page.link(from,R.drawable.ic_jm_schedule,context.getString(R.string.jm_sensors_time),SensorUi.time(context,newtime[0]),null);
    Runnable showTime=() -> {
        M3Page.setSummary(date,SensorUi.date(context,newtime[0]));
        M3Page.setSummary(time,SensorUi.time(context,newtime[0]));
        };
    date.setOnClickListener(v -> SensorUi.pickDate(context,newtime[0],t -> { newtime[0]=t; showTime.run(); }));
    time.setOnClickListener(v -> SensorUi.pickTime(context,newtime[0],t -> { newtime[0]=t; showTime.run(); }));

    LinearLayout insulin=page.section(R.string.jm_sensors_section_insulin);
    String typeLabel=context.getString(R.string.type).trim();
    if(typeLabel.endsWith(":"))
        typeLabel=typeLabel.substring(0,typeLabel.length()-1).trim();
    page.choice(insulin,typeLabel,shown,Math.min(selected[0],Math.max(0,shown.size()-1)),i -> {
        selected[0]=i;
        {if(doLog) {Log.i(LOG_ID, "selected " + labels.get(i));};};
        });

    page.bottomBar(
        page.button(M3Page.Style.TONAL,context.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,context.getString(R.string.save),0,() -> {
            var doses=op.doses;
            int ty=selected[0];
            Runnable saveall=()-> {
                int nr=0;
                int lastdose=doses.size()-1;
                for(int d=0;d<=lastdose;++d) {
                    var dose=doses.get(d);
                    int back=savenovopen(dose.referencetime,serial,ty,dose.rawdoses,d==lastdose);
                    if(back<0)  {
                        Applic.Toaster(context.getString(R.string.wentwrong));
                        page.dismiss();
                        return;
                        }
                    else
                        nr+=back;
                    }
                Applic.Toaster(nr+(nr==1?context.getString(R.string.dosis):context.getString(R.string.doses))+context.getString(R.string.saved));
                if(nr>0) context.requestRender();
                page.dismiss();
                };
            Runnable testtype=() -> {
                if(ty==type) {
                    saveall.run();
                    }
                else {
                    changeTypeconfirmation(context,labels.get(ty), saveall);
                    }
                };
            if(lasttime==newtime[0]) {
                testtype.run();
                }
            else {
                setnovopenttimeandtype(newtime[0],ty,serial);
                if(lasttime<newtime[0]) {
                    testtype.run();
                    }
                else {
                    page.dismiss();
                    earlytimeconfirmation(context);
                    }
                }
            }));
    }
}
