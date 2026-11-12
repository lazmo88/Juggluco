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

import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import tk.glucodata.m3.M3Page;

/*
 * Sound of one alarm kind (RingTones.mkviews) as a Material 3 page.
 *
 * Same values, same validation and the same Save as the old screen: nothing
 * is written until Save; Back discards and stops a playing test sound.
 * Kinds: 0 low, 1 high, 2 value notification, 3 reminders, 4 loss of signal,
 * 5 very low, 6 very high, 7 soon low, 8 soon high.
 */
public final class RingTonePage {
private RingTonePage() {}
private static final String LOG_ID="RingTonePage";

static boolean glucoseAlarm(int kind) {
    return kind<2||kind>4;
    }

static String title(Context context,String uri,int kind) {
    if(uri==null||uri.isEmpty()) // the built-in sound: its "title" is a resource number
        return context.getString(R.string.defaultname);
    try {
        Ringtone ringtone=Notify.mkrings(uri,kind);
        return ringtone.getTitle(context);
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"ringtone title",th);
        return "Unknown";
        }
    }

/* One line for the row that opens this page: "Ringtone · vibrate". */
public static String summary(Context context,int kind) {
    StringBuilder sb=new StringBuilder();
    if(Natives.alarmhassound(kind)) {
        String uri=Natives.readring(kind);
        sb.append(title(context,(uri==null||uri.isEmpty())?null:uri,kind));
        }
    else
        sb.append(context.getString(R.string.jm_alerts_no_sound));
    if(Natives.alarmhasvibration(kind))
        sb.append(" · ").append(context.getString(R.string.jm_alerts_vibrate_short));
    if(Natives.alarmhasflash(kind)&&Flash.hasFlash(context))
        sb.append(" · ").append(context.getString(R.string.jm_alerts_flash_short));
    return sb.toString();
    }

/* Opens the page for kind, as new RingTones(kind).mkviews() does. */
public static M3Page open(MainActivity act,int kind,@Nullable String label) {
    show(act,new RingTones(kind),label);
    return M3Page.top(act);
    }

/* The list row around a switch made by M3Page.toggle(). */
public static View rowOf(View control) {
    return (View)control.getParent().getParent();
    }
/* Disabled rows: not clickable and drawn at 38% like M3 disabled content. */
public static void enable(View row,boolean on) {
    row.setEnabled(on);
    row.setAlpha(on?1f:.38f);
    View sw=row.findViewById(R.id.jm_row_end);
    if(sw instanceof ViewGroup&&((ViewGroup)sw).getChildCount()>0)
        ((ViewGroup)sw).getChildAt(0).setEnabled(on);
    }

static String noColon(Context ctx,int res) {
    String s=ctx.getString(res).trim();
    while(s.endsWith(":")||s.endsWith("："))
        s=s.substring(0,s.length()-1).trim();
    return s;
    }

public static void show(MainActivity act,RingTones rt,@Nullable String label) {
    final int kind=rt.kind;
    final boolean glucosealarm=glucoseAlarm(kind);
    final M3Page page=M3Page.open(act,label!=null?label:act.getString(R.string.jm_alerts_reminder_sound));
    page.subtitle(act.getString(R.string.jm_alerts_sound_title));
    page.help(R.string.ringtone);
    final boolean hasflash=Flash.hasFlash(act);
    final boolean disturbAvailable=Build.VERSION.SDK_INT>=Build.VERSION_CODES.M;

    /* Sound */
    LinearLayout soundSec=page.section(R.string.soundname);
    final boolean[] def={rt.uri==null||rt.uri.length()==0};
    final View[] pickRow={null};
    final MaterialSwitch[] defSwitch={null};
    MaterialSwitch sound=page.toggle(soundSec,R.drawable.ic_jm_volume_up,act.getString(R.string.soundname),null,
        () -> Natives.alarmhassound(kind),on -> {
            enable(rowOf(defSwitch[0]),on);
            enable(pickRow[0],on);
            });
    defSwitch[0]=page.toggle(soundSec,R.drawable.ic_jm_settings_backup_restore,act.getString(R.string.jm_alerts_use_default),null,() -> def[0],on -> {
        def[0]=on;
        if(on) {
            rt.uri=null;
            if(rt.name!=null)
                rt.name.setText(title(act,rt.uri,kind));
            }
        pickRow[0].setVisibility(on?View.GONE:View.VISIBLE);
        });
    ViewGroup pick=page.link(soundSec,R.drawable.ic_jm_music_note,act.getString(R.string.ringtonename),title(act,rt.uri,kind),() -> {
        page.hideKeyboard();
        try {
            Intent intent=new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_ALL);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,false);
            final int request=MainActivity.REQUEST_RINGTONE|kind;
            act.startActivityForResult(Intent.createChooser(intent,null),request);
            }
        catch(Throwable th) {
            Log.stack(LOG_ID,"picker",th);
            page.toast(act.getString(R.string.no_ringtone_picker_found));
            }
        });
    pickRow[0]=pick;
    pick.setVisibility(def[0]?View.GONE:View.VISIBLE);
    /* MainActivity's ringtone result calls RingTones.setring(), which updates
     * rt.uri and this text. */
    rt.name=pick.findViewById(R.id.jm_row_summary);
    final boolean hassound=sound.isChecked();
    enable(rowOf(defSwitch[0]),hassound);
    enable(pick,hassound);
    MaterialSwitch disturb=null;
    if(disturbAvailable) {
        disturb=page.toggle(soundSec,R.drawable.ic_jm_do_not_disturb_on,act.getString(R.string.disturb),act.getString(R.string.jm_alerts_disturb_summary),
            () -> Natives.getalarmdisturb(kind),on -> {
                if(on)
                    act.asknotificationAccess();
                });
        }

    /* Vibration and flash */
    LinearLayout more=page.section(R.string.vibrationname);
    MaterialSwitch vibration=page.toggle(more,R.drawable.ic_jm_vibration,act.getString(R.string.vibrationname),null,() -> Natives.alarmhasvibration(kind),on -> {});
    MaterialSwitch flash=null;
    if(hasflash)
        flash=page.toggle(more,R.drawable.ic_jm_flashlight_on,act.getString(R.string.flash),null,() -> Natives.alarmhasflash(kind),on -> {});

    /* Timing */
    LinearLayout timing=page.section(R.string.jm_alerts_section_timing);
    TextInputEditText duredit=page.field(timing,act.getString(R.string.jm_alerts_duration),String.valueOf(rt.duration),M3Page.INTEGER,act.getString(R.string.jm_unit_seconds),null);
    TextInputEditText waitedit=null;
    if(glucosealarm) {
        waitedit=page.field(timing,act.getString(R.string.jm_alerts_snooze),String.valueOf(rt.susp),M3Page.INTEGER,act.getString(R.string.jm_unit_minutes),null);
        page.note(timing,act.getString(R.string.jm_alerts_snooze_summary));
        }

    final MaterialSwitch fDisturb=disturb,fFlash=flash;
    final TextInputEditText fWait=waitedit;
    page.onClose(() -> {
        Notify.stopalarm();
        if(RingTones.one==rt)
            RingTones.one=null;
        rt.name=null;
        });
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.play),R.drawable.ic_jm_play_arrow,() -> {
            String str=M3Page.text(duredit);
            try {
                page.hideKeyboard();
                int dur=str.isEmpty()?rt.duration:Integer.parseInt(str);
                Ringtone ringtone=Notify.mkrings(def[0]?null:rt.uri,kind);
                Notify.playring(ringtone,dur,sound.isChecked(),hasflash&&fFlash!=null&&fFlash.isChecked(),vibration.isChecked(),
                    !disturbAvailable||(fDisturb!=null&&fDisturb.isChecked()),kind);
                }
            catch(Throwable e) {
                Log.stack(LOG_ID,"play",e);
                page.toast(act.getString(R.string.can_t_play)+str+act.getString(R.string.seconds));
                }
            }),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            Notify.stopalarm();
            M3Page.error(duredit,null);
            if(fWait!=null)
                M3Page.error(fWait,null);
            try {
                String str=M3Page.text(duredit);
                int durs;
                try {
                    durs=Integer.parseInt(str);
                    }
                catch(NumberFormatException e) {
                    M3Page.error(duredit,act.getString(R.string.jm_invalid_number));
                    return;
                    }
                if(durs<0) {
                    M3Page.error(duredit,act.getString(R.string.duration_can_t_be_negative)+durs);
                    return;
                    }
                if(durs>65535) {
                    M3Page.error(duredit,durs+act.getString(R.string.too_large_maximum_65535));
                    return;
                    }
                short wa=0;
                if(fWait!=null) {
                    String wstr=M3Page.text(fWait);
                    int w;
                    try {
                        w=Integer.parseInt(wstr);
                        }
                    catch(NumberFormatException e) {
                        M3Page.error(fWait,act.getString(R.string.jm_invalid_number));
                        return;
                        }
                    if(w<0) {
                        M3Page.error(fWait,act.getString(R.string.delay_can_t_be_negative)+w);
                        return;
                        }
                    if(w>4095) {
                        M3Page.error(fWait,w+act.getString(R.string.too_large_maximum_4095));
                        return;
                        }
                    wa=(short)w;
                    }
                /* Same order of writes as the old Save. */
                Natives.writealarmduration(kind,durs);
                if(fWait!=null)
                    SuperGattCallback.writealarmsuspension(kind,wa);
                if(def[0])
                    rt.uri="";
                if(!Natives.writering(kind,rt.uri,sound.isChecked(),hasflash&&fFlash!=null&&fFlash.isChecked(),vibration.isChecked())) {
                    page.toast(rt.uri+act.getString(R.string.too_large));
                    return;
                    }
                }
            catch(Throwable e) {
                Log.stack(LOG_ID,"save",e);
                page.toast(act.getString(R.string.can_t_use_specification));
                return;
                }
            if(disturbAvailable&&fDisturb!=null)
                Natives.setalarmdisturb(kind,fDisturb.isChecked());
            page.dismiss();
            }));
    }
}
