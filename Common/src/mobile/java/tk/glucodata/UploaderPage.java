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

import static tk.glucodata.bluediag.datestr;

import android.widget.LinearLayout;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import tk.glucodata.m3.M3Page;
import tk.glucodata.settings.ExchangePage;
import tk.glucodata.settings.LibreNumbersPage;

/*
 * Nightscout uploader (NightPost.config) as a Material 3 page.
 * URL, secret, Active and v3 are stored together by Save
 * (Natives.setNightUploader), Back/Cancel discard, as before.  Send amounts,
 * Resend and Send now act at once, as before.
 */
public final class UploaderPage {
private UploaderPage() {}

public static void show(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.jm_share_ns_title);
    page.help(R.string.NightPost);
    page.onClose(() -> ExchangePage.refreshTop(act));

    LinearLayout site=page.section(R.string.jm_share_ns_sec_site);
    final boolean[] active={Natives.getuseuploader()};
    final boolean[] v3={Natives.getnightscoutV3()};
    page.toggle(site,R.drawable.ic_jm_cloud_upload,act.getString(R.string.active),null,() -> active[0],on -> active[0]=on);
    final TextInputEditText url=page.field(site,act.getString(R.string.jm_share_ns_url),Natives.getnightuploadurl(),M3Page.URL,null,null);
    String secretwas=Natives.getnightuploadsecret();
    final TextInputEditText secret=page.field(site,act.getString(R.string.jm_share_web_secret_label),secretwas==null?"":secretwas,M3Page.PASSWORD,null,null);
    tk.glucodata.settings.ExchangePage.plain(secret);
    page.toggle(site,R.drawable.ic_jm_tune,act.getString(R.string.jm_share_ns_v3),null,() -> v3[0],on -> v3[0]=on);

    LinearLayout data=page.section(R.string.jm_share_web_sec_data);
    ExchangePage.statusRow(page,data,R.drawable.ic_jm_medication,act.getString(R.string.sendamounts),Natives::getpostTreatments,
        () -> LibreNumbersPage.show(act,1,act.getString(R.string.sendamounts),Natives.getpostTreatments(),on -> {
            Natives.setpostTreatments(on);
            page.refresh();
            }));
    page.item(data,R.drawable.ic_jm_send,act.getString(R.string.sendnow),act.getString(R.string.jm_share_ns_send_summary),() -> {
        Natives.wakeuploader();
        page.toast(act.getString(R.string.sendnow));
        });
    page.item(data,R.drawable.ic_jm_restart_alt,act.getString(R.string.resenddata),act.getString(R.string.jm_share_ns_resend_summary),
        () -> M3Page.confirm(act,act.getString(R.string.resenddata),act.getString(R.string.resendquestion),act.getString(R.string.ok),Natives::resetuploader));

    LinearLayout status=page.section(R.string.jm_share_ns_sec_status);
    final android.widget.TextView statusView=page.note(status,datestr(NightPost.uploadtime)+": "+NightPost.uploadstatus);
    statusView.setTextIsSelectable(true);
    page.refresher(() -> statusView.setText(datestr(NightPost.uploadtime)+": "+NightPost.uploadstatus));
    Runnable tick=new Runnable() { public void run() {
        if(!page.isOpen()) return;
        statusView.setText(datestr(NightPost.uploadtime)+": "+NightPost.uploadstatus);
        statusView.postDelayed(this,1000L);
        }};
    statusView.postDelayed(tick,1000L);
    page.onClose(() -> statusView.removeCallbacks(tick));

    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            String u=M3Page.text(url);
            if(active[0]&&u.isEmpty()) {
                M3Page.error(url,act.getString(R.string.jm_share_ns_url_bad));
                return;
                }
            page.dismiss();
            Natives.setNightUploader(u,secret.getText()==null?"":secret.getText().toString(),active[0],v3[0]);
            ExchangePage.refreshTop(act);
            }));
    }
}
