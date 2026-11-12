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
/*      Wed May 06 21:13:17 CEST 2026                                                */
package tk.glucodata;

import static tk.glucodata.Applic.scheduler;
import static tk.glucodata.GS3ID.GS3IDstatus;
import static tk.glucodata.PhotoScan.deviceAdded;

import android.text.method.LinkMovementMethod;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;

import tk.glucodata.m3.M3Page;

/*
 * Sibionics GS3 account ID after scanning a GS3 sensor, as one Material 3
 * page (was three cream boxes: gs3Number, getUserid "Manually",
 * gs3fromserver "Retrieve from server").  Same Natives/GS3ID calls, toasts
 * and leave-confirmations; the sensor is added (deviceAdded) when the page
 * is left, as before.
 */
class GetGS3ID {
private static final String LOG_ID="GetGS3ID";

static private boolean validate(MainActivity act,String emailstr,String passstr) {
    if(emailstr.length()<3) {
        Applic.argToaster(act,act.getString(R.string.emailaddresstooshort)+emailstr,Toast.LENGTH_SHORT);
        return false;
        }
    if(emailstr.length()>255) {
        Applic.argToaster(act,act.getString(R.string.emailaddresstoolong)+emailstr,Toast.LENGTH_SHORT);
        return false;
        }
    if(passstr.length()<3) {
        Applic.argToaster(act,act.getString(R.string.password8)+passstr,Toast.LENGTH_SHORT);
        return false;
        }
    if(passstr.length()>36) {
        Applic.argToaster(act,act.getString(R.string.password36)+passstr,Toast.LENGTH_SHORT);
        return false;
        }
    return true;
    }

static void gs3Number(String name,MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.jm_sensors_gs3_title);
    page.subtitle(name);
    page.help(R.string.SibionicsServer);

    LinearLayout about=page.section((CharSequence)null);
    TextView info=page.note(about,SensorUi.html(stripIndex(act.getString(R.string.gs3number))));
    info.setMovementMethod(LinkMovementMethod.getInstance());
    info.setLinkTextColor(page.color(com.google.android.material.R.attr.colorPrimary));

    /* Current ID and manual entry (was "Manually") */
    LinearLayout idsec=page.section(R.string.jm_sensors_gs3_id);
    ViewGroup current=page.item(idsec,R.drawable.ic_jm_badge,"",act.getString(R.string.jm_sensors_gs3_current),null);
    Runnable showId=() -> {
        long id=Natives.getGS3id();
        M3Page.setTitle(current,id==0L?act.getString(R.string.jm_sensors_gs3_none):Long.toString(id));
        };
    showId.run();
    page.refresher(showId);
    long oldid=Natives.getGS3id();
    final String[] prevID={oldid==0L?"":Long.toString(oldid)};
    final TextInputEditText editid=page.field(idsec,act.getString(R.string.jm_sensors_gs3_enter),prevID[0],M3Page.INTEGER,null,null);
    page.buttons(idsec,page.button(M3Page.Style.TONAL,act.getString(R.string.save),0,() -> {
        String idstr=M3Page.text(editid);
        try {
            if(!idstr.isEmpty()) {
                long id=Long.parseLong(idstr);
                Natives.saveGS3id(id);
                Applic.argToaster(act,act.getString(R.string.saved)+" "+id,Toast.LENGTH_SHORT);
                prevID[0]=idstr;
                page.hideKeyboard();
                page.refresh();
                }
            else {
                Applic.argToaster(act,act.getString(R.string.noaccountidspecified),Toast.LENGTH_SHORT);
                }
            }
        catch(Throwable th) {
            Applic.argToaster(act,act.getString(R.string.wrongformat)+idstr,Toast.LENGTH_SHORT);
            Log.stack(LOG_ID,"parse account id",th);
            }
        }));

    /* Retrieve from the Sibionics server (was "Retrieve from server") */
    LinearLayout server=page.section(R.string.retrieve);
    String emailLabel=act.getString(R.string.email).trim();
    if(emailLabel.endsWith(":"))
        emailLabel=emailLabel.substring(0,emailLabel.length()-1);
    final TextInputEditText email=page.field(server,emailLabel,"",M3Page.EMAIL,null,null);
    final TextInputEditText editpass=page.field(server,act.getString(R.string.password),"",M3Page.PASSWORD,null,null);
    final String[] waspass={""},wasmail={""};
    TextView status=page.note(server,GS3IDstatus);
    status.setVisibility(GS3IDstatus==null||GS3IDstatus.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);
    page.buttons(server,page.button(M3Page.Style.FILLED,act.getString(R.string.getaccountid),R.drawable.ic_jm_cloud_download,() -> {
        String emailstr=M3Page.text(email);
        String passstr=editpass.getText()==null?"":editpass.getText().toString();
        if(!validate(act,emailstr,passstr))
            return;
        page.hideKeyboard();
        var md5pass=Natives.md5sum(passstr);
        status.setVisibility(android.view.View.VISIBLE);
        status.setText(R.string.connecttoserver);
        scheduler.execute(() -> {
            boolean ok=GS3ID.postgetauth(emailstr,md5pass);
            long id=Natives.getGS3id();
            String st=GS3IDstatus;
            Applic.getHandler().post(() -> {
                if(!page.isOpen())
                    return;
                status.setText(st);
                if(ok) {
                    waspass[0]=passstr;
                    wasmail[0]=emailstr;
                    prevID[0]=Long.toString(id);
                    editid.setText(prevID[0]);
                    page.refresh();
                    }
                else
                    Toast.makeText(act,R.string.retrievefailed,Toast.LENGTH_SHORT).show();
                });
            });
        }));

    page.bottomBar(page.button(M3Page.Style.FILLED,act.getString(R.string.ok),0,page::close));

    /* Leaving: the same three questions as the old screens, one at a time. */
    final boolean[] asked={false,false};
    page.closeCheck(() -> {
        if(!asked[0]&&!M3Page.text(editid).equals(prevID[0])) {
            M3Page.confirm(act,act.getString(R.string.withoutsaving),null,act.getString(R.string.jm_sensors_leave),() -> { asked[0]=true; page.close(); });
            return false;
            }
        String pass=editpass.getText()==null?"":editpass.getText().toString();
        if(!asked[1]&&!(wasmail[0].equals(M3Page.text(email))&&waspass[0].equals(pass))) {
            M3Page.confirm(act,act.getString(R.string.leave),act.getString(R.string.emailnotused),act.getString(R.string.jm_sensors_leave),() -> { asked[1]=true; page.close(); });
            return false;
            }
        if(Natives.getGS3id()==0L) {
            M3Page.confirm(act,act.getString(R.string.leave),act.getString(R.string.id0message),act.getString(R.string.jm_sensors_leave),page::dismiss);
            return false;
            }
        return true;
        });
    page.onClose(() -> {
        deviceAdded(act);
        act.requestRender();
        });
    }

/* The help texts start with an index link and a title; the page has its own. */
static String stripIndex(String html) {
    int h=html.indexOf("</h1>");
    if(h>=0)
        return html.substring(h+5);
    return html;
    }
}
