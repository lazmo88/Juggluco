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
import static tk.glucodata.MealPage.label;

import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;

import tk.glucodata.m3.M3Page;

/* Ingredients of meals (phone, Material 3): the list to choose one from
 * (old Meal.selectingredient with its search and Edit mode) and the editor
 * (old Meal.defineingredient).  Same Natives calls as before. */
final class IngredientPage {
private IngredientPage() {}

/* Choose an ingredient: setindex(i) with the chosen ingredient, or
 * setindex(-i-1) when ingredient i was deleted from the editor. */
static void select(MainActivity act,IntConsumer setindex) {
    final M3Page page=M3Page.open(act,R.string.jm_meal_ingredients);
    page.subtitle(act.getString(R.string.jm_meal_chooseingredient));
    page.help(R.string.mealhelp);
    final MealPage.ListParts parts=MealPage.listPage(page,true);
    final Adapter adapter=new Adapter(page,setindex);
    parts.recycler.setAdapter(adapter);
    final View noData=page.empty(parts.emptyHolder,R.drawable.ic_jm_grocery,act.getString(R.string.jm_meal_noingredients),act.getString(R.string.jm_meal_noingredients_body));
    final View noMatch=page.empty(parts.emptyHolder,R.drawable.ic_jm_search_off,act.getString(R.string.jm_meal_nomatch),null);
    final TextInputEditText[] search={null};
    final Runnable showEmpty=() -> {
        final boolean none=adapter.getItemCount()==0;
        final boolean searching=adapter.results!=null;
        parts.emptyHolder.setVisibility(none?View.VISIBLE:View.GONE);
        parts.recycler.setVisibility(none?View.GONE:View.VISIBLE);
        noData.setVisibility(searching?View.GONE:View.VISIBLE);
        noMatch.setVisibility(searching?View.VISIBLE:View.GONE);
        };
    /* Natives.searchIngredient is a regular expression search; null means an
     * invalid pattern (was the toast "Regex error, try again"). */
    final Runnable apply=() -> {
        final String str=M3Page.text(search[0]);
        if(str.isEmpty()) {
            M3Page.error(search[0],null);
            adapter.setResults(null);
            }
        else {
            int[] res=Natives.searchIngredient(str);
            if(res==null) {
                M3Page.error(search[0],act.getString(R.string.jm_meal_badpattern));
                return;
                }
            M3Page.error(search[0],null);
            adapter.setResults(res);
            }
        showEmpty.run();
        };
    search[0]=MealPage.searchField(page,parts.header,act.getString(R.string.jm_meal_searchingredients),apply);
    search[0].addTextChangedListener(new MealPage.Watcher(ed -> apply.run()));
    adapter.refresh=() -> {
        apply.run();
        showEmpty.run();
        };
    page.bottomBar(page.button(M3Page.Style.FILLED,act.getString(R.string.jm_meal_newingredient),R.drawable.ic_jm_add,() ->
        define(act,-1,setindex,() -> {
            adapter.refresh.run();
            if(adapter.getItemCount()>0)
                parts.recycler.scrollToPosition(adapter.getItemCount()-1);
            })));
    showEmpty.run();
    }

static final class Adapter extends RecyclerView.Adapter<IngredientViewHolder> {
    final M3Page page;
    final IntConsumer setindex;
    int[] results=null;
    Runnable refresh=this::notifyDataSetChanged;
    Adapter(M3Page page,IntConsumer setindex) {
        this.page=page;
        this.setindex=setindex;
        }
    void setResults(int[] res) {
        results=res;
        notifyDataSetChanged();
        }
    int index(int pos) {
        return results==null?pos:results[pos];
        }
    @NonNull
    @Override
    public IngredientViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int viewType) {
        ViewGroup row=(ViewGroup)LayoutInflater.from(page.ctx).inflate(R.layout.material_settings_row,parent,false);
        row.setLayoutParams(new RecyclerView.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        ((android.widget.ImageView)row.findViewById(R.id.jm_row_icon)).setImageResource(R.drawable.ic_jm_grocery);
        MaterialButton edit=new MaterialButton(page.ctx,null,com.google.android.material.R.attr.materialIconButtonStyle);
        edit.setIconResource(R.drawable.ic_jm_edit);
        edit.setIconTint(android.content.res.ColorStateList.valueOf(page.color(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        M3Page.end(row).addView(edit);
        return new IngredientViewHolder(row,edit,this);
        }
    @Override
    public void onBindViewHolder(@NonNull final IngredientViewHolder holder,int pos) {
        final int index=index(pos);
        final String name=Natives.ingredientName(index);
        M3Page.setTitle(holder.itemView,name);
        M3Page.setSummary(holder.itemView,MealPage.carbPerUnit(page.act,Natives.ingredientCarb(index),Natives.ingredientUnitName(index)));
        holder.edit.setContentDescription(page.act.getString(R.string.edit)+" "+name);
        }
    @Override
    public int getItemCount() {
        if(results!=null)
            return results.length;
        return Natives.ingredientNr();
        }
    /* Tap on a row: choose it (old: closes the list, then setindex). */
    void choose(int index) {
        page.dismiss();
        setindex.accept(index);
        }
    /* Edit button (old: the Edit check box, then tap). */
    void edit(int index) {
        define(page.act,index,setindex,refresh);
        }
    }

/* Define a new ingredient (pos<0) or edit/delete one: Meal.defineingredient. */
static void define(MainActivity act,int pos,IntConsumer setindex,Runnable changed) {
    final M3Page page=M3Page.open(act,pos>=0?R.string.jm_meal_editingredient:R.string.jm_meal_newingredient);
    page.help(R.string.mealhelp);
    LinearLayout sec=page.section((CharSequence)null);
    final TextInputEditText name=page.field(sec,label(act,R.string.name),pos>=0?Natives.ingredientName(pos):"",M3Page.TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,null,null);

    /* Units: Natives.getunits() starts with "" (no unit); the old Spinner
     * selected ingredientUnit(pos)+1 and copied it into the unit field. */
    final ArrayList<String> unitstr=Natives.getunits();
    final int usedunit=pos>=0?Natives.ingredientUnit(pos)+1:0;
    String unitText="";
    if(unitstr!=null&&usedunit>=0&&usedunit<unitstr.size())
        unitText=unitstr.get(usedunit);
    else if(pos>=0)
        unitText=Natives.ingredientUnitName(pos);
    final ArrayList<String> units=new ArrayList<>();
    if(unitstr!=null)
        for(String u:unitstr)
            if(u!=null&&!u.isEmpty())
                units.add(u);
    final MaterialAutoCompleteTextView unit=unitField(page,sec,label(act,R.string.unit),unitText,units);

    final TextInputEditText carb=page.field(sec,label(act,R.string.carbperunit),pos>=0?""+Natives.ingredientCarb(pos):"",M3Page.DECIMAL,null,null);
    unit.addTextChangedListener(new MealPage.Watcher(ed -> MealPage.setSuffix(carb,ed.length()>0?act.getString(R.string.per).trim()+" "+ed:null)));
    MealPage.setSuffix(carb,unitText.isEmpty()?null:act.getString(R.string.per).trim()+" "+unitText);

    LinearLayout db=page.section((CharSequence)null);
    page.link(db,R.drawable.ic_jm_menu_book,act.getString(R.string.jm_meal_fooddatabase),act.getString(R.string.jm_meal_fooddatabase_summary),() -> {
        page.hideKeyboard();
        FoodPage.database(act,(n,val,u) -> {
            name.setText(n);
            unit.setText(u,false);
            carb.setText(Float.toString(val));
            });
        });

    ArrayList<MaterialButton> buttons=new ArrayList<>();
    if(pos>=0&&Natives.ingredientdeleteable(pos))
        buttons.add(page.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,() ->
            M3Page.confirm(act,act.getString(R.string.jm_meal_deleteingredient),Natives.ingredientName(pos),act.getString(R.string.delete),() -> {
                if(!page.isOpen())
                    return;
                Natives.deleteingredient(pos);
                setindex.accept(-pos-1);
                page.dismiss();
                if(changed!=null)
                    changed.run();
                })));
    buttons.add(page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::close));
    buttons.add(page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> {
        final float flcarb=MealPage.editNum(carb);
        Natives.saveingredient(pos,M3Page.text(name),unit.getText()==null?"":unit.getText().toString().trim(),flcarb);
        page.dismiss();
        if(changed!=null)
            changed.run();
        }));
    page.bottomBar(buttons.toArray(new MaterialButton[0]));
    name.requestFocus();
    if(pos<0)
        name.postDelayed(() -> { if(page.isOpen()&&name.hasFocus()) help.showkeyboard(act,name); },250);
    }

/* Editable exposed dropdown: type a unit or pick one of the known units. */
private static MaterialAutoCompleteTextView unitField(M3Page page,LinearLayout parent,CharSequence label,String value,ArrayList<String> units) {
    Context wrapped=new ContextThemeWrapper(page.ctx,com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_ExposedDropdownMenu);
    TextInputLayout layout=new TextInputLayout(wrapped,null,com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle);
    layout.setHint(label);
    layout.setEndIconMode(units.isEmpty()?TextInputLayout.END_ICON_NONE:TextInputLayout.END_ICON_DROPDOWN_MENU);
    MaterialAutoCompleteTextView auto=new MaterialAutoCompleteTextView(layout.getContext());
    auto.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    auto.setSingleLine(true);
    auto.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    ArrayAdapter<String> adapter=new ArrayAdapter<String>(layout.getContext(),com.google.android.material.R.layout.m3_auto_complete_simple_item,units) {
        @NonNull @Override
        public android.widget.Filter getFilter() { // always offer every unit
            return new android.widget.Filter() {
                @Override protected FilterResults performFiltering(CharSequence c) { FilterResults r=new FilterResults(); r.values=units; r.count=units.size(); return r; }
                @Override protected void publishResults(CharSequence c,FilterResults r) { notifyDataSetChanged(); }
                };
            }
        };
    auto.setAdapter(adapter);
    auto.setText(value,false);
    layout.addView(auto,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    params.setMargins(page.dp(16),page.dp(8),page.dp(16),page.dp(8));
    parent.addView(layout,params);
    return auto;
    }
}
