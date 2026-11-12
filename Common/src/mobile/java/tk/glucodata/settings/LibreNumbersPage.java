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

package tk.glucodata.settings;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;

import tk.glucodata.Consumer;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;

/*
 * Which amounts are sent (LibreNumbers.mklayout) as a Material 3 page: the
 * on/off switch for sending amounts and, per amount label, what it is sent as.
 * night==0 LibreView, night==1 Nightscout.  The switch value is handed back
 * on close, like the old screen did through its check box.
 */
public final class LibreNumbersPage {
private LibreNumbersPage() {}

private static final int[] KIND_NAMES={0,R.string.rapidinsulin,R.string.longinsulin,R.string.carbo,R.string.comments,R.string.dontsend};
private static final int FOOD=3;

private static String format(float f) {
    if(f==Math.rint(f))
        return String.valueOf((int)f);
    return String.valueOf(f);
    }

static CharSequence kindText(Context act,int night,int pos) {
    int kind=Natives.getlibrenumkind(night,pos);
    if(kind<=0||kind>=KIND_NAMES.length)
        return act.getString(R.string.jm_share_kind_unset);
    if(kind==FOOD)
        return act.getString(R.string.jm_share_kind_food,act.getString(KIND_NAMES[kind]),format(Natives.getlibrefoodweight(night,pos)));
    return act.getString(KIND_NAMES[kind]);
    }

public static void show(MainActivity act,int night,CharSequence title,boolean checked,Consumer<Boolean> done) {
    final M3Page page=M3Page.open(act,act.getString(R.string.jm_share_labels_title));
    page.help(act.getString(night==1?R.string.nightnumhelp:R.string.librenumhelp));
    LinearLayout top=page.section((CharSequence)null);
    final boolean[] value={checked};
    MaterialSwitch sw=page.toggle(top,R.drawable.ic_jm_send,title,null,() -> value[0],on -> value[0]=on);
    View swRow=(View)sw.getParent().getParent();
    final android.widget.TextView remark=page.note(top,act.getString(R.string.libresetalllabels));
    Runnable enable=() -> {
        boolean can=Natives.canSendNumbers(night);
        swRow.setEnabled(can);
        sw.setEnabled(can);
        swRow.setAlpha(can?1f:.5f);
        remark.setVisibility(can?View.GONE:View.VISIBLE);
        };
    enable.run();

    LinearLayout sec=page.section(R.string.jm_share_labels_sec);
    ArrayList<String> labels=Natives.getLabels();
    final int count=labels==null?0:labels.size()-1;
    if(count<=0)
        page.empty(sec,R.drawable.ic_jm_label,act.getString(R.string.jm_share_labels_empty),null);
    for(int i=0;i<count;i++) {
        final int pos=i;
        final String name=labels.get(i);
        ViewGroup row=page.link(sec,R.drawable.ic_jm_label,name,kindText(act,night,pos),() -> {});
        row.setOnClickListener(v -> chooseKind(page,night,pos,name,() -> {
            M3Page.setSummary(row,kindText(act,night,pos));
            enable.run();
            }));
        }
    page.onClose(() -> done.accept(value[0]));
    }

/* The old librenumoptions dialog: radio buttons for the kind, a multiplier for food. */
private static void chooseKind(M3Page page,int night,int pos,String name,Runnable changed) {
    final Context ctx=page.ctx;
    LinearLayout box=new LinearLayout(ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(page.dp(24),page.dp(8),page.dp(24),0);
    RadioGroup group=new RadioGroup(ctx);
    final int[] ids=new int[KIND_NAMES.length];
    for(int k=1;k<KIND_NAMES.length;k++) {
        MaterialRadioButton r=new MaterialRadioButton(ctx);
        r.setText(KIND_NAMES[k]);
        ids[k]=View.generateViewId();
        r.setId(ids[k]);
        group.addView(r,new RadioGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        }
    box.addView(group);
    TextInputLayout layout=new TextInputLayout(new androidx.appcompat.view.ContextThemeWrapper(ctx,com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox),null,
        com.google.android.material.R.attr.textInputOutlinedStyle);
    layout.setHint(page.act.getString(R.string.jm_share_weight));
    TextInputEditText weight=new TextInputEditText(layout.getContext());
    weight.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    weight.setSingleLine(true);
    layout.addView(weight);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    lp.topMargin=page.dp(8);
    box.addView(layout,lp);

    final int was=Natives.getlibrenumkind(night,pos);
    if(was>0&&was<KIND_NAMES.length)
        group.check(ids[was]);
    weight.setText(was==FOOD?format(Natives.getlibrefoodweight(night,pos)):"1");
    layout.setVisibility(was==FOOD?View.VISIBLE:View.GONE);
    group.setOnCheckedChangeListener((g,id) -> layout.setVisibility(id==ids[FOOD]?View.VISIBLE:View.GONE));

    androidx.appcompat.app.AlertDialog dialog=M3Page.dialog(page.act)
        .setTitle(name)
        .setView(box)
        .setNeutralButton(R.string.helpname,null)
        .setNegativeButton(R.string.cancel,null)
        .setPositiveButton(R.string.save,null)
        .show();
    dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v ->
        tk.glucodata.help.help(page.act.getString(R.string.setlibrenumtype),page.act));
    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
        int kind=0;
        for(int k=1;k<KIND_NAMES.length;k++)
            if(group.getCheckedRadioButtonId()==ids[k])
                kind=k;
        if(kind==0) {
            dialog.dismiss();
            return;
            }
        float w=0.0f;
        if(kind==FOOD) {
            w=M3Page.number(weight);
            if(Float.isNaN(w)) {
                layout.setError(page.act.getString(R.string.wrongformat));
                return;
                }
            }
        if(kind!=was||was==FOOD)
            Natives.setlibrenum(night,pos,kind,w);
        dialog.dismiss();
        changed.run();
        });
    }
}
