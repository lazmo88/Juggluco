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

import static tk.glucodata.Natives.getlibreemail;
import static tk.glucodata.Natives.getlibrepass;
import static tk.glucodata.Natives.getuselibreview;
import static tk.glucodata.Natives.setlibreemail;
import static tk.glucodata.Natives.setlibrepass;
import static tk.glucodata.Natives.setuselibreview;
import static tk.glucodata.Natives.wakelibreview;
import static tk.glucodata.NightPost.success;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Calendar;

import tk.glucodata.m3.M3Page;
import tk.glucodata.settings.ExchangePage;
import tk.glucodata.settings.LibreNumbersPage;

/*
 * LibreView (Libreview.config) as a Material 3 page.
 *
 * As before: account fields, RU and "Send to LibreView" are stored by Save
 * (with the old checks), Back/Cancel discard them; the other switches apply
 * at once.  Account ID and the resend date are their own small flows.
 */
public final class LibreviewPage {
private LibreviewPage() {}

private static final class State {
    MainActivity act;
    TextInputEditText email,pass;
    String wasemail;
    boolean isRussia;
    boolean[] russia={false};
    }

/* Old getgegs predicate: validate (when turning on) and store account data. */
private static boolean store(State s,boolean use) {
    final MainActivity act=s.act;
    String emailstr=s.email.getText()==null?"":s.email.getText().toString();
    String passstr=s.pass.getText()==null?"":s.pass.getText().toString();
    M3Page.error(s.email,null);
    M3Page.error(s.pass,null);
    if(use) {
        if(emailstr.length()<3) {
            M3Page.error(s.email,act.getString(R.string.emailaddresstooshort));
            return false;
            }
        if(emailstr.length()>255) {
            M3Page.error(s.email,act.getString(R.string.emailaddresstoolong));
            return false;
            }
        if(passstr.length()<3) {
            M3Page.error(s.pass,act.getString(R.string.password8));
            return false;
            }
        if(passstr.length()>36) {
            M3Page.error(s.pass,act.getString(R.string.password36));
            return false;
            }
        }
    if(!s.wasemail.equals(emailstr))
        setlibreemail(emailstr);
    setlibrepass(passstr);
    if(emailstr.length()==0&&passstr.length()==0)
        Natives.clearlibreFromMSec(0L);
    if(s.isRussia!=s.russia[0])
        Natives.setLibreCountry((!s.isRussia)?4:(Applic.unit==1?0:1));
    return true;
    }

static String statusText() {
    String st=Libreview.librestatus;
    return st==success?(Libreview.posttime+": "+st):st;
    }

public static void show(MainActivity act,Runnable done) {
    final M3Page page=M3Page.open(act,R.string.jm_share_lv_title);
    page.help(R.string.libreview);
    page.onClose(() -> {
        if(done!=null)
            done.run();
        ExchangePage.refreshTop(act);
        });
    final State s=new State();
    s.act=act;
    s.wasemail=getlibreemail();
    if(s.wasemail==null)
        s.wasemail="";
    s.isRussia=Natives.getLibreCountry()==4;
    s.russia[0]=s.isRussia;
    final boolean usedlibre=getuselibreview();
    final boolean[] sendto={usedlibre};

    /* Account */
    LinearLayout account=page.section(R.string.jm_share_lv_sec_account);
    s.email=page.field(account,act.getString(R.string.email),s.wasemail,M3Page.EMAIL,null,null);
    String passwas=getlibrepass();
    s.pass=page.field(account,act.getString(R.string.password),passwas==null?"":passwas,M3Page.PASSWORD,null,null);
    page.toggle(account,R.drawable.ic_jm_language,act.getString(R.string.jm_share_lv_russia),null,() -> s.russia[0],on -> s.russia[0]=on);
    final View idRow=page.link(account,R.drawable.ic_jm_badge,act.getString(R.string.jm_share_lv_accountid),String.valueOf(Natives.getlibreAccountIDnumber()),
        () -> {
            store(s,false);
            accountId(act,s,page);
            });
    page.refresher(() -> M3Page.setSummary(idRow,String.valueOf(Natives.getlibreAccountIDnumber())));

    /* Upload */
    LinearLayout upload=page.section(R.string.jm_share_lv_sec_upload);
    page.toggle(upload,R.drawable.ic_jm_cloud_upload,act.getString(R.string.uselibreview),null,() -> sendto[0],on -> sendto[0]=on);
    page.toggle(upload,R.drawable.ic_jm_visibility,act.getString(R.string.libreisviewed),null,Natives::getLibreIsViewed,Natives::setLibreIsViewed);
    ExchangePage.statusRow(page,upload,R.drawable.ic_jm_medication,act.getString(R.string.sendamounts),Natives::getSendNumbers,
        () -> LibreNumbersPage.show(act,0,act.getString(R.string.sendamounts),Natives.getSendNumbers(),on -> {
            Natives.setSendNumbers(on);
            page.refresh();
            }));
    if(usedlibre)
        page.item(upload,R.drawable.ic_jm_send,act.getString(R.string.sendnow),null,() -> {
            wakelibreview(0);
            page.toast(act.getString(R.string.sendnow));
            });
    page.item(upload,R.drawable.ic_jm_restart_alt,act.getString(R.string.changestartbutton),act.getString(R.string.jm_share_lv_resend_summary),
        () -> resendDate(act));

    /* Status */
    LinearLayout status=page.section(R.string.jm_share_lv_sec_status);
    final TextView statusView=page.note(status,statusText());
    statusView.setTextIsSelectable(true);
    page.refresher(() -> statusView.setText(statusText()));

    /* Advanced */
    LinearLayout adv=page.section(R.string.jm_share_lv_sec_advanced);
    page.toggle(adv,R.drawable.ic_jm_schedule,act.getString(R.string.librecurrent),null,Natives::getLibreCurrent,Natives::setLibreCurrent);
    page.toggle(adv,R.drawable.ic_jm_smartphone,act.getString(R.string.jm_share_lv_newlibre),null,Natives::getnewLibre3Activation,Natives::setnewLibre3Activation);
    page.item(adv,R.drawable.ic_jm_info,act.getString(R.string.jm_share_lv_receiverid),String.valueOf(Libreview.getlibreReceiverID()),null);

    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            boolean turnonlibre=sendto[0];
            if(!store(s,turnonlibre))
                return;
            setuselibreview(turnonlibre);
            page.dismiss();
            }));
    }

/* Old resendDateDialog + askclearlibreview: date and time, then confirm. */
private static void resendDate(MainActivity act) {
    final Calendar cal=Calendar.getInstance();
    cal.setTimeInMillis(System.currentTimeMillis()-89*24*60*60*1000L);
    android.content.Context ctx=M3Page.themed(act);
    DatePickerDialog date=new DatePickerDialog(ctx,(view,year,month,day) -> {
        cal.set(Calendar.YEAR,year);
        cal.set(Calendar.MONTH,month);
        cal.set(Calendar.DAY_OF_MONTH,day);
        new TimePickerDialog(ctx,(tv,h,m) -> {
            cal.set(Calendar.HOUR_OF_DAY,h);
            cal.set(Calendar.MINUTE,m);
            final long from=cal.getTimeInMillis();
            String when=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.DEFAULT,java.text.DateFormat.SHORT).format(from);
            M3Page.dialog(act).setTitle(R.string.resendquestion)
                .setMessage(act.getString(R.string.sendfrom)+" "+when+"\n\n"+act.getString(R.string.resendmessage))
                .setNegativeButton(R.string.cancel,null)
                .setNeutralButton(R.string.helpname,(d,w) -> help.help(R.string.changestart,act))
                .setPositiveButton(R.string.ok,(d,w) -> Natives.clearlibreFromMSec(from))
                .show();
            },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),DateFormat.is24HourFormat(act)).show();
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH));
    date.setTitle(R.string.sendfrom);
    date.getDatePicker().setMaxDate(System.currentTimeMillis());
    date.show();
    }

/* Old getAccountid dialog as a small page. */
private static void accountId(MainActivity act,State s,M3Page parent) {
    final M3Page page=M3Page.open(act,R.string.jm_share_lv_accountid_title);
    page.help(R.string.getaccountidhelp);
    page.onClose(parent::refresh);
    LinearLayout sec=page.section((CharSequence)null);
    final boolean setmanually=Natives.manualLibreAccountIDnumber()!=-1L;
    final boolean[] manual={setmanually};
    final TextInputEditText[] field={null};
    final View[] fromRow={null};
    final View[] writedown={null};
    Runnable apply=() -> {
        View box=(View)field[0].getParent().getParent();
        box.setVisibility(manual[0]?View.VISIBLE:View.GONE);
        writedown[0].setVisibility(manual[0]?View.VISIBLE:View.GONE);
        fromRow[0].setVisibility(manual[0]?View.GONE:View.VISIBLE);
        };
    page.toggle(sec,R.drawable.ic_jm_edit,act.getString(R.string.manual),act.getString(R.string.jm_share_lv_accountid_manual_summary),
        () -> manual[0],on -> { manual[0]=on; apply.run(); });
    writedown[0]=page.note(sec,act.getString(R.string.writedown));
    field[0]=page.field(sec,act.getString(R.string.jm_share_lv_accountid),String.valueOf(Natives.getlibreAccountIDnumber()),M3Page.TEXT,null,null);
    fromRow[0]=page.item(sec,R.drawable.ic_jm_cloud,act.getString(R.string.fromlibreview),null,() -> {
        if(!store(s,true)) {
            page.dismiss();
            return;
            }
        M3Page.dialog(act).setTitle(R.string.getaccountidquestion).setMessage(R.string.getaccountidmessage)
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.ok,(d,w) -> {
                Natives.setlibreAccountIDnumber(-1L);
                Natives.askServerforAccountID();
                })
            .show();
        });
    apply.run();
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            if(manual[0]) {
                String idstr=M3Page.text(field[0]);
                if(idstr.length()==0) {
                    M3Page.error(field[0],act.getString(R.string.noaccountidspecified));
                    return;
                    }
                try {
                    long id=Long.parseLong(idstr);
                    Natives.setlibreAccountIDnumber(id);
                    Applic.argToaster(act,act.getString(R.string.saved)+" "+id,android.widget.Toast.LENGTH_SHORT);
                    }
                catch(Throwable th) {
                    M3Page.error(field[0],act.getString(R.string.wrongformat)+idstr);
                    return;
                    }
                }
            else
                Natives.setlibreAccountIDnumber(-1L);
            page.dismiss();
            }));
    }
}
