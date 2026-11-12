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

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.ArrayList;

import tk.glucodata.DynamicThemeUtils;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/* Phone Theme (SelectTheme): pick a light or dark app theme and the button
 * shape, with a live preview; Apply stores it like SelectTheme
 * (setradius, setisOval, setTheme) and recreates the activity.  The theme
 * colours the dashboard and the old screens; the Material pages follow
 * Juggluco's Dark mode switch. */
public final class ThemePage {
private final MainActivity act;
private final M3Page page;
private AppTheme theme;
private int radius;
private boolean oval;
private boolean dark;
private MaterialAutoCompleteTextView choice;
private MaterialButtonToggleGroup shape;
private Slider radiusSlider;
private MaterialCardView preview;
private TextView previewTitle,previewText;
private Button previewButton;

public static void show(MainActivity act) {
    new ThemePage(act);
    }

private ThemePage(MainActivity act) {
    this.act=act;
    radius=Natives.getradius();
    oval=Natives.getisOval();
    int idx=Natives.getTheme();
    theme=(idx>=0&&idx<AppTheme.values().length)?AppTheme.values()[idx]:AppTheme.DEFAULT;
    dark=isDark(theme);
    page=M3Page.open(act,R.string.theme);

    LinearLayout themes=page.section(R.string.jm_data_themes);
    page.segmented(themes,null,new CharSequence[]{act.getString(R.string.light),act.getString(R.string.dark)},dark?1:0,i -> {
        dark=i==1;
        fillChoice();
        AppTheme[] group=AppTheme.getThemesByGroup(dark);
        if(group.length>0&&isDark(theme)!=dark)
            select(group[0]);
        });
    choice=page.choice(themes,act.getString(R.string.theme),new ArrayList<String>(),0,i -> {
        AppTheme[] group=AppTheme.getThemesByGroup(dark);
        if(i>=0&&i<group.length)
            select(group[i]);
        });
    fillChoice();

    LinearLayout look=page.section(R.string.buttonshape);
    shape=page.segmented(look,null,new CharSequence[]{act.getString(R.string.rectangle),act.getString(R.string.oval)},oval?1:0,i -> {
        oval=i==1;
        update();
        });
    radiusSlider=page.slider(look,act.getString(R.string.radius).replace(":","").trim(),0f,25f,1f,Math.max(0,Math.min(25,radius)),v -> Math.round(v)+" dp",v -> {
        radius=Math.round(v);
        update();
        });

    LinearLayout prev=page.section(R.string.jm_data_preview);
    preview=new MaterialCardView(page.ctx);
    preview.setRadius(page.dp(16));
    preview.setStrokeWidth(page.dp(1));
    preview.setStrokeColor(page.color(com.google.android.material.R.attr.colorOutlineVariant));
    LinearLayout inner=new LinearLayout(page.ctx);
    inner.setOrientation(LinearLayout.VERTICAL);
    inner.setGravity(Gravity.CENTER_HORIZONTAL);
    final int p=page.dp(20);
    inner.setPadding(p,p,p,p);
    previewTitle=new TextView(page.ctx);
    previewTitle.setTextSize(20);
    previewText=new TextView(page.ctx);
    previewText.setPadding(0,page.dp(4),0,page.dp(12));
    previewText.setText(R.string.jm_data_previewtext);
    inner.addView(previewTitle);
    inner.addView(previewText);
    preview.addView(inner,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    page.custom(prev,preview);

    page.bottomBar(page.button(M3Page.Style.TEXT,act.getString(R.string.cancel),0,page::close),
                   page.button(M3Page.Style.FILLED,act.getString(R.string.jm_data_apply),0,this::apply));
    update();
    }

private static boolean isDark(AppTheme t) {
    for(AppTheme d:AppTheme.getThemesByGroup(true))
        if(d==t)
            return true;
    return false;
    }

private void fillChoice() {
    AppTheme[] group=AppTheme.getThemesByGroup(dark);
    ArrayList<String> names=new ArrayList<>();
    for(AppTheme t:group)
        names.add(t.toString());
    choice.setAdapter(new android.widget.ArrayAdapter<String>(choice.getContext(),com.google.android.material.R.layout.m3_auto_complete_simple_item,names) {
        @Override
        public android.widget.Filter getFilter() { // show every theme, never filter on the current text
            return new android.widget.Filter() {
                @Override protected FilterResults performFiltering(CharSequence c) { FilterResults r=new FilterResults(); r.values=names; r.count=names.size(); return r; }
                @Override protected void publishResults(CharSequence c,FilterResults r) { notifyDataSetChanged(); }
                };
            }
        });
    int pos=0;
    for(int i=0;i<group.length;i++)
        if(group[i]==theme)
            pos=i;
    if(group.length>0)
        choice.setText(names.get(pos),false);
    }

private void select(AppTheme t) {
    if(t==null)
        return;
    if(theme!=t) {
        theme=t;
        radius=SelectTheme.getDimensionFromTheme(act,theme,R.attr.buttonCornerRadius);
        oval=SelectTheme.getDrawableResourceFromTheme(act,theme,R.attr.themedButtonBackground)==R.drawable.button_oval;
        }
    choice.setText(t.toString(),false);
    page.updating=true;
    shape.check(shape.getChildAt(oval?1:0).getId());
    radiusSlider.setValue(Math.max(0,Math.min(25,radius)));
    page.updating=false;
    update();
    }

private void update() {
    final boolean editable=theme!=AppTheme.DEFAULT;
    for(int i=0;i<shape.getChildCount();i++)
        shape.getChildAt(i).setEnabled(editable);
    radiusSlider.setEnabled(editable&&!oval);
    Context tc=new ContextThemeWrapper(act,theme.getStyleResId());
    final int bg=DynamicThemeUtils.resolveAttributeColor(tc,android.R.attr.windowBackground,0xffffffff);
    final int fg=DynamicThemeUtils.resolveAttributeColor(tc,android.R.attr.textColorPrimary,0xff000000);
    preview.setCardBackgroundColor(bg);
    previewTitle.setText(theme.toString());
    previewTitle.setTextColor(fg);
    previewText.setTextColor(fg);
    LinearLayout inner=(LinearLayout)preview.getChildAt(0);
    if(previewButton!=null)
        inner.removeView(previewButton);
    previewButton=new Button(tc);
    previewButton.setText(R.string.ok);
    previewButton.setAllCaps(false);
    if(editable) {
        previewButton.setBackground(DynamicThemeUtils.createDynamicButton(tc,radius,oval));
        previewButton.setTextColor(DynamicThemeUtils.buttonTextColors(tc));
        }
    inner.addView(previewButton,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    }

private void apply() {
    Natives.setradius(radius);
    Natives.setisOval(oval);
    int globalPosition=0;
    for(int i=0;i<AppTheme.values().length;i++)
        if(AppTheme.values()[i]==theme) {
            globalPosition=i;
            break;
            }
    Natives.setTheme(globalPosition);
    page.dismiss();
    act.recreate();
    }
}
