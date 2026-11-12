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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import java.util.Random;

import tk.glucodata.m3.M3Page;

/* The built-in food database (phone, Material 3): search and browse foods
 * (old Meal.fooddatabase) and the nutrients of one food (old
 * Meal.shownutrients), whose Select copies name, carbohydrate per gram and
 * unit into the ingredient editor through give. */
final class FoodPage {
private FoodPage() {}
private static final Random random=new Random();
private interface HitSet { void accept(long ptr); }

static final class Adapter extends RecyclerView.Adapter<MealDatabaseViewHolder> {
    final M3Page page;
    final long[] hitptr; // 0: every food by id, else a Natives.foodsearch result
    final TriConsumer<String,Float,String> give;
    Adapter(M3Page page,long[] hitptr,TriConsumer<String,Float,String> give) {
        this.page=page;
        this.hitptr=hitptr;
        this.give=give;
        }
    @NonNull
    @Override
    public MealDatabaseViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int viewType) {
        ViewGroup row=(ViewGroup)LayoutInflater.from(page.ctx).inflate(R.layout.material_settings_row,parent,false);
        row.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        ((android.widget.ImageView)row.findViewById(R.id.jm_row_icon)).setImageResource(R.drawable.ic_jm_nutrition);
        return new MealDatabaseViewHolder(row,this);
        }
    @Override
    public void onBindViewHolder(@NonNull final MealDatabaseViewHolder holder,int pos) {
        String label=hitptr[0]==0L?Natives.idfoodlabel(pos):Natives.foodlabel(hitptr[0],pos);
        M3Page.setTitle(holder.itemView,label==null?"":label);
        }
    @Override
    public int getItemCount() {
        if(hitptr[0]==0L)
            return Natives.foodnr();
        return Natives.foodhitnr(hitptr[0]);
        }
    void open(int pos) {
        final int id=hitptr[0]==0L?pos:Natives.getfoodid(hitptr[0],pos);
        nutrients(page.act,id,false,give,page);
        }
    }

static void database(MainActivity act,TriConsumer<String,Float,String> give) {
    final M3Page page=M3Page.open(act,R.string.jm_meal_fooddatabase);
    page.help(R.string.nutrients);
    final long[] hitptr={0L};
    final MealPage.ListParts parts=MealPage.listPage(page,false);
    final Adapter adapter=new Adapter(page,hitptr,give);
    parts.recycler.setAdapter(adapter);
    final View noData=page.empty(parts.emptyHolder,R.drawable.ic_jm_menu_book,act.getString(R.string.jm_meal_nofoods),null);
    final View noMatch=page.empty(parts.emptyHolder,R.drawable.ic_jm_search_off,act.getString(R.string.jm_meal_nofoodmatch),act.getString(R.string.jm_meal_nofoodmatch_body));
    final Runnable showEmpty=() -> {
        final boolean none=adapter.getItemCount()==0;
        parts.emptyHolder.setVisibility(none?View.VISIBLE:View.GONE);
        parts.recycler.setVisibility(none?View.GONE:View.VISIBLE);
        noData.setVisibility(hitptr[0]==0L?View.VISIBLE:View.GONE);
        noMatch.setVisibility(hitptr[0]==0L?View.GONE:View.VISIBLE);
        };
    /* Replace the shown search result; the previous one is freed (the old
     * screen only freed the last one, on close). */
    final HitSet setHits=ptr -> {
        final long old=hitptr[0];
        hitptr[0]=ptr;
        adapter.notifyDataSetChanged();
        parts.recycler.scrollToPosition(0);
        if(old!=0L&&old!=ptr)
            Natives.freefoodptr(old);
        showEmpty.run();
        };
    final TextInputEditText[] search={null};
    /* Search as before: on the keyboard's Search key (or the search icon). */
    search[0]=MealPage.searchField(page,parts.header,act.getString(R.string.jm_meal_searchfoods),() ->
        setHits.accept(Natives.foodsearch(M3Page.text(search[0]))));
    /* Clearing the field shows the whole database again. */
    search[0].addTextChangedListener(new MealPage.Watcher(ed -> {
        if(ed.length()==0&&hitptr[0]!=0L)
            setHits.accept(0L);
        }));
    page.onClose(() -> {
        Natives.freefoodptr(hitptr[0]);
        hitptr[0]=0L;
        });
    final int fnr=Natives.foodnr();
    if(fnr>0)
        parts.recycler.scrollToPosition(random.nextInt(fnr)); // as before: start somewhere in the database
    showEmpty.run();
    }

/* Composition of 100 gram of food id; Select gives (label, carbohydrate per
 * gram, unit) and closes this page and the database (Meal.shownutrients). */
static void nutrients(MainActivity act,int id,boolean showzero,TriConsumer<String,Float,String> give,M3Page database) {
    final String label=Natives.idfoodlabel(id);
    final int[] ingr=Natives.getcomponents(id);
    final String[] compnames=Meal.compnames;
    final String[] compunits=Meal.compunits;
    final M3Page page=M3Page.open(act,R.string.jm_meal_nutrients);
    page.help(R.string.nutrients);
    TextView head=new TextView(page.ctx);
    head.setText(label);
    head.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
    head.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
    head.setPadding(page.dp(16),page.dp(8),page.dp(16),0);
    head.setAccessibilityHeading(true);
    page.custom(null,head);
    LinearLayout sec=page.section(R.string.compositionof100gram);
    final boolean[] zero={showzero};
    final Runnable fill=() -> {
        sec.removeAllViews();
        if(ingr==null||compnames==null||compunits==null)
            return;
        for(int i=0;i<ingr.length&&i<compnames.length;i++) {
            if((zero[0]&&ingr[i]!=-1)||ingr[i]>0) {
                // -3 trace, -2 unknown, -1 empty, else value*1000
                String strval;
                switch(ingr[i]) {
                    case -3: strval=act.getString(R.string.trace);break;
                    case -2: strval=act.getString(R.string.unknown);break;
                    default: {
                        float val=(float)ingr[i]/1000.0f;
                        strval=val<0.1f?Float.toString(val):Meal.ondecimal(val,10);
                        }
                    }
                if(ingr[i]>=0&&i<compunits.length&&compunits[i]!=null)
                    strval=strval+" "+compunits[i];
                ViewGroup row=page.item(sec,0,compnames[i],null,null);
                MealPage.endText(page,row).setText(strval);
                row.setContentDescription(compnames[i]+" "+strval);
                }
            }
        };
    fill.run();
    LinearLayout opts=page.section((CharSequence)null);
    page.toggle(opts,R.drawable.ic_jm_visibility,act.getString(R.string.showzero),null,() -> zero[0],on -> {
        zero[0]=on;
        fill.run();
        });
    page.bottomBar(page.button(M3Page.Style.FILLED,act.getString(R.string.select),0,() -> {
        if(ingr==null||ingr.length==0||compunits==null||compunits.length==0)
            return;
        page.dismiss();
        if(database!=null)
            database.dismiss();
        give.accept(label,ingr[0]/100000.0f,compunits[0]);
        }));
    }
}
