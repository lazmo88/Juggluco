/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+ and           */
/*      Sibionics GS1Sb sensors.                                                     */
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

import android.text.InputType;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;

import tk.glucodata.Applic;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;

import static tk.glucodata.settings.Settings.float2string;
import static tk.glucodata.settings.Settings.str2float;

/* Phone Number labels (LabelsClass.mklabellayout): labels as list rows (tap
 * to edit name, weight and, with a Garmin watch, rounding), the meal label,
 * New and Delete last.  Same natives: setlabel, setnrlabel, setmealvar;
 * labels are sent to mirrors when the page closes, as before. */
final class LabelsPage {
private static final int MAXLABELS=40;
private final MainActivity act;
private final M3Page page;
private final boolean garmin=Natives.gethasgarmin();
private final boolean readonly=Natives.staticnum();
private LinearLayout list;
private com.google.android.material.textfield.MaterialAutoCompleteTextView meal;
private MaterialButton add,deleteLast;

static void show(MainActivity act) {
    new LabelsPage(act);
    }

private LabelsPage(MainActivity act) {
    this.act=act;
    page=M3Page.open(act,R.string.numberlabels);
    page.help(R.string.labelhelp);
    list=page.section(R.string.jm_data_labels);
    LinearLayout mealsec=page.section(R.string.jm_data_meal);
    meal=page.choice(mealsec,act.getString(R.string.jm_data_meallabel),mealItems(),Natives.getmealvar(),i -> Natives.setmealvar((byte)i));
    page.note(mealsec,act.getString(R.string.jm_data_mealnote));
    if(readonly)
        page.note(list,act.getString(R.string.jm_data_labelsreadonly));
    else {
        add=page.button(M3Page.Style.FILLED,act.getString(R.string.newname),R.drawable.ic_jm_add,() -> edit(-1));
        deleteLast=page.button(M3Page.Style.TONAL,act.getString(R.string.deletelast),R.drawable.ic_jm_delete,this::askDeleteLast);
        page.bottomBar(deleteLast,add);
        }
    fill();
    page.onClose(() -> {
        if(Natives.shouldsendlabels()) {
            Applic.wakemirrors();
            ((Applic)act.getApplication()).sendlabels();
            }
        });
    }

/* Natives.getLabels() ends with "": the "no meal label" choice. */
private ArrayList<String> mealItems() {
    ArrayList<String> all=Natives.getLabels();
    ArrayList<String> out=new ArrayList<>();
    for(int i=0;i<all.size();i++)
        out.add(i==all.size()-1&&all.get(i).isEmpty()?act.getString(R.string.jm_data_none):all.get(i));
    return out;
    }

private void fill() {
    list.removeAllViews();
    ArrayList<String> labels=Natives.getLabels();
    final int count=labels.size()-1;
    if(count<=0)
        page.empty(list,R.drawable.ic_jm_label,act.getString(R.string.jm_data_nolabels),null);
    final int mealvar=Natives.getmealvar();
    for(int i=0;i<count;i++) {
        final int pos=i;
        float weight=Natives.getweight(pos);
        String summary=weight>0.0f?act.getString(R.string.weight).replace(":","").trim()+" "+float2string(weight):null;
        page.link(list,pos==mealvar?R.drawable.ic_jm_restaurant:R.drawable.ic_jm_label,labels.get(pos),summary,() -> edit(pos));
        }
    if(add!=null) {
        add.setVisibility(count>=MAXLABELS?View.GONE:View.VISIBLE);
        deleteLast.setVisibility(count<1?View.GONE:View.VISIBLE);
        }
    ArrayList<String> items=mealItems();
    meal.setAdapter(new android.widget.ArrayAdapter<String>(meal.getContext(),com.google.android.material.R.layout.m3_auto_complete_simple_item,items) {
        @Override
        public android.widget.Filter getFilter() {
            return new android.widget.Filter() {
                @Override protected FilterResults performFiltering(CharSequence c) { FilterResults r=new FilterResults(); r.values=items; r.count=items.size(); return r; }
                @Override protected void publishResults(CharSequence c,FilterResults r) { notifyDataSetChanged(); }
                };
            }
        });
    if(mealvar>=0&&mealvar<items.size())
        meal.setText(items.get(mealvar),false);
    }

private TextInputEditText field(LinearLayout box,int hint,String value,int type) {
    TextInputLayout layout=new TextInputLayout(page.ctx,null,com.google.android.material.R.attr.textInputOutlinedStyle);
    layout.setHint(act.getString(hint).replace(":","").trim());
    TextInputEditText edit=new TextInputEditText(layout.getContext());
    edit.setInputType(type);
    edit.setSingleLine(true);
    edit.setText(value);
    layout.addView(edit,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin=page.dp(8);
    box.addView(layout,lp);
    return edit;
    }

/* LabelsClass.mkchangelabel: pos<0 is a new label. */
private void edit(int pos) {
    ArrayList<String> labels=Natives.getLabels();
    LinearLayout box=new LinearLayout(page.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(page.dp(24),page.dp(4),page.dp(24),0);
    TextInputEditText name=field(box,R.string.numlabel,pos>=0?labels.get(pos):"",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    TextInputEditText prec=garmin?field(box,R.string.roundto,pos>=0?float2string(Natives.getprec(pos)):"",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL):null;
    TextInputEditText weight=field(box,R.string.weight,pos>=0?float2string(Natives.getweight(pos)):"",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    var builder=M3Page.dialog(act)
        .setTitle(pos>=0?act.getString(R.string.jm_data_editlabel):act.getString(R.string.newname))
        .setView(box)
        .setNeutralButton(R.string.helpname,null)
        .setNegativeButton(R.string.cancel,null);
    if(!readonly)
        builder.setPositiveButton(R.string.save,null);
    AlertDialog dialog=builder.create();
    dialog.setOnShowListener(d -> {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> tk.glucodata.help.help(R.string.newlabelhelp,act));
        if(readonly)
            return;
        name.requestFocus();
        tk.glucodata.help.showkeyboard(act,name);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            float pr=garmin?str2float(M3Page.text(prec)):0;
            float wei=str2float(M3Page.text(weight));
            String nam=name.getText()==null?"":name.getText().toString();
            int at=pos>=0?pos:Natives.getLabels().size()-1;
            if(wei>0.0)
                Toast.makeText(act,String.format(Applic.usedlocale,act.getString(R.string.usedweight),wei),Toast.LENGTH_LONG).show();
            if(!Natives.setlabel(at,nam,pr,wei)) {
                M3Page.error(name,nam+act.getString(R.string.toolarg));
                return;
                }
            dialog.dismiss();
            fill();
            });
        });
    dialog.setOnDismissListener(d -> tk.glucodata.help.hidekeyboard(act));
    dialog.show();
    }

private void askDeleteLast() {
    ArrayList<String> labels=Natives.getLabels();
    final int nr=labels.size()-2;
    if(nr<0)
        return;
    M3Page.confirm(act,act.getString(R.string.deletelabel),labels.get(nr),act.getString(R.string.delete),() -> {
        Natives.setnrlabel(nr);
        fill();
        page.toast(act.getString(R.string.jm_data_deleted));
        });
    }
}
