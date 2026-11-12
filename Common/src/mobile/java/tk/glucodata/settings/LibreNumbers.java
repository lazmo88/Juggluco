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
/*      Fri Jan 27 15:32:11 CET 2023                                                 */


package tk.glucodata.settings;

import static tk.glucodata.Natives.canSendNumbers;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.function.Consumer;

import tk.glucodata.CheckDirectionBox;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;

/*
 * What LibreView (night==0) or Nightscout (night==1) receives for each label
 * of the amounts list, as a Material 3 page.  One row per label; tapping it
 * asks for the kind (rapid/long acting insulin, carbohydrate with a factor,
 * comment, don't send).  Uses Natives.getlibrenumkind/setlibrenum like the
 * old screen (LibreNumbers + LibreNumberHolder + librenumoptions.xml).
 *
 * The send switch is only copied back to the caller when the page closes,
 * as before; the caller stores it.
 */
public class LibreNumbers {
private final static String LOG_ID="LibreNumbers";

/* kind 1..5 as stored by setlibrenum; 0 is "not set". */
private static final int[] KIND_NAMES={0,R.string.rapidinsulin,R.string.longinsulin,R.string.carbo,R.string.comments,R.string.dontsend};
private static final int[] KIND_ICONS={R.drawable.ic_jm_label,R.drawable.ic_jm_vaccines,R.drawable.ic_jm_vaccines,R.drawable.ic_jm_restaurant,R.drawable.ic_jm_notes,R.drawable.ic_jm_block};
private static final int FOOD=3;

/* Old entry point (Libreview and Nightscout screens). */
public static void mklayout(MainActivity context,int night,CheckDirectionBox donum,int[] donothing,View parent) {
    show(context,night,donum.getText(),donum.isChecked(),checked -> {
        donothing[0]=2;
        donum.setChecked(checked);
        donothing[0]=0;
        });
    }

private static String kindText(Context ctx,int night,int pos) {
    int kind=Natives.getlibrenumkind(night,pos);
    if(kind<=0||kind>=KIND_NAMES.length)
        return ctx.getString(R.string.jm_sensors_kind_unset);
    String name=ctx.getString(KIND_NAMES[kind]);
    if(kind==FOOD)
        name=ctx.getString(R.string.jm_sensors_kind_factor,name,Settings.float2string(Natives.getlibrefoodweight(night,pos)));
    return name;
    }

/* New entry point for Material 3 callers: onClose gets the send switch. */
public static void show(MainActivity act,int night,CharSequence sendLabel,boolean sendChecked,Consumer<Boolean> onClose) {
    final M3Page page=M3Page.open(act,R.string.jm_sensors_numbers_title);
    page.subtitle(night==1?"Nightscout":"LibreView");
    page.help(act.getString(night==1?R.string.nightnumhelp:R.string.librenumhelp));

    LinearLayout send=page.section((CharSequence)null);
    final boolean[] checked={sendChecked};
    MaterialSwitch sendSwitch=page.toggle(send,R.drawable.ic_jm_cloud_download,sendLabel,null,() -> checked[0],on -> checked[0]=on);
    android.widget.TextView remark=page.note(send,act.getString(R.string.libresetalllabels));
    Runnable canSend=() -> {
        boolean can=canSendNumbers(night);
        View row=sendSwitch;
        while(row.getParent()!=send&&row.getParent() instanceof View)
            row=(View)row.getParent();
        row.setEnabled(can);
        sendSwitch.setEnabled(can);
        row.setAlpha(can?1f:0.38f);
        remark.setVisibility(can?View.GONE:View.VISIBLE);
        };
    canSend.run();
    page.refresher(canSend);

    LinearLayout list=page.section(R.string.jm_sensors_section_labels);
    Runnable fill=() -> {
        list.removeAllViews();
        ArrayList<String> labels=Natives.getLabels();
        for(int pos=0;pos<labels.size()-1;pos++) {
            final int p=pos;
            final String label=labels.get(pos);
            int kind=Natives.getlibrenumkind(night,pos);
            page.link(list,KIND_ICONS[kind>=0&&kind<KIND_ICONS.length?kind:0],label,kindText(act,night,pos),() -> askKind(page,night,p,label));
            }
        };
    fill.run();
    page.refresher(fill);
    page.onClose(() -> onClose.accept(checked[0]));
    }

/* The old librenumoptions dialog as a MaterialAlertDialog. */
private static void askKind(M3Page page,int night,int pos,String label) {
    final MainActivity act=page.act;
    final Context ctx=page.ctx;
    final int was=Natives.getlibrenumkind(night,pos);
    LinearLayout box=new LinearLayout(ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(page.dp(24),page.dp(8),page.dp(24),0);
    RadioGroup group=new RadioGroup(ctx);
    final int[] ids=new int[KIND_NAMES.length];
    for(int k=1;k<KIND_NAMES.length;k++) {
        MaterialRadioButton b=new MaterialRadioButton(ctx);
        ids[k]=View.generateViewId();
        b.setId(ids[k]);
        b.setText(KIND_NAMES[k]);
        b.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        b.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
        b.setMinHeight(page.dp(48));
        group.addView(b);
        }
    box.addView(group);
    TextInputLayout weightBox=new TextInputLayout(new androidx.appcompat.view.ContextThemeWrapper(ctx,com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox),null,com.google.android.material.R.attr.textInputOutlinedStyle);
    weightBox.setHint(CalibrationPage.label(act,R.string.multiply));
    TextInputEditText weight=new TextInputEditText(weightBox.getContext());
    weight.setInputType(M3Page.DECIMAL);
    weight.setSingleLine(true);
    weightBox.addView(weight);
    LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    wp.topMargin=page.dp(8);
    box.addView(weightBox,wp);
    weight.setText(Settings.float2string(was==FOOD?Natives.getlibrefoodweight(night,pos):1.0f));
    if(was>0&&was<KIND_NAMES.length)
        group.check(ids[was]);
    weightBox.setVisibility(was==FOOD?View.VISIBLE:View.GONE);
    group.setOnCheckedChangeListener((g,id) -> weightBox.setVisibility(id==ids[FOOD]?View.VISIBLE:View.GONE));
    AlertDialog dialog=M3Page.dialog(act).setTitle(label).setView(box)
        .setPositiveButton(R.string.save,null).setNegativeButton(R.string.cancel,null).create();
    dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        int kind=0;
        for(int k=1;k<ids.length;k++)
            if(group.getCheckedRadioButtonId()==ids[k])
                kind=k;
        if(kind==0) {
            dialog.dismiss();
            return;
            }
        float weightf=0.0f;
        if(kind==FOOD) {
            weightf=M3Page.number(weight);
            if(Float.isNaN(weightf)) {
                weightBox.setError(act.getString(R.string.jm_invalid_number));
                return;
                }
            }
        if(kind!=was||was==FOOD)
            Natives.setlibrenum(night,pos,kind,weightf);
        dialog.dismiss();
        page.refresh();
        }));
    dialog.show();
    }
}
