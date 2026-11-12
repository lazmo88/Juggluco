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

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;

import tk.glucodata.m3.DataPickers;
import tk.glucodata.m3.M3Page;
import tk.glucodata.nums.item;
import tk.glucodata.nums.numio;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/* Phone list of all amounts (menu List).  Replaces the native-drawn list with
 * its column of six buttons.  Reads the stored amounts with the existing
 * read-only natives getfirstNum/getlastNum/getNumitem of both amount sources
 * (numio.numptrs: 1 = this phone, 0 = the other device), grouped per day,
 * newest first.  Tap opens AmountPage on the same hit as the old list did
 * (mkhitptr), the delete button removes it through the same path as
 * NumberView's Delete. */
final class AmountListPage {
private static final String LOG_ID="AmountListPage";

private static final class Entry {
    final int bron,pos;
    final long time; // ms
    final float value;
    final int label,mealptr;
    Entry(int bron,int pos,item it) {
        this.bron=bron;
        this.pos=pos;
        time=it.time*1000L;
        value=it.value;
        label=it.label;
        mealptr=it.mealptr;
        }
    }

private final MainActivity act;
private final M3Page page;
private final RecyclerView recycler;
private final View emptyView;
private final TextView emptyTitle;
private final Adapter adapter=new Adapter();
private ArrayList<String> labels=new ArrayList<>();
private ArrayList<Entry> all=new ArrayList<>();
private final ArrayList<Object> rows=new ArrayList<>(); // String day header or Entry
private int filter=-1; // label, -1 all
private int loadNr=0;

static void show(MainActivity act) {
    new AmountListPage(act);
    }

private AmountListPage(MainActivity act) {
    this.act=act;
    page=M3Page.open(act,R.string.amountsname);
    page.action(R.drawable.ic_jm_filter_list,act.getString(R.string.jm_data_filter),this::askFilter);
    page.action(R.drawable.ic_jm_calendar_month,act.getString(R.string.jm_data_gotodate),this::askDate);
    page.scroll.setVisibility(View.GONE);

    recycler=new RecyclerView(page.ctx);
    recycler.setLayoutManager(new LinearLayoutManager(page.ctx));
    recycler.setAdapter(adapter);
    recycler.setClipToPadding(false);
    recycler.setPadding(page.dp(16),0,page.dp(16),page.dp(96)+MainActivity.systembarBottom); // room for the FAB
    final int flat=page.color(com.google.android.material.R.attr.colorSurface);
    final int lifted=page.color(com.google.android.material.R.attr.colorSurfaceContainer);
    recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
        @Override public void onScrolled(@NonNull RecyclerView rv,int dx,int dy) {
            page.toolbar.setBackgroundColor(rv.canScrollVertically(-1)?lifted:flat);
            }
        });
    LinearLayout root=(LinearLayout)page.root;
    /* List, empty state and the New amount button share the space under the app bar. */
    android.widget.FrameLayout frame=new android.widget.FrameLayout(page.ctx);
    root.addView(frame,root.indexOfChild(page.scroll)+1,new LinearLayout.LayoutParams(MATCH_PARENT,0,1f));
    frame.addView(recycler,new android.widget.FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT));

    LinearLayout holder=new LinearLayout(page.ctx);
    holder.setOrientation(LinearLayout.VERTICAL);
    holder.setGravity(Gravity.CENTER);
    emptyView=page.empty(holder,R.drawable.ic_jm_list,act.getString(R.string.jm_data_noamounts),act.getString(R.string.jm_data_noamounts_body));
    emptyTitle=findTitle(emptyView);
    holder.setVisibility(View.GONE);
    frame.addView(holder,new android.widget.FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    holderView=holder;

    com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fab=
        new com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton(page.ctx);
    fab.setText(R.string.new_amount);
    fab.setIconResource(R.drawable.ic_jm_add);
    fab.setOnClickListener(v -> newAmount());
    android.widget.FrameLayout.LayoutParams fabParams=new android.widget.FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT,Gravity.BOTTOM|Gravity.END);
    fabParams.setMargins(0,0,page.dp(16),page.dp(16)+MainActivity.systembarBottom);
    frame.addView(fab,fabParams);
    recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
        @Override public void onScrolled(@NonNull RecyclerView rv,int dx,int dy) {
            if(dy>0) fab.shrink(); else if(dy<0) fab.extend();
            }
        });

    page.onClose(() -> {
        loadNr++;
        if(Menus.on)
            Menus.show(act);
        });
    load();
    }
private final View holderView;

private static TextView findTitle(View box) {
    if(box instanceof ViewGroup) {
        ViewGroup g=(ViewGroup)box;
        for(int i=0;i<g.getChildCount();i++)
            if(g.getChildAt(i) instanceof TextView)
                return (TextView)g.getChildAt(i);
        }
    return null;
    }

/* Reads all amounts off the UI thread; the stored values are passed on as
 * they are (no rounding, no conversion). */
private void load() {
    final int nr=++loadNr;
    final Handler main=new Handler(Looper.getMainLooper());
    new Thread(() -> {
        ArrayList<Entry> list=new ArrayList<>();
        ArrayList<String> labs=new ArrayList<>();
        try {
            labs=AmountPage.labelNames();
            for(int bron=0;bron<numio.numptrs.length;bron++) {
                final long ptr=numio.numptrs[bron];
                if(ptr==0L)
                    continue;
                final int first=Natives.getfirstNum(ptr);
                final int last=Natives.getlastNum(ptr);
                for(int pos=first;pos<last;pos++) {
                    if(nr!=loadNr)
                        return;
                    item it=Natives.getNumitem(ptr,pos);
                    if(it!=null)
                        list.add(new Entry(bron,pos,it));
                    }
                }
            Collections.sort(list,(a,b) -> Long.compare(b.time,a.time));
            }
        catch(Throwable th) {
            Log.stack(LOG_ID,"load",th);
            }
        final ArrayList<String> flabs=labs;
        main.post(() -> {
            if(nr!=loadNr||!page.isOpen())
                return;
            labels=flabs;
            all=list;
            rebuild();
            });
        },"AmountList").start();
    }

private void rebuild() {
    rows.clear();
    Calendar cal=Calendar.getInstance();
    int lastDay=-1;
    for(Entry e:all) {
        if(filter>=0&&e.label!=filter)
            continue;
        cal.setTimeInMillis(e.time);
        final int day=cal.get(Calendar.YEAR)*1000+cal.get(Calendar.DAY_OF_YEAR);
        if(day!=lastDay) {
            rows.add(DateFormat.getDateInstance(DateFormat.FULL).format(e.time));
            lastDay=day;
            }
        rows.add(e);
        }
    adapter.notifyDataSetChanged();
    final boolean empty=rows.isEmpty();
    if(emptyTitle!=null)
        emptyTitle.setText(filter>=0?act.getString(R.string.jm_data_nofilter):act.getString(R.string.jm_data_noamounts));
    if(emptyView!=null&&emptyView instanceof ViewGroup&&((ViewGroup)emptyView).getChildCount()>2)
        ((ViewGroup)emptyView).getChildAt(2).setVisibility(filter>=0?View.GONE:View.VISIBLE);
    holderView.setVisibility(empty?View.VISIBLE:View.GONE);
    recycler.setVisibility(empty?View.GONE:View.VISIBLE);
    page.subtitle(filter>=0&&filter<labels.size()?labels.get(filter):null);
    }

private String label(int l) {
    return l>=0&&l<labels.size()?labels.get(l):"?";
    }
/* Shortest text of the stored float, as Float.toString without a trailing .0 */
static String value(float v) {
    String s=Float.toString(v);
    if(s.endsWith(".0"))
        s=s.substring(0,s.length()-2);
    return s;
    }

private void askFilter() {
    String[] items=new String[labels.size()+1];
    items[0]=act.getString(R.string.jm_data_alllabels);
    for(int i=0;i<labels.size();i++)
        items[i+1]=labels.get(i);
    M3Page.dialog(act).setTitle(R.string.jm_data_filter)
        .setSingleChoiceItems(items,filter+1,(d,which) -> {
            filter=which-1;
            d.dismiss();
            rebuild();
            recycler.scrollToPosition(0);
            })
        .setNegativeButton(android.R.string.cancel,null)
        .show();
    }

private void askDate() {
    final long start=all.isEmpty()?System.currentTimeMillis():firstVisibleTime();
    DataPickers.date(act,act.getString(R.string.jm_data_gotodate),start,(year,month,day) -> {
        Calendar cal=Calendar.getInstance();
        cal.clear();
        cal.set(year,month,day,23,59,59);
        final long end=cal.getTimeInMillis();
        // newest first: the first row at or before the end of that day
        for(int i=0;i<rows.size();i++) {
            Object o=rows.get(i);
            if(o instanceof Entry&&((Entry)o).time<=end) {
                int target=(i>0&&rows.get(i-1) instanceof String)?i-1:i;
                ((LinearLayoutManager)recycler.getLayoutManager()).scrollToPositionWithOffset(target,0);
                return;
                }
            }
        if(!rows.isEmpty())
            recycler.scrollToPosition(rows.size()-1);
        },null);
    }
private long firstVisibleTime() {
    int p=((LinearLayoutManager)recycler.getLayoutManager()).findFirstVisibleItemPosition();
    for(int i=Math.max(p,0);i<rows.size();i++)
        if(rows.get(i) instanceof Entry)
            return ((Entry)rows.get(i)).time;
    return System.currentTimeMillis();
    }

private void newAmount() {
    if(Natives.staticnum()) {
        help.help(R.string.staticnum,act);
        return;
        }
    AmountPage.show(act,0L,-1,this::reloadIfOpen);
    }
private void edit(Entry e) {
    final long ptr=numio.numptrs[e.bron];
    AmountPage.show(act,Natives.mkhitptr(ptr,e.pos),-1,this::reloadIfOpen);
    }
private void reloadIfOpen() {
    if(page.isOpen())
        load();
    }
private void askDelete(Entry e) {
    final String mess=label(e.label)+" "+value(e.value)+"\n"+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(e.time);
    M3Page.confirm(act,act.getString(R.string.deletequestion),mess,act.getString(R.string.delete),() -> {
        final long ptr=numio.numptrs[e.bron];
        item now=Natives.getNumitem(ptr,e.pos);
        if(now==null||now.time*1000L!=e.time||now.label!=e.label) { // changed meanwhile
            load();
            return;
            }
        AmountPage.removeHit(Natives.mkhitptr(ptr,e.pos));
        page.toast(act.getString(R.string.jm_data_deleted));
        load();
        });
    }

private static final int HEADER=0,ITEM=1;

private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    @Override public int getItemViewType(int position) {
        return rows.get(position) instanceof String?HEADER:ITEM;
        }
    @Override public int getItemCount() {
        return rows.size();
        }
    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int type) {
        if(type==HEADER) {
            TextView head=new TextView(page.ctx);
            head.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            head.setTextColor(page.color(com.google.android.material.R.attr.colorPrimary));
            head.setPadding(page.dp(16),page.dp(20),page.dp(16),page.dp(8));
            head.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
            head.setAccessibilityHeading(true);
            return new RecyclerView.ViewHolder(head) {};
            }
        ViewGroup row=(ViewGroup)LayoutInflater.from(page.ctx).inflate(R.layout.material_settings_row,parent,false);
        row.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        TextView amount=new TextView(page.ctx);
        amount.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        amount.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
        amount.setId(R.id.jm_data_amount_value);
        amount.setPadding(page.dp(8),0,page.dp(4),0);
        MaterialButton del=new MaterialButton(page.ctx,null,com.google.android.material.R.attr.materialIconButtonStyle);
        del.setIconResource(R.drawable.ic_jm_delete);
        del.setIconTint(android.content.res.ColorStateList.valueOf(page.color(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        del.setId(R.id.jm_data_amount_delete);
        del.setContentDescription(act.getString(R.string.delete));
        LinearLayout end=new LinearLayout(page.ctx);
        end.setOrientation(LinearLayout.HORIZONTAL);
        end.setGravity(Gravity.CENTER_VERTICAL);
        end.addView(amount,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
        end.addView(del,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
        M3Page.end(row).addView(end);
        return new RecyclerView.ViewHolder(row) {};
        }
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position) {
        Object o=rows.get(position);
        if(o instanceof String) {
            ((TextView)holder.itemView).setText((String)o);
            return;
            }
        final Entry e=(Entry)o;
        View row=holder.itemView;
        ImageView icon=row.findViewById(R.id.jm_row_icon);
        icon.setVisibility(View.VISIBLE);
        icon.setImageResource(iconFor(e));
        M3Page.setTitle(row,label(e.label));
        String summary=NumberView.minhourstr(e.time);
        if(e.bron!=1)
            summary+=" · "+act.getString(R.string.jm_data_fromwatch);
        M3Page.setSummary(row,summary);
        ((TextView)row.findViewById(R.id.jm_data_amount_value)).setText(value(e.value));
        View del=row.findViewById(R.id.jm_data_amount_delete);
        del.setVisibility(Natives.staticnum()?View.GONE:View.VISIBLE);
        del.setOnClickListener(v -> askDelete(e));
        row.setOnClickListener(v -> edit(e));
        row.setContentDescription(label(e.label)+" "+value(e.value)+", "+summary);
        }
    }

private static int iconFor(Entry e) {
    if(e.label==Natives.getmealvar())
        return R.drawable.ic_jm_restaurant;
    if(e.label==Natives.getbloodvar())
        return R.drawable.ic_jm_water_drop;
    if((e.mealptr&0x80000000)!=0)
        return R.drawable.ic_jm_monitoring;
    return R.drawable.ic_jm_label;
    }
}
