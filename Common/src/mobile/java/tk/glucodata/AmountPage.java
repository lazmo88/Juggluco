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

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;

import tk.glucodata.m3.DataPickers;
import tk.glucodata.m3.M3Page;
import tk.glucodata.nums.AllData;
import tk.glucodata.nums.numio;

import static java.lang.System.currentTimeMillis;
import static tk.glucodata.Applic.isWearable;

/* Phone page for entering or editing one amount (insulin, carbohydrate, any
 * labelled number).  Replaces the floating NumberView box with its own keypad
 * and Spinners.  Reads and writes the amounts exactly like NumberView:
 * saveNum for new amounts, hitchange/hitremove for existing ones, the same
 * AllData change notifications, the same meal and exclude handling. */
public final class AmountPage {
private static final String LOG_ID="AmountPage";
private static AmountPage current=null;

private final MainActivity act;
private final M3Page page;
private long currentnum; // hit of the edited amount, 0 for a new one
private final int bron;
private final long lasttime;
private long thedate=0L;
private int labelsel;
private final int[] newmealptr={0};
private boolean shouldexclude=false;
private boolean done=false;
private final boolean wasNew;

private final ArrayList<String> labels;
private TextInputEditText valueEdit;
private MaterialAutoCompleteTextView labelChoice;
private View dateRow,timeRow,excludeRow,mealRow,activityRow;
private MaterialSwitch excludeSwitch;

/* hitptr: 0 for a new amount now, numio.newhit for a new amount at the time
 * and label tapped on the graph, otherwise an existing amount (mkhitptr).
 * tmpmealptr>=0: new meal amount with this meal, opens the meal list. */
public static void show(MainActivity act,long hitptr,int tmpmealptr) {
    show(act,hitptr,tmpmealptr,null);
    }
static void show(MainActivity act,long hitptr,int tmpmealptr,Runnable after) {
    if(current!=null)
        current.discard();
    NumberView numb=act.curve!=null?act.curve.numberview:null;
    if(numb!=null) {
        numb.closenumview(); // old box, if any
        if(numb.currentnum!=0L&&numb.currentnum!=numio.newhit)
            Natives.freehitptr(numb.currentnum);
        numb.currentnum=0L;
        }
    current=new AmountPage(act,hitptr,tmpmealptr);
    current.after=after;
    }
private Runnable after=null;

private AmountPage(MainActivity act,long hitptr,int tmpmealptr) {
    this.act=act;
    final boolean oldnum=hitptr!=0L&&hitptr!=numio.newhit;
    final long time;
    final float value;
    final int type;
    int mealptr=0;
    boolean exclude=false;
    if(hitptr!=0L) {
        time=Natives.hittime(hitptr)*1000L;
        bron=Natives.gethitindex(hitptr);
        type=Natives.hittype(hitptr);
        exclude=Natives.hitexclude(hitptr);
        value=Natives.hitvalue(hitptr);
        if(oldnum)
            mealptr=Natives.hitmeal(hitptr);
        }
    else {
        time=currentTimeMillis();
        bron=1;
        type=tmpmealptr>=0?Natives.getmealvar():0;
        value=Float.MAX_VALUE;
        }
    currentnum=oldnum?hitptr:0L;
    wasNew=!oldnum;
    lasttime=time;
    labelsel=type;
    labels=labelNames();

    page=M3Page.open(act,oldnum?act.getString(R.string.jm_data_editamount):act.getString(R.string.new_amount));
    page.help(R.string.newamount);
    if(bron!=1)
        page.subtitle(act.getString(R.string.jm_data_fromwatch));

    LinearLayout sec=page.section((CharSequence)null);
    labelChoice=page.choice(sec,act.getString(R.string.jm_data_label),labels,type,pos -> {
        labelsel=pos;
        setmealbutton();
        });
    final String valstr=(!Float.isNaN(value)&&value<Float.MAX_VALUE)?String.valueOf(value):"";
    valueEdit=page.field(sec,act.getString(R.string.jm_data_amount),valstr,M3Page.DECIMAL,null,null);
    dateRow=page.link(sec,R.drawable.ic_jm_event,act.getString(R.string.date),null,this::pickDate);
    timeRow=page.link(sec,R.drawable.ic_jm_schedule,act.getString(R.string.time),null,this::pickTime);
    excludeSwitch=page.toggle(sec,R.drawable.ic_jm_block,act.getString(R.string.exclude),null,() -> false,on -> {});
    excludeRow=(View)rowOf(excludeSwitch);
    mealRow=page.link(sec,R.drawable.ic_jm_restaurant,act.getString(R.string.mealname),null,this::openMeal);
    activityRow=page.link(sec,R.drawable.ic_jm_monitoring,act.getString(R.string.activity_details),null,this::openActivity);
    showTime();

    final boolean staticnum=Natives.staticnum();
    ArrayList<MaterialButton> buttons=new ArrayList<>();
    if(oldnum&&!staticnum)
        buttons.add(page.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,this::askDelete));
    buttons.add(page.button(M3Page.Style.TONAL,act.getString(R.string.cancel),0,this::cancel));
    if(!staticnum)
        buttons.add(page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,this::save));
    else
        page.note(null,act.getString(R.string.dontchangeamounts));
    page.bottomBar(buttons.toArray(new MaterialButton[0]));

    page.onClose(this::closed);
    setExcludeTime(time);
    setmealbutton(type,bron,mealptr,oldnum?exclude:shouldexclude);
    if(tmpmealptr>=0) {
        newmealptr[0]=tmpmealptr;
        openMeal();
        }
    else if(!oldnum&&hitptr==0L) {
        valueEdit.requestFocus();
        valueEdit.postDelayed(() -> { if(!done) help.showkeyboard(act,valueEdit); },250);
        }
    }

private static ViewGroup rowOf(View child) {
    View v=child;
    while(v.getParent() instanceof View) {
        View p=(View)v.getParent();
        if(p.getId()!=View.NO_ID&&p.getId()==R.id.jm_row_end)
            return (ViewGroup)p.getParent();
        v=p;
        }
    return (ViewGroup)child.getParent();
    }

private long when() {
    return thedate==0L?lasttime:thedate;
    }
private void showTime() {
    final long t=when();
    M3Page.setSummary(dateRow,DateFormat.getDateInstance(DateFormat.DEFAULT).format(t));
    M3Page.setSummary(timeRow,NumberView.minhourstr(t));
    }

private void setExcludeTime(long time) {
    shouldexclude=Natives.shouldExclude(time);
    excludeSwitch.setEnabled(!shouldexclude);
    excludeRow.setEnabled(!shouldexclude);
    if(shouldexclude)
        excludeSwitch.setChecked(true);
    }

private void setmealbutton() {
    final boolean here=currentnum==0L;
    setmealbutton(labelsel,here?1:Natives.gethitindex(currentnum),here?1:Natives.hitmeal(currentnum),here?shouldexclude:Natives.hitexclude(currentnum));
    }
/* Which extra row the label needs, as NumberView.setmealbutton. */
private void setmealbutton(int label,int bron,int mealptr,boolean exclude) {
    mealRow.setVisibility(View.GONE);
    activityRow.setVisibility(View.GONE);
    excludeRow.setVisibility(View.GONE);
    if(label!=Natives.getbloodvar()&&(mealptr&0x80000000)!=0) {
        activityRow.setVisibility(View.VISIBLE);
        return;
        }
    if(!isWearable&&label==Natives.getmealvar()&&(bron==1||mealptr>0))
        mealRow.setVisibility(View.VISIBLE);
    else if(label==Natives.getbloodvar()) {
        excludeRow.setVisibility(View.VISIBLE);
        excludeSwitch.setChecked(shouldexclude||exclude);
        }
    }

private void pickDate() {
    page.hideKeyboard();
    DataPickers.date(act,act.getString(R.string.date),when(),(year,month,day) -> {
        Calendar cal=Calendar.getInstance();
        cal.setTimeInMillis(when());
        cal.set(Calendar.YEAR,year);
        cal.set(Calendar.MONTH,month);
        cal.set(Calendar.DAY_OF_MONTH,day);
        thedate=cal.getTimeInMillis();
        setExcludeTime(thedate);
        showTime();
        },null);
    }
private void pickTime() {
    page.hideKeyboard();
    Calendar cal=Calendar.getInstance();
    cal.setTimeInMillis(when());
    DataPickers.time(act,act.getString(R.string.time),cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),(hour,min) -> {
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(when());
        c.set(Calendar.HOUR_OF_DAY,hour);
        c.set(Calendar.MINUTE,min);
        c.set(Calendar.SECOND,0);
        thedate=c.getTimeInMillis();
        setExcludeTime(thedate);
        showTime();
        });
    }

private static float roundto(float get,float ro) {
    return Math.round(get/ro)*ro;
    }

/* The meal list (Meal.java) on top of this page, as from the old Meal button. */
private void openMeal() {
    page.hideKeyboard();
    NumberView numb=act.curve!=null?act.curve.numberview:null;
    if(numb==null)
        return;
    numb.currentnum=currentnum; // Meal's Repeat links the meal to the edited amount
    int mptr=newmealptr[0]==0?((currentnum!=0L)?Natives.hitmeal(currentnum):0):newmealptr[0];
    if(mptr==0)
        mptr=Natives.getnewmealptr();
    page.root.setVisibility(View.INVISIBLE);
    Meal.menuview(numb,act,mptr,(carb,mealptr) -> {
        float roundt=Natives.getroundto();
        valueEdit.setText(Float.toString(roundt>0.0f?roundto(carb,roundt):carb));
        newmealptr[0]=mealptr;
        },() -> {
        numb.currentnum=0L;
        if(!done)
            page.root.setVisibility(View.VISIBLE);
        });
    }

private void openActivity() {
    if(currentnum==0L)
        return;
    try {
        int activity=ActivityNative.reference(currentnum);
        if(activity!=0) {
            final long hit=currentnum;
            final int[] previousType={Natives.hittype(hit)};
            final float[] previousValue={Natives.hitvalue(hit)};
            ActivitySettings.details(act,page.root,activity,() -> {
                if(done||currentnum!=hit)
                    return;
                int type=Natives.hittype(hit);
                float value=Natives.hitvalue(hit);
                if(labelsel==previousType[0])
                    labelsel=type;
                ArrayList<String> now=labelNames();
                labels.clear();
                labels.addAll(now);
                if(labelsel>=0&&labelsel<labels.size())
                    M3Page.select(labelChoice,labels.get(labelsel));
                if(M3Page.number(valueEdit)==previousValue[0])
                    valueEdit.setText(Float.toString(value));
                previousType[0]=type;
                previousValue[0]=value;
                setmealbutton();
                },MainActivity::doonback);
            }
        else
            Applic.argToaster(act,R.string.activity_unavailable,android.widget.Toast.LENGTH_LONG);
        }
    catch(Exception e) {
        Log.stack(LOG_ID,"Open activity details from Amount",e);
        Applic.argToaster(act,e.getMessage()==null?e.toString():e.getMessage(),android.widget.Toast.LENGTH_LONG);
        }
    }

/* NumberView.saveamount */
private boolean saveamount() {
    if(currentnum!=0L&&Natives.hittype(currentnum)!=Natives.getbloodvar()&&
        (Natives.hitmeal(currentnum)&0x80000000)!=0&&!ActivityNative.labelAllowed(labelsel)) {
        page.toast(act.getString(R.string.activity_choose_label));
        return false;
        }
    if(labelsel<0||labelsel>=labels.size()) {
        page.toast(act.getString(R.string.jm_data_chooselabel));
        return false;
        }
    final String strval=M3Page.text(valueEdit).replace(',','.');
    float val=0.0f;
    try {
        val=(strval.length()==0)?0:Float.parseFloat(strval);
        }
    catch(Throwable e) {
        Log.stack(LOG_ID,"parseFloat "+strval,e);
        M3Page.error(valueEdit,act.getString(R.string.jm_data_badnumber));
        return false;
        }
    int mealptr=newmealptr[0];
    if(labelsel==Natives.getbloodvar())
        mealptr=excludeSwitch.isChecked()?1:0;
    final long dat=when();
    if(currentnum!=0L) {
        Natives.hitchange(currentnum,dat/1000L,val,labelsel,mealptr);
        int index=Natives.gethitindex(currentnum);
        Applic.app.numdata.changedback(index);
        Natives.freehitptr(currentnum);
        }
    else {
        final int index=1;
        Natives.saveNum(numio.numptrs[index],dat/1000,val,labelsel,mealptr);
        Applic.app.numdata.changedback(index);
        }
    currentnum=0L;
    return true;
    }

private void save() {
    GlucoseCurve.reopener();
    if(!saveamount())
        return;
    newmealptr[0]=0;
    done=true;
    Applic.app.redraw();
    page.dismiss();
    if(wasNew&&Menus.on&&after==null)
        Menus.show(act);
    }

private void cancel() {
    page.close();
    }

/* Back, Up and Cancel: NumberView's closerRun. */
private void closed() {
    if(current==this)
        current=null;
    if(!done) {
        done=true;
        if(newmealptr[0]!=0) {
            if(currentnum!=0L)
                Natives.hitsetmealptr(currentnum,newmealptr[0]);
            else
                Natives.deletemeal(newmealptr[0]);
            newmealptr[0]=0;
            }
        if(currentnum!=0L) {
            Natives.freehitptr(currentnum);
            currentnum=0L;
            }
        else if(Menus.on&&after==null)
            Menus.show(act);
        GlucoseCurve.reopener();
        }
    if(!isWearable&&Build.VERSION.SDK_INT>=28)
        ActivityHealth.numberEditorClosed();
    if(after!=null)
        after.run();
    }

/* Another amount page replaces this one (Meal Repeat). */
private void discard() {
    if(done)
        return;
    done=true;
    if(currentnum!=0L) {
        Natives.freehitptr(currentnum);
        currentnum=0L;
        }
    page.dismiss();
    }

/* NumberView.deletedialog */
private void askDelete() {
    if(currentnum==0L)
        return;
    final long time=Natives.hittime(currentnum)*1000L;
    final float value=Natives.hitvalue(currentnum);
    final int type=Natives.hittype(currentnum);
    final String label=type>=0&&type<labels.size()?labels.get(type):"";
    final String mess=DateFormat.getDateTimeInstance(DateFormat.DEFAULT,DateFormat.DEFAULT).format(time)+" "+label+" "+value;
    M3Page.confirm(act,act.getString(R.string.deletequestion),mess,act.getString(R.string.delete),() -> {
        if(done)
            return;
        if(newmealptr[0]!=0)
            Natives.deletemeal(newmealptr[0]);
        newmealptr[0]=0;
        if(currentnum!=0L) {
            removeHit(currentnum);
            currentnum=0L;
            }
        done=true;
        GlucoseCurve.reopener();
        page.dismiss();
        });
    }

/* Remove an existing amount and free its hit, as NumberView.deletedialog. */
static void removeHit(long hit) {
    int index=Natives.gethitindex(hit);
    int waslast=numio.getlastnum(index);
    int pos=Natives.hitremove(hit);
    int last=numio.getlastnum(index);
    AllData alldata=Applic.app.numdata;
    alldata.deletelast(index,last,waslast);
    if(pos<last)
        alldata.changedback(index);
    Natives.freehitptr(hit);
    Applic.app.redraw();
    }

/* Natives.getLabels() without its trailing empty entry (the "no label"
 * item of the search Spinner). */
static ArrayList<String> labelNames() {
    ArrayList<String> l=Natives.getLabels();
    if(l==null)
        return new ArrayList<>();
    if(l.size()>0)
        l.remove(l.size()-1); // native getLabels adds one "" after the labels
    return l;
    }

static boolean isOpen() {
    return current!=null&&!current.done;
    }
}
