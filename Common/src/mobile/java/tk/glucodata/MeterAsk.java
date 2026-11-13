/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import tk.glucodata.m3.M3Page;

/*
 * "Ask before saving readings" for a Bluetooth glucose meter that is used by
 * more than one person (each with an own phone).  Off by default, per meter.
 *
 * Off: GlucoseMeterGatt saves every Glucose Measurement record as before;
 * received() returns false without touching anything.
 *
 * On: a new record is not given to Natives.GlucoseMeterSave but kept here as
 * "pending".  After the sync (Record Access Control Point response), or the
 * next time MainActivity resumes, a dialog asks which readings are the user's.
 * "Mine" records are then saved the old way, in ascending sequence order; the
 * others are remembered as "not mine" and dropped whenever the meter sends
 * them again.
 *
 * A pending record is never lost: the native side only moves the meter's
 * nextIndex when a record is saved, so the meter sends everything after the
 * last saved record again at every sync.  Records are identified by sequence
 * number plus base time, so a sequence number that is reused after a meter
 * reset is not taken for an old decision.
 *
 * State is in the SharedPreferences file "jm_meter_ask", keyed by the meter's
 * device name.  Everything is synchronized on one lock: Bluetooth callbacks,
 * the save thread and the UI thread all come here.
 */
public final class MeterAsk {
private MeterAsk() {}

private static final String LOG_ID="MeterAsk";
private static final String PREFS="jm_meter_ask";
private static final String ON="on|";
private static final String PENDING="pending|";
private static final String DECIDED="decided|";
/* Shorter records are ignored by Natives.GlucoseMeterSave: leave them to it. */
private static final int MIN_LENGTH=14;
private static final int MAX_DECIDED=2000;
private static final long COMMIT_INTERVAL=1000L;
/* Record Access Control Point response "report stored records: success". */
private static final byte[] RACP_SUCCESS={6,0,1,1};

private static final Object lock=new Object();

/* One Glucose Measurement (0x2A18) record as received. */
static final class Rec {
    final byte[] raw;
    final long received;
    final int seq;
    final String key;
    final long time;
    Rec(byte[] raw,long received) {
        this.raw=raw;
        this.received=received;
        seq=sequence(raw);
        key=key(raw);
        time=readingTime(raw);
        }
    }

private static final class State {
    boolean on=false;
    final LinkedHashMap<String,Rec> pending=new LinkedHashMap<>();
    /* key -> mine; oldest decision first. */
    final LinkedHashMap<String,Boolean> decided=new LinkedHashMap<>();
    }

/* ---------- record layout (Bluetooth Glucose Service) ---------- */

static int sequence(byte[] v) {
    return (v[1]&0xFF)|((v[2]&0xFF)<<8);
    }

/* Sequence number and the seven base time bytes. */
static String key(byte[] v) {
    StringBuilder sb=new StringBuilder(22);
    sb.append(sequence(v)).append('_');
    for(int i=3;i<10;i++) {
        sb.append(Character.forDigit((v[i]>>4)&0xF,16));
        sb.append(Character.forDigit(v[i]&0xF,16));
        }
    return sb.toString();
    }

private static boolean hasOffset(byte[] v) {
    return (v[0]&1)!=0;
    }

/* Base time plus time offset, as the meter's local time (milliseconds). */
static long readingTime(byte[] v) {
    Calendar cal=Calendar.getInstance();
    cal.clear();
    cal.set((v[3]&0xFF)|((v[4]&0xFF)<<8),v[5]-1,v[6],v[7],v[8],v[9]);
    if(hasOffset(v))
        cal.add(Calendar.MINUTE,(short)((v[10]&0xFF)|((v[11]&0xFF)<<8)));
    return cal.getTimeInMillis();
    }

private static float sfloat(int raw) {
    switch(raw) {
        case 0x07FE: return Float.POSITIVE_INFINITY;
        case 0x0802: return Float.NEGATIVE_INFINITY;
        case 0x07FF:
        case 0x0801:
        case 0x0800: return Float.NaN;
        }
    int mantissa=raw&0x0FFF;
    if(mantissa>=0x0800)
        mantissa-=0x1000;
    int exponent=(raw>>12)&0xF;
    if(exponent>=8)
        exponent-=16;
    return (float)(mantissa*Math.pow(10.0,exponent));
    }

/* The value as the native side reads it (MeterData.hpp). */
static float mgdL(byte[] v) {
    final int at=hasOffset(v)?12:10;
    final float value=sfloat((v[at]&0xFF)|((v[at+1]&0xFF)<<8));
    if((v[0]&4)!=0)
        return value*1000.0f*18.0f; // mol/L
    return value*100000.0f; // kg/L
    }

private static String valueText(Context ctx,Rec rec) {
    final float mgdl=mgdL(rec.raw);
    if(Float.isNaN(mgdl)||Float.isInfinite(mgdl))
        return ctx.getString(R.string.jm_meterask_no_value);
    if(Natives.getunit()==1)
        return ctx.getString(R.string.jm_meterask_value,String.format(Locale.getDefault(),"%.1f",mgdl/18.0182f),ctx.getString(R.string.mmolL));
    return ctx.getString(R.string.jm_meterask_value,String.format(Locale.getDefault(),"%d",Math.round(mgdl)),ctx.getString(R.string.mgdL));
    }

private static String timeText(Context ctx,Rec rec) {
    return SensorUi.date(ctx,rec.time)+"  "+SensorUi.time(ctx,rec.time);
    }

/* ---------- state ---------- */

private static HashMap<String,State> states=null; // guarded by lock
private static volatile boolean anyOn=false;
private static volatile boolean anyPending=false;
private static volatile boolean loaded=false;
private static long lastCommit=0L;
private static boolean unflushed=false;

private static SharedPreferences prefs() {
    return Applic.app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

private static State get(HashMap<String,State> map,String name) {
    State st=map.get(name);
    if(st==null) {
        st=new State();
        map.put(name,st);
        }
    return st;
    }

/* Call with lock held. */
private static HashMap<String,State> states() {
    if(states!=null)
        return states;
    final HashMap<String,State> map=new HashMap<>();
    /* If reading the file itself fails this throws and nothing is cached, so
     * the next call tries again instead of taking the option as off. */
    for(Map.Entry<String,?> entry:prefs().getAll().entrySet()) {
        final String k=entry.getKey();
        final Object v=entry.getValue();
        try {
            if(k.startsWith(ON)&&v instanceof Boolean)
                get(map,k.substring(ON.length())).on=(Boolean)v;
            else if(k.startsWith(PENDING)&&v instanceof String)
                readPending(get(map,k.substring(PENDING.length())),(String)v);
            else if(k.startsWith(DECIDED)&&v instanceof String)
                readDecided(get(map,k.substring(DECIDED.length())),(String)v);
            }
        catch(Throwable th) {
            Log.stack(LOG_ID,"load "+k,th);
            }
        }
    states=map;
    flags();
    loaded=true;
    return map;
    }

/* Call with lock held. */
private static void flags() {
    boolean on=false,pending=false;
    for(State st:states.values()) {
        on=on||st.on;
        pending=pending||!st.pending.isEmpty();
        }
    anyOn=on;
    anyPending=pending;
    }

private static void load() {
    if(!loaded) {
        synchronized(lock) {
            states();
            }
        }
    }

private static void readPending(State st,String text) {
    for(String line:text.split("\n")) {
        final int comma=line.indexOf(',');
        if(comma<=0)
            continue;
        try {
            final byte[] raw=Base64.decode(line.substring(comma+1),Base64.NO_WRAP);
            if(raw.length<MIN_LENGTH)
                continue;
            final Rec rec=new Rec(raw,Long.parseLong(line.substring(0,comma)));
            st.pending.put(rec.key,rec);
            }
        catch(Throwable th) {
            Log.stack(LOG_ID,"readPending",th);
            }
        }
    }

private static void readDecided(State st,String text) {
    for(String item:text.split(",")) {
        final int len=item.length();
        if(len<2)
            continue;
        final char c=item.charAt(len-1);
        if(c=='+'||c=='-')
            st.decided.put(item.substring(0,len-1),c=='+');
        }
    }

/* Call with lock held.  sync: on disk before returning. */
private static boolean write(String name,State st,boolean sync) {
    SharedPreferences.Editor edit=prefs().edit();
    edit.putBoolean(ON+name,st.on);
    if(st.pending.isEmpty())
        edit.remove(PENDING+name);
    else {
        StringBuilder sb=new StringBuilder(st.pending.size()*40);
        for(Rec rec:st.pending.values()) {
            if(sb.length()>0)
                sb.append('\n');
            sb.append(rec.received).append(',').append(Base64.encodeToString(rec.raw,Base64.NO_WRAP));
            }
        edit.putString(PENDING+name,sb.toString());
        }
    if(st.decided.isEmpty())
        edit.remove(DECIDED+name);
    else {
        StringBuilder sb=new StringBuilder(st.decided.size()*24);
        for(Map.Entry<String,Boolean> d:st.decided.entrySet()) {
            if(sb.length()>0)
                sb.append(',');
            sb.append(d.getKey()).append(d.getValue()?'+':'-');
            }
        edit.putString(DECIDED+name,sb.toString());
        }
    flags();
    if(sync) {
        lastCommit=System.currentTimeMillis();
        unflushed=false;
        if(edit.commit())
            return true;
        Log.e(LOG_ID,"commit failed "+name);
        return false;
        }
    unflushed=true;
    edit.apply();
    return true;
    }

/* ---------- settings ---------- */

public static boolean enabled(String name) {
    if(name==null)
        return false;
    try {
        synchronized(lock) {
            State st=states().get(name);
            return st!=null&&st.on;
            }
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"enabled",th);
        return false;
        }
    }

public static void setEnabled(String name,boolean on) {
    if(name==null)
        return;
    try {
        synchronized(lock) {
            State st=get(states(),name);
            st.on=on;
            write(name,st,true);
            }
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"setEnabled",th);
        }
    }

public static int pendingCount(String name) {
    if(name==null)
        return 0;
    try {
        synchronized(lock) {
            State st=states().get(name);
            return st==null?0:st.pending.size();
            }
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"pendingCount",th);
        return 0;
        }
    }

/* Run on the UI thread after pending readings were saved or dropped;
 * for a page that shows the number of waiting readings. */
private static Runnable changed=null;
public static void onChanged(Runnable r) {
    changed=r;
    }

/* ---------- Bluetooth thread ---------- */

/*
 * A Glucose Measurement record arrived.  Returns false when the caller has to
 * save it like before (option off, record too short, or anything went wrong);
 * true when it was handled here.
 */
static boolean received(GlucoseMeterGatt gatt,byte[] value) {
    String name=null,key=null;
    try {
        load();
        if(!anyOn)
            return false;
        if(value==null||value.length<MIN_LENGTH)
            return false;
        name=gatt.getDeviceName();
        if(name==null)
            return false;
        synchronized(lock) {
            final State st=states().get(name);
            if(st==null||!st.on)
                return false;
            final String id=key(value);
            final Boolean mine=st.decided.get(id);
            if(mine!=null) {
                if(mine)
                    gatt.saveRecord(value); // native saves it only if it is newer than the last one saved
                return true;
                }
            if(st.pending.containsKey(id))
                return true;
            key=id;
            st.pending.put(id,new Rec(value.clone(),System.currentTimeMillis()));
            /* On disk right away; in a burst (a meter sending its whole memory)
             * at most once a second, the rest when the sync ends in synced().
             * What is lost in between comes again at the next sync. */
            final long now=System.currentTimeMillis();
            write(name,st,now-lastCommit>=COMMIT_INTERVAL||now<lastCommit);
            return true;
            }
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"received",th);
        if(name!=null&&key!=null) {
            try {
                synchronized(lock) {
                    State st=states().get(name);
                    if(st!=null) {
                        st.pending.remove(key);
                        flags();
                        }
                    }
                }
            catch(Throwable th2) {
                Log.stack(LOG_ID,"received undo",th2);
                }
            }
        return false;
        }
    }

/* The meter answered the records request: ask now if the app is on screen,
 * otherwise resume() asks later. */
static void synced(GlucoseMeterGatt gatt) {
    try {
        if(!loaded||!anyPending)
            return;
        final String name=gatt.getDeviceName();
        if(name==null)
            return;
        synchronized(lock) {
            final State st=states().get(name);
            if(st==null||st.pending.isEmpty())
                return;
            if(unflushed)
                write(name,st,true);
            }
        Applic.RunOnUiThread(() -> {
            final MainActivity act=MainActivity.thisone;
            if(act!=null&&act.active)
                ask(act,name,new HashSet<>(),null);
            });
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"synced",th);
        }
    }

/* ---------- UI thread ---------- */

/* MainActivity.onResume (PhoneUi "meterask"). */
public static void resume(MainActivity act) {
    try {
        if(!Applic.Nativesloaded)
            return;
        load();
        if(!anyPending)
            return;
        Applic.getHandler().post(() -> ask(act,null,new HashSet<>(),null));
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"resume",th);
        }
    }

/* Ask about the waiting readings of one meter now; later runs when the user
 * closes the dialog without deciding. */
public static void show(MainActivity act,String name,Runnable later) {
    ask(act,name,new HashSet<>(),later);
    }

private static AlertDialog open=null;
private static MainActivity openAct=null;
private static boolean busy=false; // guarded by lock: decisions are being saved

private static boolean showing() {
    final AlertDialog d=open;
    if(d==null)
        return false;
    final MainActivity a=openAct;
    if(d.isShowing()&&a!=null&&a==MainActivity.thisone&&!a.isFinishing()&&!a.isDestroyed())
        return true;
    open=null;
    openAct=null;
    try {
        d.dismiss();
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"dismiss",th);
        }
    return false;
    }

private static int meterIndex(String name) {
    final int n=Natives.GlucoseMeterCount();
    for(int i=0;i<n;i++) {
        if(name.equals(Natives.GlucoseMeterDeviceName(i)))
            return i;
        }
    return -1;
    }

/* A meter that still exists and has waiting readings: first, if it has any,
 * else the first one not in skip. Call with lock held. */
private static String nextMeter(String first,HashSet<String> skip) {
    final HashMap<String,State> map=states();
    final int n=Natives.GlucoseMeterCount();
    String found=null;
    for(int i=0;i<n;i++) {
        final String name=Natives.GlucoseMeterDeviceName(i);
        if(name==null||skip.contains(name))
            continue;
        final State st=map.get(name);
        if(st==null||st.pending.isEmpty())
            continue;
        if(name.equals(first))
            return name;
        if(found==null)
            found=name;
        }
    return found;
    }

private static void ask(MainActivity act,String first,HashSet<String> skip,Runnable later) {
    try {
        if(act==null||act.isFinishing()||act.isDestroyed()||!Applic.Nativesloaded)
            return;
        if(showing())
            return;
        final String name;
        final ArrayList<Rec> recs;
        synchronized(lock) {
            if(busy)
                return;
            name=nextMeter(first,skip);
            if(name==null)
                return;
            recs=new ArrayList<>(states().get(name).pending.values());
            }
        /* Newest first. */
        Collections.sort(recs,(a,b) -> a.time!=b.time?Long.compare(b.time,a.time):Integer.compare(b.seq,a.seq));
        final int n=recs.size();
        final boolean[] mine=new boolean[n]; // every row starts unchecked

        final MaterialAlertDialogBuilder builder=M3Page.dialog(act);
        final Context ctx=M3Page.themed(act);
        final int onSurface=M3Page.color(ctx,com.google.android.material.R.attr.colorOnSurface);
        final int onSurfaceVariant=M3Page.color(ctx,com.google.android.material.R.attr.colorOnSurfaceVariant);

        LinearLayout box=new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0,M3Page.dp(ctx,8),0,0);

        TextView message=new TextView(ctx);
        message.setText(R.string.jm_meterask_message);
        message.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        message.setTextColor(onSurfaceVariant);
        message.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        message.setPadding(M3Page.dp(ctx,24),0,M3Page.dp(ctx,24),M3Page.dp(ctx,8));
        box.addView(message,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

        if(Natives.GlucoseMeterCount()>1) {
            TextView meter=new TextView(ctx);
            meter.setText(name);
            meter.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            meter.setTextColor(onSurface);
            meter.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            meter.setPadding(M3Page.dp(ctx,24),0,M3Page.dp(ctx,24),M3Page.dp(ctx,8));
            box.addView(meter,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
            }

        final BaseAdapter adapter=new BaseAdapter() {
            @Override public int getCount() { return n; }
            @Override public Object getItem(int pos) { return recs.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos,View convert,ViewGroup parent) {
                View row=convert;
                if(row==null) {
                    row=LayoutInflater.from(ctx).inflate(R.layout.material_settings_row,parent,false);
                    row.findViewById(R.id.jm_row_icon).setVisibility(View.GONE);
                    row.setPaddingRelative(M3Page.dp(ctx,24),0,M3Page.dp(ctx,16),0);
                    MaterialCheckBox check=new MaterialCheckBox(ctx);
                    check.setClickable(false);
                    check.setFocusable(false);
                    M3Page.end(row).addView(check);
                    row.setTag(check);
                    }
                final Rec rec=recs.get(pos);
                M3Page.setTitle(row,valueText(ctx,rec));
                M3Page.setSummary(row,timeText(ctx,rec));
                ((MaterialCheckBox)row.getTag()).setChecked(mine[pos]);
                return row;
                }
            };

        if(n>=3) {
            LinearLayout bulk=new LinearLayout(ctx);
            bulk.setGravity(Gravity.CENTER_VERTICAL);
            bulk.setPadding(M3Page.dp(ctx,12),0,M3Page.dp(ctx,12),0);
            for(int i=0;i<2;i++) {
                final boolean all=i==0;
                MaterialButton b=new MaterialButton(ctx,null,com.google.android.material.R.attr.borderlessButtonStyle);
                b.setText(all?R.string.jm_meterask_all:R.string.jm_meterask_none);
                b.setAllCaps(false);
                b.setOnClickListener(v -> {
                    java.util.Arrays.fill(mine,all);
                    adapter.notifyDataSetChanged();
                    });
                bulk.addView(b,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
                }
            box.addView(bulk,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
            }

        ListView list=new ListView(ctx);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent); // the row has its own ripple
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent,row,pos,id) -> {
            if(pos<0||pos>=n)
                return;
            mine[pos]=!mine[pos];
            Object check=row.getTag();
            if(check instanceof MaterialCheckBox)
                ((MaterialCheckBox)check).setChecked(mine[pos]);
            });
        box.addView(list,new LinearLayout.LayoutParams(MATCH_PARENT,0,1f));

        final Runnable postpone=() -> {
            skip.add(name);
            if(later!=null)
                later.run();
            else // another meter may have readings waiting; posted: this dialog is still closing
                Applic.getHandler().post(() -> ask(MainActivity.thisone,null,skip,null));
            };
        final AlertDialog dialog=builder.setTitle(R.string.jm_meterask_title)
            .setView(box)
            .setPositiveButton(R.string.save,(d,w) -> {
                boolean any=false;
                for(boolean m:mine)
                    any=any||m;
                if(any&&Natives.getbloodvar()>=Natives.getLabels().size()-1) {
                    /* Nothing can be saved without a label for blood glucose: keep them waiting. */
                    Applic.argToaster(act,act.getString(R.string.specifyblood).replace("\"",""),android.widget.Toast.LENGTH_LONG);
                    if(later!=null)
                        later.run();
                    return;
                    }
                decide(name,recs,mine,skip);
                })
            .setNegativeButton(R.string.jm_meterask_later,(d,w) -> postpone.run())
            .setOnCancelListener(d -> postpone.run())
            .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            if(open==dialog) {
                open=null;
                openAct=null;
                }
            });
        open=dialog;
        openAct=act;
        dialog.show();
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"ask",th);
        }
    }

/* ---------- saving the decisions ---------- */

/* Save pressed.  The native saves run on a thread without a Looper, like the
 * Bluetooth callbacks they come from normally. */
private static void decide(String name,ArrayList<Rec> shown,boolean[] mine,HashSet<String> skip) {
    synchronized(lock) {
        busy=true;
        }
    new Thread(() -> {
        try {
            process(name,shown,mine);
            }
        catch(Throwable th) {
            Log.stack(LOG_ID,"process",th);
            }
        finally {
            synchronized(lock) {
                busy=false;
                }
            }
        Applic.RunOnUiThread(() -> {
            try {
                Dashboard.update();
                MeterList.refreshOpen();
                final Runnable r=changed;
                if(r!=null)
                    r.run();
                }
            catch(Throwable th) {
                Log.stack(LOG_ID,"after save",th);
                }
            /* Readings that arrived while the dialog was open, or another meter. */
            ask(MainActivity.thisone,null,skip,null);
            });
        },"MeterAsk").start();
    }

/*
 * Only the records that were shown are decided; one that arrived while the
 * dialog was open stays pending and is asked about next.
 */
private static void process(String name,ArrayList<Rec> shown,boolean[] mine) {
    synchronized(lock) {
        final State st=states().get(name);
        final int index=meterIndex(name);
        if(st==null||index<0) {
            Log.e(LOG_ID,"process: no meter "+index);
            return; // the readings stay pending
            }
        final GlucoseMeterGatt gatt=BluetoothGlucoseMeter.getExistingGatt(index);
        final int n=shown.size();
        final Integer[] order=new Integer[n];
        for(int i=0;i<n;i++)
            order[i]=i;
        java.util.Arrays.sort(order,(a,b) -> {
            final Rec x=shown.get(a),y=shown.get(b);
            return x.seq!=y.seq?Integer.compare(x.seq,y.seq):Long.compare(x.received,y.received);
            });
        int saved=0;
        for(int i:order) {
            final Rec rec=shown.get(i);
            if(!st.pending.containsKey(rec.key))
                continue;
            if(mine[i]) {
                try {
                    if(gatt!=null)
                        gatt.saveRecord(rec.raw);
                    else
                        Natives.GlucoseMeterSave(index,rec.raw); // meter not active now
                    saved++;
                    }
                catch(Throwable th) {
                    Log.stack(LOG_ID,"save",th);
                    continue; // stays pending, asked again
                    }
                }
            st.pending.remove(rec.key);
            st.decided.remove(rec.key);
            st.decided.put(rec.key,mine[i]);
            }
        final Iterator<String> it=st.decided.keySet().iterator();
        while(st.decided.size()>MAX_DECIDED&&it.hasNext()) {
            it.next();
            it.remove();
            }
        write(name,st,true);
        if(saved>0) {
            /* What followed the records of a sync before: the "records sent"
             * response, on which the native side calibrates with the last
             * blood value and wakes the backup; then the screens. */
            Natives.recordCharacteristicChanged(RACP_SUCCESS);
            if(gatt!=null) {
                gatt.newvalues=true;
                gatt.receivedTime=System.currentTimeMillis();
                gatt.updateview();
                }
            Applic.app.redraw();
            Applic.toGarmin(1);
            }
        }
    }
}
