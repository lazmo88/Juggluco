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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import tk.glucodata.m3.M3Page;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Log.stack;

/* Phone controls for the statistics (menu Statistics).  The statistics
 * themselves (percentages, percentile graph, summary graph) are drawn by the
 * native renderer and their numbers are not available to Java, so they stay
 * native.  This replaces the old cream button box at the bottom with an M3
 * floating card; same natives, same order of side effects as Stats.mkstats. */
final class StatsBar {
private static final String LOG_ID="StatsBar";
private StatsBar() {}

private static int color(Context ctx,int attr) {
    return M3Page.color(ctx,attr);
    }

static void show(MainActivity act) {
    final boolean showhistory=Natives.getAnalysehistory();
    MainActivity.clearonback();
    final Context ctx=M3Page.themed(act);
    final int pad=M3Page.dp(ctx,12);

    MaterialCardView card=new MaterialCardView(ctx);
    card.setCardBackgroundColor(color(ctx,com.google.android.material.R.attr.colorSurfaceContainerHigh));
    card.setRadius(M3Page.dp(ctx,28));
    card.setStrokeWidth(0);
    card.setCardElevation(M3Page.dp(ctx,3));
    card.setClickable(true);

    LinearLayout col=new LinearLayout(ctx);
    col.setOrientation(LinearLayout.VERTICAL);
    col.setPadding(pad+M3Page.dp(ctx,4),pad,pad,pad);
    card.addView(col,new FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));

    LinearLayout row1=new LinearLayout(ctx);
    row1.setGravity(Gravity.CENTER_VERTICAL);
    MaterialSwitch history=new MaterialSwitch(ctx);
    history.setText(R.string.historyname);
    history.setChecked(showhistory);
    history.setTextColor(color(ctx,com.google.android.material.R.attr.colorOnSurface));
    history.setSwitchPadding(M3Page.dp(ctx,12));
    row1.addView(history,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    View space=new View(ctx);
    row1.addView(space,new LinearLayout.LayoutParams(M3Page.dp(ctx,16),1,1f));
    MaterialButton days=tonal(ctx,daysText(act));
    days.setIconResource(R.drawable.ic_jm_calendar_month);
    row1.addView(days,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    MaterialButton help=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialIconButtonStyle);
    help.setIconResource(R.drawable.ic_jm_help);
    help.setIconTint(ColorStateList.valueOf(color(ctx,com.google.android.material.R.attr.colorOnSurfaceVariant)));
    help.setContentDescription(act.getString(R.string.helpname));
    row1.addView(help,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    col.addView(row1,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

    LinearLayout row2=new LinearLayout(ctx);
    row2.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
    MaterialButton close=new MaterialButton(ctx,null,com.google.android.material.R.attr.borderlessButtonStyle);
    close.setText(R.string.closename);
    close.setAllCaps(false);
    MaterialButton report=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
    report.setText(R.string.jm_data_report);
    report.setAllCaps(false);
    report.setIconResource(R.drawable.ic_jm_open_in_new);
    MaterialButton curve=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialButtonStyle);
    curve.setText(R.string.summarygraph);
    curve.setAllCaps(false);
    curve.setIconResource(R.drawable.ic_jm_show_chart);
    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT);
    bp.setMarginStart(M3Page.dp(ctx,8));
    row2.addView(close,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    row2.addView(report,bp);
    row2.addView(curve,new LinearLayout.LayoutParams(bp));
    col.addView(row2,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

    if(!act.curve.statspresent)
        curve.setVisibility(View.INVISIBLE);
    act.curve.summarybutton=curve;

    FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT,Gravity.BOTTOM);
    final int margin=M3Page.dp(ctx,12);
    params.setMargins(MainActivity.systembarLeft+margin,0,MainActivity.systembarRight+margin,MainActivity.systembarBottom+margin);
    act.addContentView(card,params);

    final Runnable remove=() -> {
        ViewGroup parent=(ViewGroup)card.getParent();
        if(parent!=null)
            parent.removeView(card);
        };
    final Runnable closeonback=() -> {
        act.curve.statspresent=false;
        act.curve.summarybutton=null;
        remove.run();
        Natives.endstats();
        if(doLog) {Log.i(LOG_ID,"closeonback");}
        if(Menus.on)
            Menus.show(act);
        else
            act.requestRender();
        };
    close.setOnClickListener(v -> {
        act.poponback();
        closeonback.run();
        });
    act.setonback(closeonback);
    help.setOnClickListener(v -> {
        act.themeLightBars();
        tk.glucodata.help.help(R.string.stathelp,act,l -> act.lightBars(!Natives.getInvertColors()));
        });
    days.setOnClickListener(v -> askdays(act,history.isChecked(),curve,days));
    final boolean[] dontswitch={false};
    history.setOnCheckedChangeListener((buttonView,isChecked) -> {
        if(dontswitch[0])
            return;
        act.curve.summarybutton=curve;
        curve.setVisibility(View.INVISIBLE);
        act.curve.statspresent=false;
        Natives.analysedays(-1,isChecked);
        final boolean usehistory=Natives.getAnalysehistory();
        if(usehistory!=isChecked) {
            com.google.android.material.snackbar.Snackbar.make(card,usehistory?R.string.nostreamvalues:R.string.nohistoryvalues,com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
            dontswitch[0]=true;
            history.setChecked(usehistory);
            dontswitch[0]=false;
            }
        act.requestRender();
        });
    curve.setOnClickListener(v -> {
        act.poponback();
        act.setonback(() -> {
            Natives.summarygraph(false);
            show(act);
            act.requestRender();
            });
        Natives.summarygraph(true);
        remove.run();
        act.requestRender();
        });
    report.setOnClickListener(v -> {
        if(Natives.getusexdripwebserver())
            Stats.webPercentiles(act,Natives.getAnalysedays(),history.isChecked());
        else
            M3Page.message(act,act.getString(R.string.titlewebserverneed),act.getString(R.string.messagewebserverneed));
        });
    }

private static String daysText(MainActivity act) {
    return Natives.getAnalysedays()+" "+act.getString(R.string.days).toLowerCase();
    }

private static MaterialButton tonal(Context ctx,CharSequence text) {
    MaterialButton b=new MaterialButton(new android.view.ContextThemeWrapper(ctx,com.google.android.material.R.style.Widget_Material3_Button_TonalButton),null,0);
    b.setBackgroundTintList(ColorStateList.valueOf(color(ctx,com.google.android.material.R.attr.colorSecondaryContainer)));
    b.setTextColor(color(ctx,com.google.android.material.R.attr.colorOnSecondaryContainer));
    b.setIconTint(ColorStateList.valueOf(color(ctx,com.google.android.material.R.attr.colorOnSecondaryContainer)));
    b.setCornerRadius(M3Page.dp(ctx,20));
    b.setAllCaps(false);
    b.setText(text);
    return b;
    }

/* Number of days to analyse: Stats.askdays as a Material dialog. */
private static void askdays(MainActivity act,boolean history,MaterialButton curve,MaterialButton daysButton) {
    Context ctx=M3Page.themed(act);
    TextInputLayout layout=new TextInputLayout(ctx,null,com.google.android.material.R.attr.textInputOutlinedStyle);
    layout.setHint(act.getString(R.string.days));
    TextInputEditText edit=new TextInputEditText(layout.getContext());
    edit.setInputType(InputType.TYPE_CLASS_NUMBER);
    edit.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    edit.setSingleLine(true);
    edit.setText(String.valueOf(Natives.getAnalysedays()));
    layout.addView(edit,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    FrameLayout frame=new FrameLayout(ctx);
    final int p=M3Page.dp(ctx,24);
    frame.setPadding(p,M3Page.dp(ctx,8),p,0);
    frame.addView(layout);
    androidx.appcompat.app.AlertDialog dialog=M3Page.dialog(act)
        .setTitle(R.string.statistics)
        .setView(frame)
        .setNegativeButton(R.string.cancel,null)
        .setPositiveButton(R.string.ok,null)
        .create();
    dialog.setOnShowListener(d -> {
        edit.requestFocus();
        edit.selectAll();
        tk.glucodata.help.showkeyboard(act,edit);
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int get=0;
            String str=edit.getText()==null?"":edit.getText().toString().trim();
            try {
                get=Integer.parseInt(str);
                }
            catch(Throwable e) {
                stack(LOG_ID,e);
                }
            if(get<=0) {
                layout.setError("'"+str+act.getString(R.string.invaliddays));
                return;
                }
            act.curve.statspresent=false;
            act.curve.summarybutton=curve;
            curve.setVisibility(View.INVISIBLE);
            Natives.analysedays(get,history);
            daysButton.setText(daysText(act));
            act.requestRender();
            dialog.dismiss();
            });
        });
    dialog.setOnDismissListener(d -> tk.glucodata.help.hidekeyboard(act));
    dialog.show();
    }
}
