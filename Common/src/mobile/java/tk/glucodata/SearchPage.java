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

package tk.glucodata;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Locale;

import tk.glucodata.m3.DataPickers;
import tk.glucodata.m3.M3Page;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/* Phone search (menu Search): the old floating search box as an M3 page.
 * Same criteria and the same Natives.search call as GlucoseCurve.search();
 * the result is shown on the graph with an M3 Earlier/Close/Later bar
 * instead of the three white ImageButtons. */
final class SearchPage {
private static final String LOG_ID="SearchPage";

/* Kept between searches like GlucoseCurve's saved search state. */
private static boolean valid=false;
private static String under="0",above="999",ingredient="",quantity="";
private static boolean scans=false,history=false,stream=true,streamcal=false,historycal=false;
private static int label=-1; // -1: glucose
private static final int[] minutes={-1,-1};

private final MainActivity act;
private final M3Page page;
private final ArrayList<String> labels;
private TextInputEditText underEdit,aboveEdit,ingredientEdit,quantityEdit;
private MaterialAutoCompleteTextView labelChoice;
private final MaterialSwitch[] sources=new MaterialSwitch[5];
private LinearLayout glucoseSec,amountSec;
private View mealSec;
private View fromRow,toRow;
private com.google.android.material.button.MaterialButtonToggleGroup kind;

static void show(MainActivity act) {
    new SearchPage(act);
    }

private SearchPage(MainActivity act) {
    this.act=act;
    labels=AmountPage.labelNames();
    if(!valid)
        reset();
    page=M3Page.open(act,R.string.search);
    page.help(R.string.searchhelp);
    page.action(R.drawable.ic_jm_restart_alt,act.getString(R.string.resetname),() -> {
        page.dismiss(); // saves the fields first
        reset();
        show(act);
        });

    LinearLayout top=page.section((CharSequence)null);
    kind=page.segmented(top,null,new CharSequence[]{act.getString(R.string.glucose),act.getString(R.string.amountsname)},label<0?0:1,i -> {
        if(i==0) {
            label=-1;
            if(!anySource())
                sources[2].setChecked(true);
            }
        else {
            label=labelChoiceIndex();
            for(MaterialSwitch s:sources)
                s.setChecked(false);
            }
        showKind();
        });

    glucoseSec=page.section(R.string.jm_data_sources);
    final String historystr=act.getString(R.string.historyname);
    final String streamstr=act.getString(R.string.streamname);
    final String calibrated=act.getString(R.string.calibrated);
    sources[0]=page.toggle(glucoseSec,0,streamstr,null,() -> stream,on -> stream=on);
    sources[1]=page.toggle(glucoseSec,0,historystr,null,() -> history,on -> history=on);
    sources[2]=page.toggle(glucoseSec,0,act.getString(R.string.scansname),null,() -> scans,on -> scans=on);
    sources[3]=page.toggle(glucoseSec,0,streamstr+" "+calibrated.toLowerCase(Locale.getDefault()),null,() -> streamcal,on -> streamcal=on);
    sources[4]=page.toggle(glucoseSec,0,historystr+" "+calibrated.toLowerCase(Locale.getDefault()),null,() -> historycal,on -> historycal=on);
    { MaterialSwitch tmp=sources[2]; sources[2]=sources[0]; sources[0]=tmp; } // index: 0 scans, 1 history, 2 stream, 3/4 calibrated

    amountSec=page.section(R.string.jm_data_label);
    labelChoice=page.choice(amountSec,act.getString(R.string.jm_data_label),labels,label>=0?label:0,i -> {
        label=i;
        showMeal();
        });
    mealSec=page.custom(amountSec,new LinearLayout(page.ctx));
    ((LinearLayout)mealSec).setOrientation(LinearLayout.VERTICAL);
    ingredientEdit=page.field((LinearLayout)mealSec,act.getString(R.string.ingredient).replace(":","").trim(),ingredient,M3Page.TEXT,null,null);
    quantityEdit=page.field((LinearLayout)mealSec,act.getString(R.string.minimum),quantity,M3Page.DECIMAL,null,null);

    LinearLayout range=page.section(R.string.jm_data_range);
    LinearLayout pair=new LinearLayout(page.ctx);
    pair.setOrientation(LinearLayout.HORIZONTAL);
    page.custom(range,pair);
    LinearLayout left=new LinearLayout(page.ctx),right=new LinearLayout(page.ctx);
    left.setOrientation(LinearLayout.VERTICAL);
    right.setOrientation(LinearLayout.VERTICAL);
    pair.addView(left,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
    pair.addView(right,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
    underEdit=page.field(left,act.getString(R.string.jm_data_from),under,M3Page.DECIMAL,null,null);
    aboveEdit=page.field(right,act.getString(R.string.jm_data_to),above,M3Page.DECIMAL,null,null);
    ((ViewGroup.MarginLayoutParams)((View)underEdit.getParent().getParent()).getLayoutParams()).setMarginEnd(page.dp(4));
    ((ViewGroup.MarginLayoutParams)((View)aboveEdit.getParent().getParent()).getLayoutParams()).setMarginStart(page.dp(4));

    LinearLayout times=page.section(R.string.jm_data_timeofday);
    fromRow=page.link(times,R.drawable.ic_jm_schedule,act.getString(R.string.jm_data_from),null,() -> pickTime(0));
    toRow=page.link(times,R.drawable.ic_jm_schedule,act.getString(R.string.jm_data_to),null,() -> pickTime(1));
    showTimes();

    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.jm_data_earlier),R.drawable.ic_jm_chevron_left,() -> search(false)),
        page.button(M3Page.Style.FILLED,act.getString(R.string.jm_data_later),R.drawable.ic_jm_chevron_right,() -> search(true)));
    page.onClose(this::save);
    showKind();
    }

private boolean anySource() {
    for(MaterialSwitch s:sources)
        if(s.isChecked())
            return true;
    return false;
    }
private int labelChoiceIndex() {
    String now=labelChoice.getText()==null?"":labelChoice.getText().toString();
    int i=labels.indexOf(now);
    return i<0?0:i;
    }
private void showKind() {
    final boolean glucose=label<0;
    M3Page.sectionView(glucoseSec).setVisibility(glucose?View.VISIBLE:View.GONE);
    M3Page.sectionView(amountSec).setVisibility(glucose?View.GONE:View.VISIBLE);
    showMeal();
    }
private void showMeal() {
    mealSec.setVisibility(label>=0&&label==Natives.getmealvar()?View.VISIBLE:View.GONE);
    }

private static long todayAt(int minute) {
    java.util.Calendar c=java.util.Calendar.getInstance();
    c.set(java.util.Calendar.HOUR_OF_DAY,minute/60);
    c.set(java.util.Calendar.MINUTE,minute%60);
    return c.getTimeInMillis();
    }
private void showTimes() {
    M3Page.setSummary(fromRow,minutes[0]<0?act.getString(R.string.jm_data_anytime):NumberView.minhourstr(todayAt(minutes[0])));
    M3Page.setSummary(toRow,minutes[1]<0?act.getString(R.string.jm_data_anytime):NumberView.minhourstr(todayAt(minutes[1])));
    }
private void pickTime(int which) {
    page.hideKeyboard();
    int m=minutes[which];
    if(m<0)
        m=which==0?0:23*60+59;
    DataPickers.time(act,act.getString(which==0?R.string.jm_data_from:R.string.jm_data_to),m/60,m%60,(h,min) -> {
        minutes[which]=h*60+min;
        showTimes();
        });
    }

private static void reset() {
    under="0";
    above="999";
    ingredient="";
    quantity="";
    scans=false;
    history=false;
    stream=true;
    streamcal=false;
    historycal=false;
    label=-1;
    minutes[0]=-1;
    minutes[1]=-1;
    valid=true;
    }
private void save() {
    under=M3Page.text(underEdit);
    above=M3Page.text(aboveEdit);
    ingredient=M3Page.text(ingredientEdit);
    quantity=M3Page.text(quantityEdit);
    scans=sources[0].isChecked();
    history=sources[1].isChecked();
    stream=sources[2].isChecked();
    streamcal=sources[3].isChecked();
    historycal=sources[4].isChecked();
    }

private static float parse(String s) {
    try { return Float.parseFloat(s.replace(',','.')); }
    catch(Exception e) { return 0.0f; }
    }

/* GlucoseCurve.search(forward) */
private void search(boolean forward) {
    save();
    page.hideKeyboard();
    final float funder=parse(under);
    final float fabove=parse(above);
    float ingamount=0.0f;
    String ingsearch=null;
    final boolean meal=label>=0&&label==Natives.getmealvar();
    if(meal) {
        ingamount=parse(quantity);
        if(ingredient.length()>0)
            ingsearch=ingredient;
        }
    final int glsearch=label>=0?0:((history?0x40000002:0)|(scans?0x40000001:0))|(stream?0x40000004:0)|(streamcal?0x40000008:0)|(historycal?0x40000010:0);
    final int searchLabel=glsearch==0?(label>=0?label:labels.size()):glsearch;
    if(Natives.search(searchLabel,funder,fabove,minutes[0],minutes[1],forward,ingsearch,ingamount)==0) {
        page.dismiss();
        act.requestRender();
        ResultBar.show(act);
        }
    else
        page.toast(act.getString(R.string.notfound));
    }

/* Earlier / Close / Later on the graph after a hit (GlucoseCurve.searchcontrol). */
static final class ResultBar {
    private static View bar=null;
    static void hide() {
        if(bar!=null) {
            ViewGroup parent=(ViewGroup)bar.getParent();
            if(parent!=null)
                parent.removeView(bar);
            bar=null;
            }
        }
    static void show(MainActivity act) {
        hide();
        final Context ctx=M3Page.themed(act);
        MaterialCardView card=new MaterialCardView(ctx);
        card.setCardBackgroundColor(M3Page.color(ctx,com.google.android.material.R.attr.colorSurfaceContainerHigh));
        card.setRadius(M3Page.dp(ctx,28));
        card.setStrokeWidth(0);
        card.setCardElevation(M3Page.dp(ctx,3));
        LinearLayout row=new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final int p=M3Page.dp(ctx,4);
        row.setPadding(p,p,p,p);
        MaterialButton prev=icon(ctx,R.drawable.ic_jm_chevron_left,act.getString(R.string.jm_data_earlier));
        MaterialButton close=icon(ctx,R.drawable.ic_jm_close,act.getString(R.string.closename));
        MaterialButton next=icon(ctx,R.drawable.ic_jm_chevron_right,act.getString(R.string.jm_data_later));
        row.addView(prev);
        row.addView(close);
        row.addView(next);
        card.addView(row);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        params.bottomMargin=MainActivity.systembarBottom+M3Page.dp(ctx,16);
        act.addContentView(card,params);
        bar=card;
        final GlucoseCurve curve=act.curve;
        GlucoseCurve.onSearchAway=ResultBar::hide;
        prev.setOnClickListener(v -> {
            if(Natives.earliersearch()==0) {
                next.setVisibility(View.VISIBLE);
                act.requestRender();
                }
            else if(next.getVisibility()!=View.VISIBLE) {
                act.poponback();
                curve.searchaway();
                }
            else
                v.setVisibility(View.INVISIBLE);
            });
        next.setOnClickListener(v -> {
            if(Natives.latersearch()==0) {
                prev.setVisibility(View.VISIBLE);
                act.requestRender();
                }
            else if(prev.getVisibility()!=View.VISIBLE) {
                act.poponback();
                curve.searchaway();
                }
            else
                v.setVisibility(View.INVISIBLE);
            });
        close.setOnClickListener(v -> {
            act.poponback();
            curve.searchaway();
            });
        act.setonback(curve::searchaway);
        }
    private static MaterialButton icon(Context ctx,int icon,String desc) {
        MaterialButton b=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialIconButtonStyle);
        b.setIconResource(icon);
        b.setIconTint(ColorStateList.valueOf(M3Page.color(ctx,com.google.android.material.R.attr.colorOnSurface)));
        b.setContentDescription(desc);
        b.setMinimumWidth(M3Page.dp(ctx,56));
        return b;
        }
    }
}
