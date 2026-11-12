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

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.autocomplete.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;

import tk.glucodata.m3.M3Page;
import tk.glucodata.settings.ExchangePage;

/*
 * Activities from Health Connect (ActivitySettings) as Material 3 pages:
 * settings, label mappings, one mapping, saved activities and one activity.
 * Natives calls, checks and messages are the old ones.
 */
final class ActivityPage {
private ActivityPage() {}
private static final String LOG_ID="ActivityPage";
private static final int[] intervals={0,900,1800,3600,10800,21600,86400};

private static void fail(MainActivity act,String operation,Exception e) {
    Log.stack(LOG_ID,operation,e);
    Applic.argToaster(act,ActivitySettings.message(e),Toast.LENGTH_LONG);
    }

private static int indexOf(int[] arr,long v) {
    for(int i=0;i<arr.length;++i)
        if(arr[i]==v) return i;
    return 0;
    }

/* ActivitySettings.show */
static void show(MainActivity act) {
    final long[] config;
    try { config=ActivityNative.config(); }
    catch(Exception e) { fail(act,"Open activity settings",e); return; }
    final long[] from={config[0]==0?System.currentTimeMillis()/1000-7*86400:config[0]};
    final int[] interval={indexOf(intervals,config[1])};
    final boolean[] amounts={(config[5]&ActivityNative.CREATE_AMOUNTS)!=0};
    final int[] units={(config[5]&ActivityNative.USE_MILES)!=0?1:0};
    final long[] saved={from[0],intervals[interval[0]],config[5]&(ActivityNative.CREATE_AMOUNTS|ActivityNative.USE_MILES)};

    final M3Page page=M3Page.open(act,R.string.activity_import);
    page.help(R.string.activities);
    page.onClose(() -> ExchangePage.refreshTop(act));

    LinearLayout ret=page.section(R.string.jm_share_act_sec_retrieve);
    final View fromRow=page.link(ret,R.drawable.ic_jm_calendar_today,act.getString(R.string.activity_from),ActivitySettings.date(from[0]),() -> {});
    fromRow.setOnClickListener(v -> {
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(from[0]*1000);
        DatePickerDialog d=new DatePickerDialog(M3Page.themed(act),(picker,y,m,dd) -> {
            Calendar cal=Calendar.getInstance();cal.set(y,m,dd,0,0,0);cal.set(Calendar.MILLISECOND,0);
            from[0]=cal.getTimeInMillis()/1000;
            M3Page.setSummary(fromRow,ActivitySettings.date(from[0]));
            },c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH));
        d.getDatePicker().setMaxDate(System.currentTimeMillis());
        d.show();
        });
    page.choice(ret,act.getString(R.string.activity_interval),act.getResources().getStringArray(R.array.activity_intervals),interval[0],i -> interval[0]=i);
    page.toggle(ret,R.drawable.ic_jm_medication,act.getString(R.string.activity_create_amounts),null,() -> amounts[0],on -> amounts[0]=on);
    page.choice(ret,act.getString(R.string.activity_units),act.getResources().getStringArray(R.array.activity_units),units[0],i -> units[0]=i);

    final Runnable apply=() -> {
        long[] current=ActivityNative.config();
        int options=(amounts[0]?ActivityNative.CREATE_AMOUNTS:0)|(units[0]==1?ActivityNative.USE_MILES:0);
        ActivityNative.configure(from[0],intervals[interval[0]],(int)current[3]);
        ActivityNative.setOptions(options);
        ActivityHealth.settingsChanged(act);
        ActivityHealth.numberEditorClosed();
        saved[0]=from[0];saved[1]=intervals[interval[0]];saved[2]=options;
        };

    LinearLayout status=page.section(R.string.jm_share_lv_sec_status);
    final TextView statusView=page.note(status,"");
    statusView.setTextIsSelectable(true);
    com.google.android.material.button.MaterialButton retrieve=page.button(M3Page.Style.TONAL,act.getString(R.string.activity_retrieve),R.drawable.ic_jm_sync,() -> {
        try { apply.run(); ActivityHealth.retrieve(act); }
        catch(Exception e) { fail(act,"Retrieve activities",e); }
        });
    retrieve.setEnabled(!Natives.staticnum());
    page.buttons(status,retrieve);

    LinearLayout more=page.section(R.string.jm_share_act_sec_more);
    page.link(more,R.drawable.ic_jm_label,act.getString(R.string.activity_labels),act.getString(R.string.jm_share_act_labels_summary),() -> {
        try { mappings(act); } catch(Exception e) { fail(act,"Open activity label mappings",e); }
        });
    page.link(more,R.drawable.ic_jm_list,act.getString(R.string.activity_saved),null,() -> {
        try { activities(act); } catch(Exception e) { fail(act,"Open saved activities",e); }
        });

    final Handler handler=new Handler(Looper.getMainLooper());
    Runnable refresh=new Runnable() { public void run() {
        if(!page.isOpen()) return;
        try {
            long last=ActivityNative.config()[2];
            statusView.setText(act.getString(R.string.activity_last)+" "+(last==0?act.getString(R.string.activity_never):ActivitySettings.date(last))+"\n"+ActivityHealth.status());
            }
        catch(Exception e) {
            Log.stack(LOG_ID,"Refresh activity status",e);
            statusView.setText(ActivitySettings.message(e));
            return;
            }
        handler.postDelayed(this,1000);
        }};
    handler.post(refresh);
    page.onClose(() -> handler.removeCallbacks(refresh));

    page.closeCheck(() -> {
        int options=(amounts[0]?ActivityNative.CREATE_AMOUNTS:0)|(units[0]==1?ActivityNative.USE_MILES:0);
        if(from[0]==saved[0]&&intervals[interval[0]]==saved[1]&&options==saved[2])
            return true;
        M3Page.confirm(act,act.getString(R.string.withoutsaving),null,act.getString(R.string.ok),page::dismiss);
        return false;
        });
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
            try {
                apply.run();
                page.dismiss();
                try { ActivityHealth.saved(act); }
                catch(Exception e) { fail(act,"Open activity permissions",e); }
                }
            catch(Exception e) { fail(act,"Save activity settings",e); }
            }));
    }

/* ActivitySettings.mappings */
static void mappings(MainActivity act) {
    final int[] types=ActivityHealth.typeCodes();
    final String[] measures=act.getResources().getStringArray(R.array.activity_measures);
    final M3Page page=M3Page.open(act,R.string.activity_labels);
    page.help(R.string.assignActivity);
    final LinearLayout sec=page.section((CharSequence)null);
    final Runnable[] fill={null};
    fill[0]=() -> {
        sec.removeAllViews();
        ArrayList<String> labels=Natives.getLabels();
        int defaultLabel=(int)ActivityNative.config()[3];
        page.link(sec,R.drawable.ic_jm_label,act.getString(R.string.activity_default),
            defaultLabel>=0&&defaultLabel<labels.size()?labels.get(defaultLabel):"Activity",() -> mapping(act,-1,fill[0]));
        for(int type:types) {
            int[] rule=ActivityNative.rule(type);
            String name=rule[0]>=0&&rule[0]<labels.size()?labels.get(rule[0]):act.getString(R.string.activity_default);
            page.link(sec,R.drawable.ic_jm_directions_run,ActivityHealth.typeName(type),name+" · "+measures[rule[1]],() -> mapping(act,type,fill[0]));
            }
        };
    fill[0].run();
    }

/* ActivitySettings.mapping */
static void mapping(MainActivity act,int type,Runnable refreshed) {
    try {
        final int[] rule=type<0?new int[]{(int)ActivityNative.config()[3],0}:ActivityNative.rule(type);
        final ArrayList<String> names=new ArrayList<>();
        final ArrayList<Integer> ids=new ArrayList<>();
        ArrayList<String> all=Natives.getLabels();
        for(int i=0;i<all.size();++i)
            if(ActivityNative.labelAllowed(i)) { names.add(all.get(i)); ids.add(i); }
        final int[] label={Math.max(0,ids.indexOf(rule[0]))};
        final int initialLabel=ids.indexOf(rule[0]);
        final int[] measure={rule[1]};
        final float initialRoundTo=type<0?1.0f:ActivityNative.roundTo(type);
        final SharedPreferences preferences=act.getSharedPreferences("activity-mapping",Context.MODE_PRIVATE);
        final String existingKey="update_existing_"+type;
        final boolean initialExisting=preferences.getBoolean(existingKey,true);
        final boolean[] existing={initialExisting};
        final boolean[] saved={false};

        final M3Page page=M3Page.open(act,type<0?act.getString(R.string.activity_default):ActivityHealth.typeName(type));
        page.help(R.string.assignActivity);
        if(refreshed!=null) page.onClose(refreshed);
        LinearLayout sec=page.section((CharSequence)null);
        final MaterialAutoCompleteTextView[] labelView={null};
        labelView[0]=page.choice(sec,act.getString(R.string.jm_share_act_label),names,label[0],i -> label[0]=i);
        page.buttons(sec,page.button(M3Page.Style.TEXT,act.getString(R.string.activity_new_label),R.drawable.ic_jm_add,() -> newLabel(page,names,ids,label,labelView[0],sec)));
        final TextInputEditText[] roundTo={null};
        if(type>=0) {
            page.choice(sec,act.getString(R.string.jm_share_act_measure),act.getResources().getStringArray(R.array.activity_measures),measure[0],i -> measure[0]=i);
            roundTo[0]=page.field(sec,act.getString(R.string.activity_round_to),Float.toString(initialRoundTo),M3Page.DECIMAL,null,null);
            page.toggle(sec,R.drawable.ic_jm_history,act.getString(R.string.activity_update_existing),null,() -> existing[0],on -> existing[0]=on);
            }
        page.closeCheck(() -> {
            boolean changed=!saved[0]&&(label[0]!=initialLabel||type>=0&&(measure[0]!=rule[1]||existing[0]!=initialExisting
                ||ActivityFormat.roundingChanged(M3Page.text(roundTo[0]),initialRoundTo)));
            if(!changed) return true;
            M3Page.confirm(act,act.getString(R.string.withoutsaving),null,act.getString(R.string.ok),page::dismiss);
            return false;
            });
        page.bottomBar(
            page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::dismiss),
            page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
                try {
                    if(ids.isEmpty()) throw new IllegalStateException(act.getString(R.string.activity_choose_label));
                    int id=ids.get(label[0]);
                    float increment=initialRoundTo;
                    if(type>=0) {
                        try { increment=ActivityFormat.roundTo(M3Page.text(roundTo[0])); }
                        catch(IllegalArgumentException e) {
                            M3Page.error(roundTo[0],act.getString(R.string.activity_round_to_invalid));
                            return;
                            }
                        }
                    if(type<0) {
                        long[] cfg=ActivityNative.config();
                        ActivityNative.configure(cfg[0],(int)cfg[1],id);
                        }
                    else {
                        ActivityNative.setRule(type,id,measure[0],increment,existing[0]);
                        preferences.edit().putBoolean(existingKey,existing[0]).apply();
                        }
                    saved[0]=true;
                    page.dismiss();
                    }
                catch(Exception e) { fail(act,"Save activity mapping: type="+type,e); }
                }));
        }
    catch(Exception e) { fail(act,"Open activity mapping: type="+type,e); }
    }

private static void newLabel(M3Page page,ArrayList<String> names,ArrayList<Integer> ids,int[] label,MaterialAutoCompleteTextView view,LinearLayout sec) {
    final MainActivity act=page.act;
    FrameLayout box=new FrameLayout(page.ctx);
    box.setPadding(page.dp(24),page.dp(8),page.dp(24),0);
    TextInputLayout layout=new TextInputLayout(new androidx.appcompat.view.ContextThemeWrapper(page.ctx,com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox),null,
        com.google.android.material.R.attr.textInputOutlinedStyle);
    layout.setHint(act.getString(R.string.jm_share_act_label));
    TextInputEditText text=new TextInputEditText(layout.getContext());
    text.setSingleLine(true);
    text.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    layout.addView(text);
    box.addView(layout,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    M3Page.dialog(act).setTitle(R.string.activity_new_label).setView(box)
        .setNegativeButton(R.string.cancel,null)
        .setPositiveButton(R.string.save,(d,w) -> {
            try {
                int id=ActivityNative.newLabel(String.valueOf(text.getText()).trim());
                String name=Natives.getLabels().get(id);
                names.add(name); ids.add(id);
                label[0]=ids.size()-1;
                android.widget.ArrayAdapter<?> a=(android.widget.ArrayAdapter<?>)view.getAdapter();
                @SuppressWarnings("unchecked") android.widget.ArrayAdapter<CharSequence> ca=(android.widget.ArrayAdapter<CharSequence>)a;
                ca.add(name);
                M3Page.select(view,name);
                }
            catch(Exception e) { fail(act,"Create activity label",e); }
            })
        .show();
    }

/* ActivitySettings.activities */
static void activities(MainActivity act) {
    final ArrayList<Integer> ids=new ArrayList<>();
    final ArrayList<String[]> rows=new ArrayList<>();
    final Runnable collect=() -> {
        ids.clear(); rows.clear();
        for(int id:ActivityNative.indices(false)) {
            int source=ActivityNative.representative(id);
            double[] r=ActivityNative.record(source);
            String[] t=ActivityNative.texts(source);
            if(r==null||t==null) continue;
            ids.add(id);
            rows.add(new String[]{ActivityHealth.typeName((int)r[2])+(t[2].isEmpty()?"":" · "+t[2]),ActivitySettings.date((long)r[3])});
            }
        };
    collect.run();
    if(ids.isEmpty()) {
        Applic.argToaster(act,R.string.activity_none,Toast.LENGTH_LONG);
        return;
        }
    final M3Page page=M3Page.open(act,R.string.activity_saved);
    final LinearLayout sec=page.section((CharSequence)null);
    final Runnable[] fill={null};
    fill[0]=() -> {
        collect.run();
        sec.removeAllViews();
        if(ids.isEmpty())
            page.empty(sec,R.drawable.ic_jm_directions_run,act.getString(R.string.activity_none),null);
        for(int i=0;i<ids.size();i++) {
            final int group=ids.get(i);
            page.link(sec,R.drawable.ic_jm_directions_run,rows.get(i)[0],rows.get(i)[1],() -> details(act,group,null,fill[0]));
            }
        };
    fill[0].run();
    }

/* ActivitySettings.details */
static void details(MainActivity act,int group,Runnable amountsChanged,Runnable removed) {
    final double[] root,summary;
    final CharSequence text;
    try {
        root=ActivityNative.record(group);
        if(root==null) throw new IllegalStateException(act.getString(R.string.activity_unavailable));
        summary=ActivityNative.record(ActivityNative.representative(group));
        if(summary==null) throw new IllegalStateException(act.getString(R.string.activity_unavailable));
        text=ActivitySettings.detailsText(act,group);
        }
    catch(Exception e) { fail(act,"Open activity details: group="+group,e); return; }
    final M3Page page=M3Page.open(act,ActivityHealth.typeName((int)summary[2]));
    LinearLayout sec=page.section((CharSequence)null);
    TextView body=page.note(sec,text);
    body.setTextIsSelectable(true);
    body.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
    body.setLineSpacing(page.dp(3),1.0f);
    LinearLayout acts=page.section(R.string.jm_share_act_sec_actions);
    page.link(acts,R.drawable.ic_jm_label,act.getString(R.string.activity_labels),null,() -> mapping(act,(int)summary[2],amountsChanged));
    if((((int)root[1])&2)==0)
        page.link(acts,R.drawable.ic_jm_link,act.getString(R.string.activity_link),null,() -> {
            try { chooseAmount(act,group,page); } catch(Exception e) { fail(act,"Choose activity Amount: group="+group,e); }
            });
    page.item(acts,R.drawable.ic_jm_remove_circle,act.getString(R.string.activity_remove_source),null,() -> {
        try {
            int[] sids=ActivityNative.sourceIDs(group);
            String[] names=new String[sids.length];
            for(int i=0;i<sids.length;++i) names[i]=ActivityFormat.sourceLabel(ActivityNative.sourceName(sids[i]),true);
            M3Page.dialog(act).setTitle(R.string.activity_remove_source).setItems(names,(dialog,index) ->
                M3Page.confirm(act,act.getString(R.string.activity_remove_source),act.getString(R.string.activity_remove_source_confirm,names[index]),act.getString(R.string.delete),() -> {
                    try {
                        ActivityNative.removeSource(group,sids[index]);
                        page.dismiss();
                        ActivityHealth.numberEditorClosed();
                        act.requestRender();
                        if(ActivityNative.representative(group)!=0) details(act,group,amountsChanged,removed);
                        else if(removed!=null) removed.run();
                        }
                    catch(Exception e) { fail(act,"Remove activity source: group="+group,e); }
                    })).setNegativeButton(R.string.cancel,null).show();
            }
        catch(Exception e) { fail(act,"Choose activity source: group="+group,e); }
        });
    page.item(acts,R.drawable.ic_jm_delete,act.getString(R.string.delete),null,() ->
        M3Page.confirm(act,act.getString(R.string.deletequestion),act.getString(R.string.activity_delete_confirm),act.getString(R.string.delete),() -> {
            try {
                ActivityNative.remove(group);
                page.dismiss();
                if(removed!=null) removed.run();
                ActivityHealth.numberEditorClosed();
                act.requestRender();
                }
            catch(Exception e) { fail(act,"Delete activity: group="+group,e); }
            }));
    }

private static void chooseAmount(MainActivity act,int group,M3Page page) {
    String[] candidates=ActivityNative.candidates(group);
    String[] display=new String[candidates.length];
    ArrayList<String> labels=Natives.getLabels();
    for(int i=0;i<candidates.length;++i) {
        String[] parts=candidates[i].split(":");
        display[i]=ActivitySettings.date(Long.parseLong(parts[2]))+"  "+labels.get(Integer.parseInt(parts[3]))+"  "+parts[4];
        }
    if(display.length==0) {
        Applic.argToaster(act,R.string.activity_no_match,Toast.LENGTH_LONG);
        return;
        }
    M3Page.dialog(act).setTitle(R.string.activity_link).setItems(display,(dialog,pos) -> {
        try {
            String[] p=candidates[pos].split(":");
            ActivityNative.link(group,Integer.parseInt(p[0]),Integer.parseInt(p[1]),Long.parseLong(p[2]),Integer.parseInt(p[3]),Float.parseFloat(p[4]));
            page.dismiss();
            }
        catch(Exception e) { fail(act,"Link activity Amount: group="+group,e); }
        }).setNegativeButton(R.string.cancel,null).show();
    }
}
