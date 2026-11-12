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
// SPDX-License-Identifier: GPL-3.0-or-later

package tk.glucodata;

import android.app.DatePickerDialog;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import tk.glucodata.m3.M3Page;
import tk.glucodata.settings.ExchangePage;

/*
 * Dexcom Clarity (Clarity.show, ClarityAmounts.show) as Material 3 pages.
 * Same Save semantics as before: the switches and the start date are stored
 * together by Natives.clarityConfigure; Back/Cancel discard.  Sign in, Send
 * now and Categories act at once.
 */
public final class ClarityPage {
private ClarityPage() {}
private static final String LOG_ID="ClarityPage";

public static void show(MainActivity act) {
    final ClarityStatus state;
    try {
        state=Natives.clarityStatus();
        if(state==null) throw new IllegalStateException();
        }
    catch(Exception ex) {
        Clarity.logFailure("Read settings",ex);
        M3Page.message(act,"Dexcom Clarity",act.getString(R.string.clarity_read_settings_error));
        return;
        }
    if(!state.available) {
        Log.e(LOG_ID,"Native Clarity support unavailable");
        M3Page.message(act,"Dexcom Clarity",state.status);
        return;
        }
    final M3Page page=M3Page.open(act,"Dexcom Clarity");
    page.help(R.string.clarityhelp);
    page.onClose(() -> ExchangePage.refreshTop(act));
    final boolean[] enabled={state.enabled};
    final boolean[] history={state.libre3History};
    final boolean[] numbers={state.numbers};
    final long[] since={state.since};
    final SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);

    /* Account */
    LinearLayout account=page.section(R.string.jm_share_lv_sec_account);
    final View accountRow=page.link(account,R.drawable.ic_jm_account_circle,act.getString(R.string.clarity_sign_in),state.account,
        () -> ClarityLogin.show(act,page.root,page::refresh));

    /* Upload */
    LinearLayout upload=page.section(R.string.jm_share_lv_sec_upload);
    page.toggle(upload,R.drawable.ic_jm_cloud_upload,act.getString(R.string.clarity_send_enabled),null,() -> enabled[0],on -> enabled[0]=on);
    page.toggle(upload,R.drawable.ic_jm_history,act.getString(R.string.clarity_use_history),null,() -> history[0],on -> history[0]=on);
    final com.google.android.material.materialswitch.MaterialSwitch num=page.toggle(upload,R.drawable.ic_jm_medication,act.getString(R.string.sendamounts),null,
        () -> numbers[0],on -> {
            numbers[0]=on;
            if(on&&!ClarityAmounts.ready()) {
                numbers[0]=false;
                page.refresh();
                categories(act,() -> { numbers[0]=true; page.refresh(); });
                }
            });
    page.link(upload,R.drawable.ic_jm_label,act.getString(R.string.clarity_categories),null,() -> categories(act,() -> {}));
    final View dateRow=page.link(upload,R.drawable.ic_jm_calendar_today,act.getString(R.string.changestartbutton),
        act.getString(R.string.clarity_start_date,format.format(new Date(since[0]*1000L))),() -> {});
    dateRow.setOnClickListener(v -> {
        Calendar selected=Calendar.getInstance();
        selected.setTimeInMillis(since[0]*1000L);
        DatePickerDialog picker=new DatePickerDialog(M3Page.themed(act),(view,year,month,day) -> {
            Calendar chosen=Calendar.getInstance();
            chosen.clear();
            chosen.set(year,month,day);
            final long candidate=chosen.getTimeInMillis()/1000L;
            if(candidate==since[0]) return;
            M3Page.confirm(act,act.getString(R.string.clarity_change_start_title),
                act.getString(R.string.clarity_change_start_message,format.format(chosen.getTime())),act.getString(android.R.string.ok),() -> {
                    since[0]=candidate;
                    M3Page.setSummary(dateRow,act.getString(R.string.clarity_start_date,format.format(chosen.getTime())));
                    });
            },selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(1598911200000L);
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
        });
    page.item(upload,R.drawable.ic_jm_send,act.getString(R.string.sendnow),null,() -> {
        Natives.clarityWake();
        page.refresh();
        });

    /* Status */
    LinearLayout status=page.section(R.string.jm_share_lv_sec_status);
    final TextView statusView=page.note(status,Clarity.statusText(act,state));
    statusView.setTextIsSelectable(true);
    page.refresher(() -> {
        try {
            ClarityStatus s=Natives.clarityStatus();
            if(s==null) throw new IllegalStateException();
            M3Page.setSummary(accountRow,s.account);
            statusView.setText(Clarity.statusText(act,s));
            }
        catch(Exception ex) { Clarity.logFailure("Refresh settings",ex); }
        });

    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            try {
                if(numbers[0]&&!ClarityAmounts.ready()) {
                    numbers[0]=false;
                    page.refresh();
                    categories(act,() -> { numbers[0]=true; page.refresh(); });
                    return;
                    }
                String error=Natives.clarityConfigure(enabled[0],history[0],numbers[0],since[0]);
                if(error.isEmpty())
                    page.dismiss();
                else
                    M3Page.message(act,"Dexcom Clarity",error);
                }
            catch(Exception ex) {
                Clarity.logFailure("Save settings",ex);
                M3Page.message(act,"Dexcom Clarity",act.getString(R.string.clarity_save_settings_error));
                }
            }));
    }

private static final int[] names={R.string.clarity_category_choose,R.string.clarity_category_fast,
    R.string.clarity_category_long,R.string.clarity_category_carbs,R.string.clarity_category_comment,
    R.string.clarity_category_not,R.string.clarity_category_blood};

/* ClarityAmounts.show: one row per amount label, Save stores all. */
static void categories(MainActivity act,Runnable saved) {
    final int[] kinds=Natives.clarityCategories();
    final ArrayList<String> labels=Natives.getLabels();
    if(kinds==null||labels==null||labels.size()!=kinds.length+1) {
        Log.e(LOG_ID,"Cannot read amount labels/categories");
        M3Page.message(act,act.getString(R.string.clarity_categories),act.getString(R.string.clarity_read_labels_error));
        return;
        }
    final M3Page page=M3Page.open(act,act.getString(R.string.clarity_categories));
    final float[] weights=new float[kinds.length];
    LinearLayout sec=page.section(R.string.jm_share_labels_sec);
    if(kinds.length==0)
        page.empty(sec,R.drawable.ic_jm_label,act.getString(R.string.jm_share_labels_empty),null);
    final View[] rows=new View[kinds.length];
    final MaterialButton[] save={null};
    final Runnable redraw=() -> {
        boolean complete=kinds.length>0;
        for(int i=0;i<kinds.length;++i) {
            String text=act.getString(names[kinds[i]]);
            if(kinds[i]==3) text=act.getString(R.string.jm_share_kind_food,text,Float.toString(weights[i]));
            M3Page.setSummary(rows[i],text);
            if(kinds[i]==0) complete=false;
            }
        if(save[0]!=null) save[0].setEnabled(complete);
        };
    for(int i=0;i<kinds.length;++i) {
        final int pos=i;
        weights[i]=Natives.getlibrefoodweight(0,i);
        if(kinds[i]==3&&(Float.isNaN(weights[i])||Float.isInfinite(weights[i])||weights[i]<=0))
            kinds[i]=0;
        rows[i]=page.link(sec,R.drawable.ic_jm_label,labels.get(i),null,() -> {
            String[] choices=new String[names.length-1];
            for(int j=0;j<choices.length;++j) choices[j]=act.getString(names[j+1]);
            M3Page.dialog(act).setTitle(labels.get(pos))
                .setSingleChoiceItems(choices,kinds[pos]-1,(dialog,which) -> {
                    int chosen=which+1;
                    if(chosen==6) {
                        for(int j=0;j<kinds.length;++j)
                            if(j!=pos&&kinds[j]==6) {
                                page.toast(act.getString(R.string.clarity_change_blood_first,labels.get(j)));
                                return;
                                }
                        }
                    dialog.dismiss();
                    if(chosen==3)
                        askWeight(page,labels.get(pos),weights[pos],factor -> {
                            weights[pos]=factor;
                            kinds[pos]=3;
                            redraw.run();
                            });
                    else {
                        kinds[pos]=chosen;
                        redraw.run();
                        }
                    })
                .setNegativeButton(R.string.cancel,null).show();
            });
        }
    save[0]=page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
        if(!labels.equals(Natives.getLabels())) {
            Log.e(LOG_ID,"Labels changed while Categories was open");
            page.toast(act.getString(R.string.clarity_labels_changed));
            return;
            }
        String error=Natives.claritySetCategories(kinds,weights);
        if(error==null||!error.isEmpty()) {
            M3Page.message(act,act.getString(R.string.clarity_categories),error==null?act.getString(R.string.clarity_save_categories_error):error);
            return;
            }
        page.dismiss();
        saved.run();
        });
    page.bottomBar(page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),save[0]);
    redraw.run();
    }

private interface FloatDone { void done(float f); }

private static void askWeight(M3Page page,String label,float initial,FloatDone done) {
    final MainActivity act=page.act;
    FrameLayout box=new FrameLayout(page.ctx);
    box.setPadding(page.dp(24),page.dp(8),page.dp(24),0);
    TextInputLayout layout=new TextInputLayout(new androidx.appcompat.view.ContextThemeWrapper(page.ctx,com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox),null,
        com.google.android.material.R.attr.textInputOutlinedStyle);
    layout.setHint(act.getString(R.string.jm_share_weight));
    TextInputEditText weight=new TextInputEditText(layout.getContext());
    weight.setSingleLine(true);
    weight.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    weight.setText(initial>0&&!Float.isInfinite(initial)?Float.toString(initial):"1");
    layout.addView(weight);
    box.addView(layout,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    androidx.appcompat.app.AlertDialog editor=M3Page.dialog(act).setTitle(act.getString(R.string.clarity_grams_per_unit,label))
        .setView(box).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.save,null).show();
    editor.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(w -> {
        float factor=M3Page.number(weight);
        if(Float.isNaN(factor)||Float.isInfinite(factor)||factor<=0) {
            layout.setError(act.getString(R.string.clarity_positive_number));
            return;
            }
        done.done(factor);
        editor.dismiss();
        });
    }
}
