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
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

import tk.glucodata.m3.M3Page;

/*
 * Opt-in, per-device review of readings from a shared Bluetooth glucose meter.
 * A row starts undecided; Add and Exclude are explicit choices. Draft changes
 * are persisted immediately, but only Save choices commits import permission
 * or a permanent identity-only exclusion. Later/cancel merely defers review.
 *
 * Presentation is persisted separately from the draft: automatic sync/resume
 * prompts contain only unseen pending identities. Explicit show() includes
 * all pending/drafts. A new sync cannot erase a draft or redisplay a deferred
 * identity. Sequence plus the seven base-time bytes identify each record,
 * scoped by the existing meter device-name key (never shared across devices).
 *
 * Exclusions are never evicted, and disabling review cannot release existing
 * exclusions or pending records. Brand-new records still follow the opt-in
 * setting. State/commit failures are held rather than falling through to the
 * caller's unconditional native save. Durable Add consent precedes native
 * import; interrupted Add work remains reviewable, not an offer of undo.
 *
 * State is in the existing private jm_meter_ask preferences. Existing on|,
 * pending| and decided| encodings are preserved; review| stores presentation
 * and draft metadata. All shared state is guarded by lock. Native cursor/
 * conversion bindings and durable import receipts are separate from ordinary
 * GATT semantics. Legacy unbound committed Adds are held for explicit recovery.
 */
public final class MeterAsk {
private MeterAsk() {}

private static final String LOG_ID="MeterAsk";
private static final String PREFS="jm_meter_ask";
private static final String ON="on|";
private static final String PENDING="pending|";
private static final String DECIDED="decided|";
private static final String REVIEW="review|";
private static final String BINDINGS="bindings|";
private static final String RESOLVED="resolved|";
/* Old unbound Adds are compared with saved readings up to a day either side. */
private static final int LEGACY_WINDOW=86400;
/* Shorter records are ignored by Natives.GlucoseMeterSave: leave them to it. */
private static final int MIN_LENGTH=14;
private static final long COMMIT_INTERVAL=1000L;

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

private static final class State extends MeterDecisionState<Rec> {
    boolean on=false;
    boolean loadFailed=false; // unreadable decisions must never become implicit permission
    }

/* ---------- record layout (Bluetooth Glucose Service) ---------- */

static int sequence(byte[] v) {
    return (v[1]&0xFF)|((v[2]&0xFF)<<8);
    }

/* Sequence number and the seven base time bytes. */
static String key(byte[] v) {
    return MeterDecisionState.identity(v);
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
        final String prefix=k.startsWith(ON)?ON:k.startsWith(PENDING)?PENDING:
            k.startsWith(DECIDED)?DECIDED:k.startsWith(REVIEW)?REVIEW:k.startsWith(BINDINGS)?BINDINGS:
            k.startsWith(RESOLVED)?RESOLVED:null;
        if(prefix==null)
            continue;
        final State st=get(map,k.substring(prefix.length()));
        try {
            if(prefix.equals(ON)&&v instanceof Boolean)
                st.on=(Boolean)v;
            else if(prefix.equals(PENDING)&&v instanceof String)
                readPending(st,(String)v);
            else if(prefix.equals(DECIDED)&&v instanceof String)
                st.readDecisions((String)v);
            else if(prefix.equals(REVIEW)&&v instanceof String)
                st.readReview((String)v);
            else if(prefix.equals(BINDINGS)&&v instanceof String)
                st.readBindings((String)v);
            else if(prefix.equals(RESOLVED)&&v instanceof String)
                st.readResolved((String)v);
            else
                throw new IllegalArgumentException("preference type");
            }
        catch(Throwable th) {
            st.loadFailed=true;
            Log.stack(LOG_ID,"load state",th);
            }
        }
    for(Map.Entry<String,State> meterState:map.entrySet()) {
        State st=meterState.getValue();
        try {
            for(String id:st.bindings.keySet()) {
                if(!Boolean.TRUE.equals(st.decided.get(id))) throw new IllegalArgumentException("binding without consent");
                Rec rec=st.pending.get(id);
                byte[] binding=MeterImportBinding.decode(st.bindings.get(id),id,rec==null?null:rec.raw);
                MeterImportBinding.requireDevice(binding,meterState.getKey());
            }
        }
        catch(RuntimeException badBinding) { st.loadFailed=true; Log.stack(LOG_ID,"load binding",badBinding); }
        // Migration/order-independent reconciliation: never retain raw excluded values.
        st.pending.keySet().removeIf(id -> Boolean.FALSE.equals(st.decided.get(id)));
        st.drafts.keySet().retainAll(st.pending.keySet());
        st.presented.retainAll(st.pending.keySet());
        }
    states=map;
    flags();
    loaded=true;
    return map;
    }

/* Call with lock held. */
private static void flags() {
    boolean pending=false;
    for(State st:states.values()) {
        pending=pending||!st.pending.isEmpty();
        }
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
    if(text.isEmpty())
        return;
    for(String line:text.split("\n")) {
        final int comma=line.indexOf(',');
        if(comma<=0)
            throw new IllegalArgumentException("pending entry");
        final byte[] raw=Base64.decode(line.substring(comma+1),Base64.NO_WRAP);
        if(raw.length<MIN_LENGTH)
            throw new IllegalArgumentException("short pending");
        final Rec rec=new Rec(raw,Long.parseLong(line.substring(0,comma)));
        st.pending.put(rec.key,rec);
        }
    }

/* Call with lock held.  sync: on disk before returning. */
private static boolean write(String name,State st,boolean sync) {
    return write(name,st,st,sync);
    }

private static boolean write(String name,State actual,MeterDecisionState<Rec> st,boolean sync) {
    if(actual.loadFailed)
        return false; // never overwrite unreadable decisions with an incomplete map
    SharedPreferences.Editor edit=prefs().edit();
    edit.putBoolean(ON+name,actual.on);
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
    else
        edit.putString(DECIDED+name,st.decisionsText());
    edit.putString(REVIEW+name,st.reviewText());
    edit.putString(BINDINGS+name,st.bindingsText());
    edit.putString(RESOLVED+name,st.resolvedText());
    if(sync) {
        lastCommit=System.currentTimeMillis();
        if(edit.commit()) {
            unflushed=false;
            actual.storageFailed=false;
            return true;
            }
        unflushed=true;
        actual.storageFailed=true;
        Log.e(LOG_ID,"commit failed");
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
            final boolean previous=st.on;
            st.on=on;
            try {
                if(!write(name,st,true))
                    st.on=previous;
                }
            catch(Throwable th) {
                st.on=previous;
                st.storageFailed=true;
                throw th;
                }
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

/** Committed identity-only exclusions; they are never aged out. */
public static int excludedCount(String name) {
    try {
        synchronized(lock) {
            State st=states().get(name);
            if(st==null) return 0;
            int count=0;
            for(Boolean add:st.decided.values()) if(!add) count++;
            return count;
            }
        }
    catch(Throwable th) { Log.stack(LOG_ID,"excludedCount",th); return 0; }
    }

/** Draft selections are not committed permission to save or export. */
public static int draftCount(String name) {
    try {
        synchronized(lock) {
            State st=states().get(name);
            if(st==null) return 0;
            int count=0;
            for(String key:st.drafts.keySet()) if(!st.decided.containsKey(key)) count++;
            return count;
            }
        }
    catch(Throwable th) { Log.stack(LOG_ID,"draftCount",th); return 0; }
    }

public static boolean storageProblem(String name) {
    try {
        synchronized(lock) {
            State st=states().get(name);
            return st!=null&&(st.loadFailed||st.storageFailed);
            }
        }
    catch(Throwable th) { Log.stack(LOG_ID,"storageProblem",th); return true; }
    }

private static void storageError(MainActivity act) {
    // Old unbound Adds are not a storage error: they have their own resolve step in the dialog.
    Applic.argToaster(act,act.getString(R.string.jm_meterask_storage_error),android.widget.Toast.LENGTH_LONG);
    }

/* ---------- old unbound Adds ---------- */

/* Native read-only search for one waiting old Add; null when it cannot be checked now. */
private static long[] legacyMatches(String name,Rec rec) {
    final int index=meterIndex(name);
    if(index<0) return null;
    return Natives.GlucoseMeterLegacyMatches(index,rec.raw,LEGACY_WINDOW);
    }

/* Call with lock held. Settles old Adds already in the log at the same time. */
private static int autoResolve(String name,State st) {
    if(st==null||st.loadFailed||!st.hasLegacyAdds()) return 0;
    final int n=st.autoResolve((id,rec) -> legacyMatches(name,rec),proposed -> write(name,st,proposed,true));
    if(n>0) {
        flags();
        Log.i(LOG_ID,"auto-resolved "+n+" earlier Add(s) already in the log");
        }
    return n;
    }

/* What the resolve row says, from a fresh read-only search. */
private static String legacyText(Context ctx,long[] found) {
    if(found==null)
        return ctx.getString(R.string.jm_meterask_legacy_unknown);
    if(found.length<4)
        return ctx.getString(R.string.jm_meterask_legacy_absent);
    long nearest=found[1];
    for(int i=1;i<found.length;i+=3) if(Math.abs(found[i]-found[0])<Math.abs(nearest-found[0])) nearest=found[i];
    final long ms=nearest*1000L;
    return ctx.getString(R.string.jm_meterask_legacy_found,SensorUi.date(ctx,ms)+"  "+SensorUi.time(ctx,ms));
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
 * save it like before (unseen record on a device with the option off, or a short record);
 * true when it was handled here.
 */
static boolean received(GlucoseMeterGatt gatt,byte[] value) {
    if(value==null||value.length<MIN_LENGTH)
        return false; // native ignores incomplete records
    String name=null;
    try {
        load();
        name=gatt.getDeviceName();
        if(name==null)
            return true; // cannot safely scope a remembered exclusion
        synchronized(lock) {
            final State st=states().get(name);
            if(st==null)
                return false; // opt-in: this device has never been configured
            if(st.loadFailed)
                return true;
            final String id=key(value);
            switch(st.route(id,st.on)) {
                case BYPASS: return false;
                case HOLD: return true;
                case DONE: return true; // settled as already in the log; never re-added
                case ADD:
                    // A remembered Add must use the receipt path too, not the
                    // ordinary GATT cursor gate. Stage raw retry DURABLY first.
                    final Rec accepted=new Rec(value.clone(),System.currentTimeMillis());
                    st.pending.put(id,accepted);
                    flags();
                    if(write(name,st,true))
                        process(name,new ArrayList<>(Collections.singletonList(accepted)));
                    return true;
                case NEW:
                    st.pending.put(id,new Rec(value.clone(),System.currentTimeMillis()));
                    flags();
                    final long now=System.currentTimeMillis();
                    write(name,st,now-lastCommit>=COMMIT_INTERVAL||now<lastCommit);
                    return true;
                default: return true;
                }
            }
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"received (held)",th);
        // The caller saves on false. Storage/identity failures must NEVER use that fallback.
        if(name!=null&&states!=null) {
            synchronized(lock) {
                State st=states.get(name);
                if(st!=null) st.storageFailed=true;
                }
            }
        return true;
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
        synchronized(lock) {
            for(Map.Entry<String,State> e:states().entrySet()) autoResolve(e.getKey(),e.getValue());
            }
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
    if(act==null)return;
    try {
        synchronized(lock) { autoResolve(name,states().get(name)); }
    }
    catch(Throwable failure) { Log.stack(LOG_ID,"show state",failure); storageError(act); return; }
    ask(act,name,new HashSet<>(),later,true);
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
private static String nextMeter(String first,HashSet<String> skip,boolean explicit) {
    final HashMap<String,State> map=states();
    if(explicit&&first!=null) {
        State st=map.get(first);
        return st!=null&&!st.pending.isEmpty()&&!st.loadFailed&&meterIndex(first)>=0?first:null;
        }
    final int n=Natives.GlucoseMeterCount();
    String found=null;
    for(int i=0;i<n;i++) {
        final String name=Natives.GlucoseMeterDeviceName(i);
        if(name==null||skip.contains(name))
            continue;
        final State st=map.get(name);
        if(st==null||st.loadFailed||(explicit?st.pending.isEmpty():!st.hasUnseen()))
            continue;
        if(name.equals(first))
            return name;
        if(found==null)
            found=name;
        }
    return found;
    }

private static void ask(MainActivity act,String first,HashSet<String> skip,Runnable later) {
    ask(act,first,skip,later,false);
    }

private static void ask(MainActivity act,String first,HashSet<String> skip,Runnable later,boolean explicit) {
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
            name=nextMeter(first,skip,explicit);
            if(name==null)
                return;
            final State st=states().get(name);
            recs=new ArrayList<>();
            for(String id:st.reviewKeys(explicit))
                recs.add(st.pending.get(id));
            }
        /* Newest first. */
        Collections.sort(recs,(a,b) -> a.time!=b.time?Long.compare(b.time,a.time):Integer.compare(b.seq,a.seq));
        final int n=recs.size();
        final Boolean[] choices=new Boolean[n]; // null means not chosen, NOT implicitly excluded
        final boolean[] legacy=new boolean[n]; // accepted by an older version, needs a resolve choice
        final long[][] found=new long[n][]; // read-only search for those rows
        int legacyRows=0;
        synchronized(lock) {
            final State st=states().get(name);
            for(int i=0;i<n;i++) {
                choices[i]=st.drafts.get(recs.get(i).key);
                legacy[i]=st.isLegacy(recs.get(i).key);
                if(legacy[i]) { legacyRows++; found[i]=legacyMatches(name,recs.get(i)); }
                }
            }
        final boolean onlyLegacy=legacyRows==n;

        final MaterialAlertDialogBuilder builder=M3Page.dialog(act);
        final Context ctx=M3Page.themed(act);
        final int onSurface=M3Page.color(ctx,com.google.android.material.R.attr.colorOnSurface);
        final int onSurfaceVariant=M3Page.color(ctx,com.google.android.material.R.attr.colorOnSurfaceVariant);

        LinearLayout box=new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0,M3Page.dp(ctx,8),0,0);

        TextView message=new TextView(ctx);
        message.setText(onlyLegacy?R.string.jm_meterask_legacy_message:R.string.jm_meterask_message);
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

        final AlertDialog[] self={null};
        final BaseAdapter adapter=new BaseAdapter() {
            @Override public int getCount() { return n; }
            @Override public Object getItem(int pos) { return recs.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int pos) { return legacy[pos]?1:0; }
            @Override
            public View getView(int pos,View convert,ViewGroup parent) {
                if(legacy[pos])
                    return legacyRow(act,ctx,name,recs.get(pos),found[pos],(LinearLayout)convert,onSurface,onSurfaceVariant,self,later);
                LinearLayout row=(LinearLayout)convert;
                if(row==null) {
                    row=new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPaddingRelative(M3Page.dp(ctx,24),M3Page.dp(ctx,8),M3Page.dp(ctx,16),M3Page.dp(ctx,8));
                    TextView value=new TextView(ctx);
                    value.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
                    value.setTextColor(onSurface);
                    row.addView(value);
                    TextView time=new TextView(ctx);
                    time.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
                    time.setTextColor(onSurfaceVariant);
                    row.addView(time);
                    RadioGroup group=new RadioGroup(ctx);
                    group.setOrientation(RadioGroup.HORIZONTAL);
                    for(int i=0;i<2;i++) {
                        MaterialRadioButton button=new MaterialRadioButton(ctx);
                        button.setId(View.generateViewId());
                        button.setText(i==0?R.string.jm_meterask_add:R.string.jm_meterask_exclude);
                        group.addView(button,new RadioGroup.LayoutParams(0,WRAP_CONTENT,1f));
                        }
                    row.addView(group,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
                    }
                final Rec rec=recs.get(pos);
                ((TextView)row.getChildAt(0)).setText(valueText(ctx,rec));
                final boolean committed;
                synchronized(lock) {
                    committed=Boolean.TRUE.equals(states().get(name).decided.get(rec.key));
                    }
                ((TextView)row.getChildAt(1)).setText(timeText(ctx,rec)+(committed?"\n"+ctx.getString(R.string.jm_meterask_add_committed):""));
                RadioGroup group=(RadioGroup)row.getChildAt(2);
                group.setOnCheckedChangeListener(null);
                group.getChildAt(0).setEnabled(!committed);
                group.getChildAt(1).setEnabled(!committed);
                if(choices[pos]==null)
                    group.clearCheck();
                else
                    group.check(group.getChildAt(choices[pos]?0:1).getId());
                group.setOnCheckedChangeListener((buttons,id) -> {
                    if(id==View.NO_ID) return;
                    final boolean add=id==buttons.getChildAt(0).getId();
                    boolean ok;
                    synchronized(lock) {
                        final State st=states().get(name);
                        ok=st!=null&&st.draft(rec.key,add,proposed -> write(name,st,proposed,true));
                        if(ok) choices[pos]=add;
                        }
                    if(!ok) storageError(act);
                    notifyDataSetChanged();
                    });
                return row;
                }
            };

        if(n-legacyRows>=3) {
            LinearLayout bulk=new LinearLayout(ctx);
            bulk.setGravity(Gravity.CENTER_VERTICAL);
            bulk.setPadding(M3Page.dp(ctx,12),0,M3Page.dp(ctx,12),0);
            for(int i=0;i<2;i++) {
                final boolean all=i==0;
                MaterialButton b=new MaterialButton(ctx,null,com.google.android.material.R.attr.borderlessButtonStyle);
                b.setText(all?R.string.jm_meterask_all:R.string.jm_meterask_none);
                b.setAllCaps(false);
                b.setOnClickListener(v -> {
                    boolean ok;
                    synchronized(lock) {
                        final State st=states().get(name);
                        ArrayList<String> keys=new ArrayList<>();
                        for(int row=0;row<n;row++) if(!legacy[row]) keys.add(recs.get(row).key);
                        ok=st!=null&&st.draft(keys,all,proposed -> write(name,st,proposed,true));
                        if(ok) for(int row=0;row<n;row++) choices[row]=st.drafts.get(recs.get(row).key);
                        }
                    if(!ok) storageError(act);
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

        box.addView(list,new LinearLayout.LayoutParams(MATCH_PARENT,0,1f));

        final Runnable postpone=() -> {
            skip.add(name);
            if(later!=null)
                later.run();
            else // another meter may have readings waiting; posted: this dialog is still closing
                Applic.getHandler().post(() -> ask(MainActivity.thisone,null,skip,null));
            };
        final AlertDialog dialog=builder.setTitle(onlyLegacy?R.string.jm_meterask_legacy_title:R.string.jm_meterask_title)
            .setView(box)
            .setPositiveButton(onlyLegacy?R.string.jm_meterask_legacy_done:R.string.jm_meterask_save_choices,null)
            .setNegativeButton(R.string.jm_meterask_later,(d,w) -> postpone.run())
            .setOnCancelListener(d -> postpone.run())
            .create();
        self[0]=dialog;
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
        // Mark exactly this presentation durably. Later/cancel just defers; it does not erase drafts.
        synchronized(lock) {
            final State st=states().get(name);
            ArrayList<String> keys=new ArrayList<>();
            for(Rec rec:recs) keys.add(rec.key);
            if(!st.present(keys,proposed -> write(name,st,proposed,true)))
                storageError(act);
            }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if(onlyLegacy) { // nothing to commit here; each row resolves on its own
                dialog.dismiss();
                postpone.run();
                return;
                }
            boolean anyChoice=false,anyAdd=false;
            for(Boolean choice:choices) {
                anyChoice|=choice!=null;
                anyAdd|=Boolean.TRUE.equals(choice);
                }
            if(!anyChoice) {
                Applic.argToaster(act,act.getString(R.string.jm_meterask_choose_first),android.widget.Toast.LENGTH_LONG);
                return;
                }
            if(anyAdd&&Natives.getbloodvar()>=Natives.getLabels().size()-1) {
                Applic.argToaster(act,act.getString(R.string.specifyblood).replace("\"",""),android.widget.Toast.LENGTH_LONG);
                return;
                }
            decide(name,recs,skip);
            dialog.dismiss();
            });
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"ask",th);
        }
    }

/* ---------- resolving an Add an older version accepted ---------- */

/* One row: the reading, what the log holds near it, and buttons that act at once.
 * found: fresh read-only search; null when it could not be checked. */
private static View legacyRow(MainActivity act,Context ctx,String name,Rec rec,long[] found,LinearLayout row,
        int onSurface,int onSurfaceVariant,AlertDialog[] dialog,Runnable later) {
    if(row==null) {
        row=new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPaddingRelative(M3Page.dp(ctx,24),M3Page.dp(ctx,8),M3Page.dp(ctx,16),M3Page.dp(ctx,12));
        TextView value=new TextView(ctx);
        value.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        value.setTextColor(onSurface);
        row.addView(value);
        TextView status=new TextView(ctx);
        status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        status.setTextColor(onSurfaceVariant);
        row.addView(status);
        LinearLayout buttons=new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        for(int i=0;i<2;i++) {
            MaterialButton b=new MaterialButton(ctx,null,i==0?com.google.android.material.R.attr.materialButtonOutlinedStyle:com.google.android.material.R.attr.borderlessButtonStyle);
            b.setAllCaps(false);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT);
            lp.setMarginEnd(M3Page.dp(ctx,8));
            buttons.addView(b,lp);
            }
        row.addView(buttons,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        }
    ((TextView)row.getChildAt(0)).setText(valueText(ctx,rec)+"  ·  "+timeText(ctx,rec));
    ((TextView)row.getChildAt(1)).setText(legacyText(ctx,found));
    final LinearLayout buttons=(LinearLayout)row.getChildAt(2);
    final MaterialButton first=(MaterialButton)buttons.getChildAt(0),second=(MaterialButton)buttons.getChildAt(1);
    final boolean unknown=found==null;
    final boolean inLog=!unknown&&found.length>=4;
    buttons.setVisibility(unknown?View.GONE:View.VISIBLE);
    // In the log: keeping the saved one is the safe default. Not in the log: adding it is.
    first.setText(inLog?R.string.jm_meterask_legacy_keep:R.string.jm_meterask_legacy_add);
    second.setText(inLog?R.string.jm_meterask_legacy_add_also:R.string.jm_meterask_legacy_discard);
    first.setOnClickListener(v -> resolve(act,name,rec,inLog?Resolution.KEEP:Resolution.ADD,dialog,later));
    second.setOnClickListener(v -> resolve(act,name,rec,inLog?Resolution.ADD:Resolution.DISCARD,dialog,later));
    return row;
    }

private enum Resolution { KEEP, ADD, DISCARD }

private static void resolve(MainActivity act,String name,Rec rec,Resolution how,AlertDialog[] dialog,Runnable later) {
    if(how==Resolution.ADD&&Natives.getbloodvar()>=Natives.getLabels().size()-1) {
        Applic.argToaster(act,act.getString(R.string.specifyblood).replace("\"",""),android.widget.Toast.LENGTH_LONG);
        return;
        }
    synchronized(lock) {
        if(busy) return;
        busy=true;
        }
    new Thread(() -> {
        boolean ok=false;
        try {
            synchronized(lock) {
                final State st=states().get(name);
                final int index=meterIndex(name);
                if(st!=null&&!st.loadFailed&&index>=0) {
                    final MeterDecisionState.Persistence<Rec> disk=proposed -> write(name,st,proposed,true);
                    switch(how) {
                        case KEEP: ok=st.resolveAlreadySaved(rec.key,disk); break;
                        case DISCARD: ok=st.resolveDiscard(rec.key,disk); break;
                        case ADD:
                            ok=st.resolveAddNow(rec.key,disk,(id,r) -> {
                                byte[] binding=Natives.GlucoseMeterPrepareAccepted(index,r.raw);
                                if(binding==null) return null;
                                String encoded=MeterImportBinding.encode(binding,id,r.raw);
                                MeterImportBinding.requireDevice(binding,name);
                                return encoded;
                                },(id,r) -> Natives.GlucoseMeterImportAccepted(index,r.raw,MeterImportBinding.decode(st.bindings.get(id),id,r.raw)));
                            if(ok) Applic.app.redraw();
                            break;
                        }
                    }
                flags();
                }
            }
        catch(Throwable th) { Log.stack(LOG_ID,"resolve",th); }
        finally { synchronized(lock) { busy=false; } }
        final boolean done=ok;
        Applic.RunOnUiThread(() -> {
            try {
                if(!done) storageError(act);
                final AlertDialog d=dialog[0];
                if(d!=null&&d.isShowing()) d.dismiss();
                Dashboard.update();
                MeterList.refreshOpen();
                final Runnable r=changed;
                if(r!=null) r.run();
                }
            catch(Throwable th) { Log.stack(LOG_ID,"after resolve",th); }
            // Reopen with what is left. Nothing left: the page's onChanged handles it.
            final HashSet<String> skip=new HashSet<>();
            boolean more;
            synchronized(lock) { State st=states().get(name); more=st!=null&&!st.pending.isEmpty(); }
            if(more) ask(MainActivity.thisone,name,skip,later,true);
            });
        },"MeterResolve").start();
    }

/* ---------- saving the decisions ---------- */

/* Save pressed.  The native saves run on a thread without a Looper, like the
 * Bluetooth callbacks they come from normally. */
private static void decide(String name,ArrayList<Rec> shown,HashSet<String> skip) {
    synchronized(lock) {
        busy=true;
        }
    new Thread(() -> {
        boolean success=false;
        try {
            success=process(name,shown);
            }
        catch(Throwable th) {
            Log.stack(LOG_ID,"process",th);
            }
        finally {
            synchronized(lock) {
                busy=false;
                }
            }
        final boolean finished=success;
        Applic.RunOnUiThread(() -> {
            try {
                if(!finished) {
                    skip.add(name);
                    final MainActivity act=MainActivity.thisone;
                    if(act!=null) storageError(act);
                    }
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
private static boolean process(String name,ArrayList<Rec> shown) {
    synchronized(lock) {
        final State st=states().get(name);
        final int index=meterIndex(name);
        if(st==null||st.loadFailed||index<0) {
            Log.e(LOG_ID,"process: unavailable meter state");
            return false;
            }
        final GlucoseMeterGatt gatt=BluetoothGlucoseMeter.getExistingGatt(index);
        final ArrayList<Rec> ordered=new ArrayList<>(shown);
        // Order is cosmetic. Existing bindings are NEVER replaced by preparation.
        final java.util.HashMap<Rec,Long> times=new java.util.HashMap<>();
        for(Rec rec:ordered) times.put(rec,rec.time/1000);
        Collections.sort(ordered,(x,y) -> {
            int cmp=Long.compare(times.get(x),times.get(y));
            return cmp!=0?cmp:Long.compare(x.received,y.received);
            });
        final ArrayList<String> keys=new ArrayList<>();
        for(Rec rec:ordered) keys.add(rec.key);
        final int[] saved={0};
        final boolean success;
        try {
            success=st.commit(keys,proposed -> write(name,st,proposed,true),(id,rec) -> {
                byte[] binding=Natives.GlucoseMeterPrepareAccepted(index,rec.raw);
                if(binding==null) return null;
                String encoded=MeterImportBinding.encode(binding,id,rec.raw);
                MeterImportBinding.requireDevice(binding,name);
                return encoded;
                },(id,rec) -> {
                final byte[] binding=MeterImportBinding.decode(st.bindings.get(id),id,rec.raw);
                final int receipt=Natives.GlucoseMeterImportAccepted(index,rec.raw,binding);
                if(MeterDecisionState.importConfirmed(receipt)) saved[0]++;
                return receipt;
                });
            }
        finally {
            flags();
            }
        if(saved[0]>0) {
            // Do not synthesize RACP success: that runs calibrateLast on a
            // historical review import. Native receipt already wakes observers.
            if(gatt!=null) {
                gatt.newvalues=true;
                gatt.receivedTime=System.currentTimeMillis();
                gatt.updateview();
                }
            Applic.app.redraw();
            Applic.toGarmin(1);
            }
        return success;
        }
    }
}
