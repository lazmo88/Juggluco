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

import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static tk.glucodata.Natives.lastglucose;
import static tk.glucodata.Natives.settouchtalk;

import android.media.AudioAttributes;
import android.speech.tts.Voice;
import android.widget.LinearLayout;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import tk.glucodata.m3.M3Page;

/*
 * Speech settings (Talker.config) as a Material 3 page.
 *
 * Same values and same Save logic as the old screen: nothing is stored until
 * Save; Back and Up discard.  Test speaks the last glucose value (or 8.7)
 * with the values on screen, like before.
 *
 * This is also the reference example for converting old screens to M3Page.
 */
public final class TalkerPage {
private TalkerPage() {}

private static final double LN2=Math.log(2);
private static float toSlider(float ratio) { // speed/pitch ratio -> log2, -2.5..2.5 as the old seek bar
    if(ratio<0.18f)
        ratio=0.18f;
    return (float)(Math.log(ratio)/LN2);
    }
private static float fromSlider(float v) {
    return (float)Math.exp(v*LN2);
    }
private static String ratio(float v) {
    return String.format(Locale.getDefault(),"%.2f×",fromSlider(v));
    }

public static void show(MainActivity act) {
    if(!Talker.istalking())
        SuperGattCallback.newtalker(act);
    final M3Page page=M3Page.open(act,R.string.jm_talk_title);
    page.help(R.string.talkhelp);

    /* What to say */
    LinearLayout what=page.section(R.string.jm_talk_section_what);
    final TextInputEditText[] sepField={null};
    MaterialSwitch active=page.toggle(what,R.drawable.ic_jm_volume_up,act.getString(R.string.speakglucose),null,
        () -> SuperGattCallback.dotalk,on -> { if(sepField[0]!=null) sepField[0].setEnabled(on); });
    TextInputEditText separation=page.field(what,act.getString(R.string.secondsbetween),String.valueOf(Talker.cursep/1000L),M3Page.INTEGER,
        null,null);
    sepField[0]=separation;
    separation.setEnabled(active.isChecked());
    MaterialSwitch touch=page.toggle(what,R.drawable.ic_jm_touch_app,act.getString(R.string.talk_touch),null,Natives::gettouchtalk,on -> {});
    MaterialSwitch messages=page.toggle(what,R.drawable.ic_jm_chat,act.getString(R.string.speakmessages),null,Natives::speakmessages,on -> {});
    MaterialSwitch alarms=page.toggle(what,R.drawable.ic_jm_alarm,act.getString(R.string.speakalarms),null,Natives::speakalarms,on -> {});

    /* Voice */
    LinearLayout voice=page.section(R.string.jm_talk_section_voice);
    final int[] voicePos={Talker.voicepos};
    final MaterialAutoCompleteTextView[] voiceMenu={null};
    Runnable fillVoices=() -> {
        if(voiceMenu[0]==null)
            return;
        ArrayList<String> names=new ArrayList<>();
        for(Voice v:Talker.voiceChoice)
            names.add(v.getName());
        voiceMenu[0].setSimpleItems(names.toArray(new String[0]));
        if(voicePos[0]>=0&&voicePos[0]<names.size())
            M3Page.select(voiceMenu[0],names.get(voicePos[0]));
        else
            M3Page.select(voiceMenu[0],act.getString(names.isEmpty()?R.string.jm_talk_no_voices:R.string.jm_talk_default_voice));
        };
    if(android.os.Build.VERSION.SDK_INT>=21) {
        voiceMenu[0]=page.choice(voice,act.getString(R.string.talker),new ArrayList<String>(),-1,i -> voicePos[0]=i);
        fillVoices.run();
        /* The voices are known once the speech engine has started. */
        Talker.onVoices=() -> Applic.RunOnUiThread(fillVoices);
        page.onClose(() -> Talker.onVoices=null);
        }
    Slider speed=page.slider(voice,act.getString(R.string.speed),-2.5f,2.5f,0.05f,toSlider(Talker.curspeed),TalkerPage::ratio,null);
    Slider pitch=page.slider(voice,act.getString(R.string.pitch),-2.5f,2.5f,0.05f,toSlider(Talker.curpitch),TalkerPage::ratio,null);
    page.toggle(voice,R.drawable.ic_jm_music_note,act.getString(R.string.jm_talk_media),act.getString(R.string.jm_talk_media_summary),
        () -> Natives.getSoundType()==AudioAttributes.USAGE_MEDIA,on -> {
            Natives.setSoundType(on?AudioAttributes.USAGE_MEDIA:USAGE_NOTIFICATION);
            Talker.maketalk_audio();
            });

    /* Profiles: switching reloads the page with that profile's values. */
    LinearLayout prof=page.section(R.string.jm_talk_section_profile);
    final String profile=act.getString(R.string.profile);
    List<String> profiles=Arrays.asList(act.getString(R.string.defaultname),profile+"1",profile+"2",profile+"3",profile+"4",profile+"5");
    final int curProfile=Natives.getProfile();
    page.choice(prof,act.getString(R.string.profile),profiles,curProfile,i -> {
        if(i!=curProfile) {
            tk.glucodata.settings.Settings.setProfile(act,i);
            page.dismiss();
            show(act);
            }
        });
    page.link(prof,R.drawable.ic_jm_schedule,act.getString(R.string.schedules),null,() -> {
        if(!PhoneUi.show("settings.scheduleprofiles",act))
            tk.glucodata.settings.Settings.scheduleProfiles(act,page.root);
        });

    /* Values on screen into Talker's fields, as the old getvalues(). */
    Runnable getvalues=() -> {
        if(voicePos[0]>=0)
            Talker.voicepos=voicePos[0];
        String sep=M3Page.text(separation);
        try {
            Talker.cursep=Integer.parseInt(sep)*1000L;
            Talker.nexttime=System.currentTimeMillis()+Talker.cursep;
            }
        catch(NumberFormatException e) {
            Log.i(Talker.LOG_ID,"separation "+sep);
            }
        Talker.curspeed=fromSlider(speed.getValue());
        Talker.curpitch=fromSlider(pitch.getValue());
        };

    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.test),R.drawable.ic_jm_play_arrow,() -> {
            var gl=lastglucose();
            var say=(gl!=null&&gl.value!=null)?gl.value:"8.7";
            getvalues.run();
            if(Talker.istalking()) {
                var talk=SuperGattCallback.talker;
                if(talk!=null) {
                    talk.setvalues();
                    talk.setvoice();
                    talk.speak(say);
                    return;
                    }
                }
            Talker.playstring=say;
            SuperGattCallback.newtalker(act);
            }),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            String sep=M3Page.text(separation);
            if(!sep.matches("\\d+")) {
                M3Page.error(separation,act.getString(R.string.jm_invalid_number));
                return;
                }
            getvalues.run();
            if(active.isChecked()||touch.isChecked()||messages.isChecked()||alarms.isChecked()) {
                SuperGattCallback.newtalker(act);
                SuperGattCallback.dotalk=active.isChecked();
                settouchtalk(touch.isChecked());
                Natives.setspeakmessages(messages.isChecked());
                Natives.setspeakalarms(alarms.isChecked());
                }
            else {
                settouchtalk(false);
                Natives.setspeakmessages(false);
                Natives.setspeakalarms(false);
                SuperGattCallback.endtalk();
                }
            Natives.saveVoice(Talker.curspeed,Talker.curpitch,(int)(Talker.cursep/1000L),Talker.voicepos,SuperGattCallback.dotalk);
            page.dismiss();
            }));
    }
}
