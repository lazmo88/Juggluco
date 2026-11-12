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

import static tk.glucodata.MainActivity.CHAIN_REQUEST;
import static tk.glucodata.MainActivity.PRIVATE_REQUEST;
import static tk.glucodata.Natives.getreceiveport;

import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import tk.glucodata.m3.M3Page;
import tk.glucodata.settings.ExchangePage;
import tk.glucodata.settings.LibreNumbersPage;

/*
 * Web server settings (Nightscout.show) as a Material 3 page.
 *
 * Switches apply at once, as before.  API secret, ports and interval are
 * stored by Save with the old validation and messages; Back with unsaved
 * changes asks first, like the old "without saving" question.
 */
public final class WebServerPage {
private WebServerPage() {}

private static int parse(String s) {
    try { return Integer.parseInt(s.trim()); }
    catch(Throwable e) { return Integer.MIN_VALUE; }
    }

public static void show(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.jm_share_web_title);
    page.help(R.string.Nightscouthelp);
    page.onClose(() -> ExchangePage.refreshTop(act));

    /* Server */
    LinearLayout server=page.section(R.string.jm_share_web_sec_server);
    final MaterialSwitch active=page.toggle(server,R.drawable.ic_jm_dns,act.getString(R.string.active),null,
        Natives::getusexdripwebserver,Natives::setusexdripwebserver);
    page.toggle(server,R.drawable.ic_jm_smartphone,act.getString(R.string.localonly),null,
        Natives::getXdripServerLocal,Natives::setXdripServerLocal);
    final TextInputEditText http=page.field(server,act.getString(R.string.jm_share_web_http_port),String.valueOf(Natives.gethttpport()),M3Page.INTEGER,null,null);
    final TextInputEditText interval=page.field(server,act.getString(R.string.jm_share_web_interval),String.valueOf(Natives.getinterval()),M3Page.INTEGER,act.getString(R.string.jm_share_seconds),null);
    String err=Natives.nightError();
    if(err!=null&&err.length()>0)
        page.item(server,R.drawable.ic_jm_error,act.getString(R.string.jm_share_web_error),err,null);

    /* Access */
    LinearLayout access=page.section(R.string.jm_share_web_sec_access);
    final String[] oldkey={Natives.getApiSecret()};
    final TextInputEditText secret=page.field(access,act.getString(R.string.jm_share_web_secret_label),oldkey[0],M3Page.PASSWORD,null,null);
    tk.glucodata.settings.ExchangePage.plain(secret);

    /* HTTPS */
    LinearLayout https=page.section(R.string.jm_share_web_sec_https);
    final MaterialSwitch[] ssl={null};
    final boolean[] reverting={false};
    ssl[0]=page.toggle(https,R.drawable.ic_jm_lock,act.getString(R.string.usessl),null,Natives::getuseSSL,isChecked -> {
        if(reverting[0])
            return;
        String res=Natives.setuseSSL(isChecked);
        if(res!=null) {
            reverting[0]=true;
            ssl[0].setChecked(!isChecked);
            reverting[0]=false;
            M3Page.message(act,act.getString(R.string.usessl),res);
            }
        else if(isChecked)
            active.setChecked(true);
        });
    final TextInputEditText sslport=page.field(https,act.getString(R.string.jm_share_web_ssl_port),String.valueOf(Natives.getsslport()),M3Page.INTEGER,null,null);
    page.link(https,R.drawable.ic_jm_key,act.getString(R.string.privatekey),act.getString(R.string.jm_share_web_privkey_summary),
        () -> Nightscout.openfile(act,PRIVATE_REQUEST));
    page.link(https,R.drawable.ic_jm_link,act.getString(R.string.fullchain),act.getString(R.string.jm_share_web_chain_summary),
        () -> Nightscout.openfile(act,CHAIN_REQUEST));

    /* Data */
    LinearLayout data=page.section(R.string.jm_share_web_sec_data);
    ExchangePage.statusRow(page,data,R.drawable.ic_jm_medication,act.getString(R.string.treatments),Natives::getGiveAmounts,
        () -> LibreNumbersPage.show(act,1,act.getString(R.string.treatments),Natives.getGiveAmounts(),on -> {
            Natives.setGiveAmounts(on);
            page.refresh();
            }));
    page.link(data,R.drawable.ic_jm_folder_open,act.getString(R.string.webuploadpages),act.getString(R.string.jm_share_web_pages_summary),
        () -> WebPageUpload.show(act));

    /* Save / Cancel for the text fields */
    page.closeCheck(() -> {
        boolean same=String.valueOf(secret.getText()).equals(oldkey[0]==null?"":oldkey[0])
            &&parse(M3Page.text(sslport))==Natives.getsslport()
            &&parse(M3Page.text(http))==Natives.gethttpport()
            &&parse(M3Page.text(interval))==Natives.getinterval();
        if(same)
            return true;
        M3Page.confirm(act,act.getString(R.string.jm_share_discard),null,act.getString(R.string.jm_share_discard_yes),page::dismiss);
        return false;
        });
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            if(save(act,page,secret,sslport,http,interval,oldkey))
                page.dismiss();
            }));
    }

/* Old Save handler, field errors in place of some toasts. */
private static boolean save(MainActivity act,M3Page page,TextInputEditText secret,TextInputEditText sslport,TextInputEditText http,TextInputEditText interval,String[] oldkey) {
    M3Page.error(secret,null); M3Page.error(sslport,null); M3Page.error(http,null); M3Page.error(interval,null);
    String newkey=secret.getText()==null?"":secret.getText().toString();
    if(newkey.length()>=Nightscout.MAXKEY) {
        M3Page.error(secret,act.getString(R.string.toolongsecret)+Nightscout.MAXKEY);
        return false;
        }
    String portstr=M3Page.text(sslport);
    int portnum=parse(portstr);
    if(portnum==Integer.MIN_VALUE) {
        M3Page.error(sslport,portstr+act.getString(R.string.invalidport));
        return false;
        }
    String httpportstr=M3Page.text(http);
    int httpportnum=parse(httpportstr);
    if(httpportnum==Integer.MIN_VALUE) {
        M3Page.error(http,httpportstr+act.getString(R.string.invalidport));
        return false;
        }
    if(portstr.equals(getreceiveport())||httpportstr.equals(getreceiveport())) {
        M3Page.error(portstr.equals(getreceiveport())?sslport:http,act.getString(R.string.nomirrorport));
        return false;
        }
    if(portnum==httpportnum) {
        M3Page.error(sslport,act.getString(R.string.nohttpport));
        return false;
        }
    if(portnum<1024||portnum>65535||httpportnum<1024||httpportnum>65535) {
        M3Page.error((portnum<1024||portnum>65535)?sslport:http,act.getString(R.string.portrange));
        return false;
        }
    String intervalstr=M3Page.text(interval);
    int intervalnum=parse(intervalstr);
    if(intervalnum==Integer.MIN_VALUE) {
        M3Page.error(interval,act.getString(R.string.jm_share_invalid_number));
        return false;
        }
    if(!newkey.equals(oldkey[0])) {
        oldkey[0]=newkey;
        Applic.argToaster(act,act.getString(R.string.newsecret)+newkey,Toast.LENGTH_LONG);
        Natives.setApiSecret(newkey);
        }
    if(portnum!=Natives.getsslport()) {
        Natives.setsslport(portnum);
        Applic.argToaster(act,act.getString(R.string.newport)+portstr,Toast.LENGTH_LONG);
        if(Natives.getuseSSL())
            Natives.setuseSSL(true);
        }
    boolean warnhttpport=false;
    if(httpportnum!=Natives.gethttpport()) {
        boolean serveractive=Natives.getusexdripwebserver();
        Natives.sethttpport(httpportnum);
        Applic.argToaster(act,act.getString(R.string.newport)+httpportstr,Toast.LENGTH_LONG);
        if(serveractive) {
            Natives.setusexdripwebserver(false);
            util.sleep(1000);
            Natives.setusexdripwebserver(true);
            }
        warnhttpport=httpportnum!=17580;
        }
    Natives.setinterval(intervalnum);
    page.hideKeyboard();
    if(warnhttpport)
        Applic.argToaster(act,R.string.httpportxdripwarning,Toast.LENGTH_LONG);
    else
        Applic.argToaster(act,R.string.saved,Toast.LENGTH_SHORT);
    return true;
    }
}
