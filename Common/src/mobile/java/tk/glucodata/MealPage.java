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

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;

import tk.glucodata.m3.M3Page;
import tk.glucodata.nums.numio;

/* Phone Material 3 pages for a meal, replacing the floating Layouts of
 * Meal.java:
 *   MealPage        the items of one meal, rounding and Repeat  (Meal.menuview, askround)
 *   MealPage.item   add or edit one item of the meal             (Meal.menuitem)
 *   IngredientPage  choose, define and edit ingredients          (Meal.selectingredient, defineingredient)
 *   FoodPage        food database search and nutrients           (Meal.fooddatabase, shownutrients)
 * The meal data is read and written with exactly the same Natives calls; the
 * carbohydrate total goes back to the caller (AmountPage) through the same
 * callback: give(carb,mealptr) when the page opens and after every change,
 * endrun when the meal page closes. */
final class MealPage {
private static final String LOG_ID="MealPage";

private final MainActivity act;
private final M3Page page;
private final NumberView numb;
private final ObjIntConsumer<Float> give;
private int mealptr;
private float carb;
private final LinearLayout items;
private final TextView totalView;
private final ViewGroup roundRow;

static void show(MainActivity act,NumberView numb,int mealptr,ObjIntConsumer<Float> give,Runnable endrun) {
    new MealPage(act,numb,mealptr,give,endrun);
    }

private MealPage(MainActivity act,NumberView numb,int mealptrin,ObjIntConsumer<Float> give,Runnable endrun) {
    this.act=act;
    this.numb=numb;
    this.give=give;
    mealptr=mealptrin;
    page=M3Page.open(act,R.string.mealname);
    page.help(R.string.mealhelp);

    carb=Natives.carbinmeal(mealptr);
    final boolean canRepeat=carb!=0.0f; // as before: only a meal that had carbohydrate when opened
    give.accept(carb,mealptr);

    LinearLayout top=page.section((CharSequence)null);
    ViewGroup totalRow=page.item(top,R.drawable.ic_jm_nutrition,act.getString(R.string.jm_meal_totalcarb),null,null);
    totalView=endText(page,totalRow);
    roundRow=page.link(top,R.drawable.ic_jm_decimal_decrease,label(act,R.string.roundto),roundSummary(act),this::askRound);

    items=page.section(R.string.jm_meal_items);
    fill();

    ArrayList<MaterialButton> buttons=new ArrayList<>();
    if(canRepeat) {
        MaterialButton repeat=page.button(M3Page.Style.TONAL,act.getString(R.string.repeat),R.drawable.ic_jm_repeat,this::repeat);
        repeat.setContentDescription(act.getString(R.string.repeat)+", "+act.getString(R.string.jm_meal_repeat_desc));
        buttons.add(repeat);
        }
    buttons.add(page.button(M3Page.Style.FILLED,act.getString(R.string.additem),R.drawable.ic_jm_add,() -> editItem(-1)));
    page.bottomBar(buttons.toArray(new MaterialButton[0]));
    page.onClose(endrun);
    }

/* Rows of the meal items, as Meal.MealItemViewAdapter. */
private void fill() {
    items.removeAllViews();
    totalView.setText(Meal.ondecimal(carb,10));
    final int nr=Natives.getmealitemnr(mealptr);
    if(nr<=0) {
        page.empty(items,R.drawable.ic_jm_restaurant,act.getString(R.string.jm_meal_noitems),act.getString(R.string.jm_meal_noitems_body));
        return;
        }
    for(int pos=0;pos<nr;pos++) {
        final int p=pos;
        final float amount=Natives.getitemamount(mealptr,pos);
        final String name=Natives.getitemingredientname(mealptr,pos);
        ViewGroup row;
        if(name==null||Float.isNaN(amount)) {
            row=page.row(items,R.drawable.ic_jm_grocery,act.getString(R.string.error),null);
            }
        else {
            final int ingr=Natives.getitemingredient(mealptr,pos);
            final String unit=ingr>=0?Natives.ingredientUnitName(ingr):"";
            final String amountText=num(amount)+(unit==null||unit.isEmpty()?"":" "+unit);
            row=page.row(items,R.drawable.ic_jm_grocery,name,amountText);
            if(ingr>=0) {
                final String c=Meal.ondecimal(amount*Natives.ingredientCarb(ingr),10);
                endText(page,row).setText(c);
                row.setContentDescription(name+", "+amountText+", "+label(act,R.string.carbohydrate)+" "+c);
                }
            }
        row.setOnClickListener(v -> editItem(p));
        }
    }

private static String roundSummary(MainActivity act) {
    final float r=Natives.getroundto();
    return r>0.0f?Float.toString(r):act.getString(R.string.jm_meal_noround);
    }

/* Meal.askround: Save sets the rounding and passes the total on again. */
private void askRound() {
    LinearLayout box=new LinearLayout(page.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(page.dp(8),page.dp(12),page.dp(8),0);
    TextInputEditText edit=page.field(box,label(act,R.string.roundto),Float.toString(Natives.getroundto()),M3Page.DECIMAL,null,null);
    TextInputLayout layout=layoutOf(edit);
    if(layout!=null)
        layout.setHelperText(act.getString(R.string.jm_meal_round_hint));
    androidx.appcompat.app.AlertDialog dialog=M3Page.dialog(act)
        .setTitle(label(act,R.string.roundto))
        .setView(box)
        .setNegativeButton(R.string.cancel,null)
        .setPositiveButton(R.string.save,(d,w) -> {
            Natives.setroundto(editNum(edit));
            give.accept(carb,mealptr);
            M3Page.setSummary(roundRow,roundSummary(act));
            })
        .create();
    if(dialog.getWindow()!=null)
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    dialog.show();
    edit.requestFocus();
    edit.selectAll();
    }

/* Meal.menuview's Repeat: link this meal to the amount being edited, and
 * start a new amount with a copy of the meal. */
private void repeat() {
    if(mealptr==0)
        return;
    if(numb.currentnum!=0L&&numb.currentnum!=numio.newhit) {
        if(Log.doLog) Log.i(LOG_ID,"repeat");
        Natives.hitsetmealptr(numb.currentnum,mealptr);
        }
    final int ptrcp=Natives.cpmeal(mealptr);
    /* endrun (onClose) clears numb.currentnum: the amount page under this one
     * owns that hit and frees it once when the new amount replaces it. */
    page.dismiss();
    numb.addnumberwithmenu(act,ptrcp);
    }

private void editItem(int pos) {
    item(act,mealptr,pos,carb,newmealptr -> {
        if(newmealptr>=0) {
            carb=Natives.carbinmeal(newmealptr);
            mealptr=newmealptr;
            give.accept(carb,newmealptr);
            if(page.isOpen())
                fill();
            }
        });
    }

/* ---------- one item (Meal.menuitem) ---------- */

static void item(MainActivity act,int mealptr,int pos,float carbmealin,IntConsumer give) {
    final M3Page page=M3Page.open(act,pos>=0?act.getString(R.string.jm_meal_edititem):act.getString(R.string.additem));
    page.help(R.string.mealhelp);
    final float[] cargs={0.0f};
    final int[] ingred={-1};
    final boolean[] done={false};
    String amountStr="";
    String totalStr="";
    float carbmeal=carbmealin;
    if(pos>=0) {
        final float am=Natives.getitemamount(mealptr,pos);
        amountStr=""+Math.round(am*10.0f)/10.0f;
        final int ingr=Natives.getitemingredient(mealptr,pos);
        ingred[0]=ingr;
        cargs[0]=Natives.ingredientCarb(ingr);
        final float tot=am*cargs[0];
        totalStr=Meal.ondecimal(tot,10);
        carbmeal-=tot;
        }
    final float rest=carbmeal; // carbohydrate of the other items

    LinearLayout sec=page.section((CharSequence)null);
    final ViewGroup ingrRow=page.link(sec,R.drawable.ic_jm_grocery,label(act,R.string.ingredient),null,() -> {});
    final TextInputEditText amount=page.field(sec,act.getString(R.string.quantity),amountStr,M3Page.DECIMAL,null,null);
    final TextInputEditText total=page.field(sec,label(act,R.string.carbohydrate),totalStr,M3Page.DECIMAL,null,null);
    final TextInputEditText mealtotal=page.field(sec,label(act,R.string.mealtotal),Meal.ondecimal(carbmealin,10),M3Page.DECIMAL,null,null);
    final Runnable showIngredient=() -> {
        if(ingred[0]<0) {
            M3Page.setSummary(ingrRow,act.getString(R.string.jm_meal_chooseingredient));
            setSuffix(amount,null);
            return;
            }
        final String unit=Natives.ingredientUnitName(ingred[0]);
        M3Page.setSummary(ingrRow,Natives.ingredientName(ingred[0])+" · "+carbPerUnit(act,cargs[0],unit));
        setSuffix(amount,unit);
        };
    showIngredient.run();

    final boolean[] changing={false};
    amount.addTextChangedListener(new Watcher(ed -> {
        M3Page.error(amount,null);
        if(changing[0])
            return;
        changing[0]=true;
        final float tot=str2float(ed)*cargs[0];
        total.setText(Meal.ondecimal(tot,10));
        mealtotal.setText(Meal.ondecimal(rest+tot,10));
        changing[0]=false;
        }));
    total.addTextChangedListener(new Watcher(ed -> {
        if(changing[0]||!(cargs[0]>0.0f))
            return;
        changing[0]=true;
        final float tot=str2float(ed);
        amount.setText(Meal.ondecimal(tot/cargs[0],10));
        mealtotal.setText(Meal.ondecimal(rest+tot,10));
        changing[0]=false;
        }));
    mealtotal.addTextChangedListener(new Watcher(ed -> {
        if(changing[0]||!(cargs[0]>0.0f))
            return;
        changing[0]=true;
        final float tot=str2float(ed)-rest;
        amount.setText(Meal.ondecimal(tot/cargs[0],10));
        total.setText(Meal.ondecimal(tot,10));
        changing[0]=false;
        }));

    ingrRow.setOnClickListener(v -> {
        page.hideKeyboard();
        IngredientPage.select(act,i -> {
            if(i<0) { // ingredient -i-1 was deleted
                if(ingred[0]>=0&&i==(-ingred[0]-1)) {
                    ingred[0]=-1;
                    cargs[0]=0.0f;
                    showIngredient.run();
                    }
                }
            else {
                ingred[0]=i;
                cargs[0]=Natives.ingredientCarb(i);
                showIngredient.run();
                changing[0]=true;
                final float tot=editNum(amount)*cargs[0];
                total.setText(Meal.ondecimal(tot,10));
                mealtotal.setText(Meal.ondecimal(rest+tot,10));
                changing[0]=false;
                amount.requestFocus();
                }
            });
        });

    final Runnable save=() -> {
        if(ingred[0]==-1) {
            page.toast(act.getString(R.string.specifyingredient));
            return;
            }
        final float am=editNum(amount);
        if(am==0.0f) {
            M3Page.error(amount,act.getString(R.string.specifyhowmuch));
            page.toast(act.getString(R.string.specifyhowmuch));
            return;
            }
        final int newmealptr=Natives.changemealitem(mealptr,pos,ingred[0],am);
        done[0]=true;
        page.dismiss();
        give.accept(newmealptr);
        };
    amount.setOnEditorActionListener((v,actionId,ev) -> {
        page.hideKeyboard();
        return true;
        });

    ArrayList<MaterialButton> buttons=new ArrayList<>();
    if(pos>=0)
        buttons.add(page.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,() -> {
            String name=ingred[0]>=0?Natives.ingredientName(ingred[0]):null;
            M3Page.confirm(act,act.getString(R.string.jm_meal_deleteitem),name,act.getString(R.string.delete),() -> {
                if(done[0])
                    return;
                final int newmealptr=Natives.deletefrommeal(mealptr,pos);
                done[0]=true;
                page.dismiss();
                give.accept(newmealptr);
                });
            }));
    buttons.add(page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,page::close));
    buttons.add(page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,save));
    page.bottomBar(buttons.toArray(new MaterialButton[0]));
    page.onClose(() -> {
        if(!done[0]) {
            done[0]=true;
            give.accept(-1);
            }
        });
    amount.requestFocus();
    amount.postDelayed(() -> { if(page.isOpen()&&amount.hasFocus()) help.showkeyboard(act,amount); },250);
    }

/* ---------- shared helpers ---------- */

/* Upstream labels end in ": "; M3 field labels and titles do not. */
static String label(MainActivity act,int id) {
    String s=act.getString(id).trim();
    while(s.endsWith(":")||s.endsWith("\uFF1A"))
        s=s.substring(0,s.length()-1).trim();
    return s;
    }

/* Settings.str2float, accepting a decimal comma: 0 if empty or invalid. */
static float str2float(CharSequence str) {
    if(str==null)
        return 0.0f;
    String s=str.toString().trim().replace(',','.');
    try {
        return Float.parseFloat(s);
        }
    catch(Exception e) {
        return 0.0f;
        }
    }
static float editNum(TextInputEditText edit) {
    return str2float(edit.getText());
    }

/* One decimal, without a trailing .0 */
static String num(float v) {
    String s=Meal.ondecimal(v,10);
    if(s.endsWith(".0"))
        s=s.substring(0,s.length()-2);
    return s;
    }

static String carbPerUnit(MainActivity act,float carb,String unit) {
    return label(act,R.string.carbohydrate)+" "+carb+act.getString(R.string.per)+(unit==null?"":unit);
    }

static TextView endText(M3Page page,ViewGroup row) {
    TextView text=new TextView(page.ctx);
    text.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    text.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
    M3Page.end(row).addView(text);
    return text;
    }

static @Nullable TextInputLayout layoutOf(View edit) {
    View p=(View)edit.getParent();
    while(p!=null&&!(p instanceof TextInputLayout))
        p=p.getParent() instanceof View?(View)p.getParent():null;
    return (TextInputLayout)p;
    }
static void setSuffix(TextInputEditText edit,@Nullable CharSequence suffix) {
    TextInputLayout layout=layoutOf(edit);
    if(layout!=null)
        layout.setSuffixText(suffix==null||suffix.length()==0?null:suffix);
    }

interface EditDone { void done(Editable ed); }
static final class Watcher implements TextWatcher {
    private final EditDone done;
    Watcher(EditDone done) { this.done=done; }
    @Override public void afterTextChanged(Editable ed) { done.done(ed); }
    @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
    @Override public void onTextChanged(CharSequence s,int start,int before,int count) { }
    }

/* A list page: the M3Page's scrolling list is replaced by an optional fixed
 * header (search field), a RecyclerView of list rows and an empty state, as
 * in AmountListPage. */
static final class ListParts {
    LinearLayout header;
    RecyclerView recycler;
    LinearLayout emptyHolder;
    }
static ListParts listPage(M3Page page,boolean withBottomBar) {
    ListParts parts=new ListParts();
    page.scroll.setVisibility(View.GONE);
    LinearLayout root=(LinearLayout)page.root;
    parts.header=new LinearLayout(page.ctx);
    parts.header.setOrientation(LinearLayout.VERTICAL);
    root.addView(parts.header,root.indexOfChild(page.scroll)+1,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    RecyclerView recycler=new RecyclerView(page.ctx);
    recycler.setLayoutManager(new LinearLayoutManager(page.ctx));
    recycler.setClipToPadding(false);
    recycler.setPadding(page.dp(16),0,page.dp(16),page.dp(24)+(withBottomBar?0:MainActivity.systembarBottom));
    final int flat=page.color(com.google.android.material.R.attr.colorSurface);
    final int lifted=page.color(com.google.android.material.R.attr.colorSurfaceContainer);
    recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
        @Override public void onScrolled(@NonNull RecyclerView rv,int dx,int dy) {
            final int c=rv.canScrollVertically(-1)?lifted:flat;
            page.toolbar.setBackgroundColor(c);
            parts.header.setBackgroundColor(c);
            }
        });
    root.addView(recycler,root.indexOfChild(parts.header)+1,new LinearLayout.LayoutParams(MATCH_PARENT,0,1f));
    parts.recycler=recycler;
    LinearLayout holder=new LinearLayout(page.ctx);
    holder.setOrientation(LinearLayout.VERTICAL);
    holder.setGravity(Gravity.CENTER);
    holder.setVisibility(View.GONE);
    root.addView(holder,root.indexOfChild(recycler)+1,new LinearLayout.LayoutParams(MATCH_PARENT,0,1f));
    parts.emptyHolder=holder;
    return parts;
    }

/* Outlined search field with a leading search icon (tap: search) and a
 * clear button; the keyboard's Search key runs onSearch. */
static TextInputEditText searchField(M3Page page,LinearLayout parent,CharSequence hint,@Nullable Runnable onSearch) {
    Context wrapped=new ContextThemeWrapper(page.ctx,com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox);
    TextInputLayout layout=new TextInputLayout(wrapped,null,com.google.android.material.R.attr.textInputOutlinedStyle);
    layout.setHint(hint);
    layout.setStartIconDrawable(R.drawable.ic_jm_search);
    layout.setStartIconContentDescription(page.act.getString(R.string.search));
    layout.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
    TextInputEditText edit=new TextInputEditText(layout.getContext());
    edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    edit.setSingleLine(true);
    edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    edit.setOnEditorActionListener((v,actionId,ev) -> {
        if(actionId==EditorInfo.IME_ACTION_SEARCH||(ev!=null&&ev.getKeyCode()==KeyEvent.KEYCODE_ENTER)) {
            if(ev==null||ev.getAction()==KeyEvent.ACTION_DOWN) {
                if(onSearch!=null)
                    onSearch.run();
                page.hideKeyboard();
                }
            return true;
            }
        return false;
        });
    if(onSearch!=null)
        layout.setStartIconOnClickListener(v -> { onSearch.run(); page.hideKeyboard(); });
    layout.addView(edit,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    params.setMargins(page.dp(16),page.dp(4),page.dp(16),page.dp(8));
    parent.addView(layout,params);
    return edit;
    }
}
