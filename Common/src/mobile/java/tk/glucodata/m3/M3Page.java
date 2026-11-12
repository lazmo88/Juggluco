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

package tk.glucodata.m3;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Natives.getInvertColors;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.Insets;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.R;

/*
 * Full screen Material 3 page for phones: top app bar with Up and optional
 * actions, a scrolling list of titled card sections, and an optional bottom
 * action bar.  Every redesigned Juggluco screen is built from these parts so
 * they all look and behave the same:
 *
 *   M3Page p=M3Page.open(act,act.getString(R.string.x));
 *   LinearLayout sec=p.section("Title");
 *   p.toggle(sec,R.drawable.ic_jm_x,"Label",null,Natives::getX,Natives::setX);
 *   p.field(sec,"Seconds",String.valueOf(n),M3Page.INTEGER,"s",s -> ...);
 *
 * Back (system Back, gesture, or Up) closes the page via MainActivity's
 * setonback stack, so pages nest naturally on top of each other and on top of
 * the old screens.  The page follows Juggluco's dark mode switch and dynamic
 * colour; system bar icons are set to match while it is on top.
 *
 * Values are written with the same Natives/Settings setters the old screens
 * used; pages do not invent new storage.
 */
public final class M3Page {
private static final String LOG_ID="M3Page";

public static final int TEXT=InputType.TYPE_CLASS_TEXT;
public static final int INTEGER=InputType.TYPE_CLASS_NUMBER;
public static final int SIGNED=InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED;
public static final int DECIMAL=InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL;
public static final int PASSWORD=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD;
public static final int URL=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI;
public static final int EMAIL=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;

public enum Style { FILLED, TONAL, OUTLINED, TEXT }

public interface BoolGet { boolean get(); }
public interface BoolSet { void set(boolean on); }
public interface TextSet { void set(String value); }
public interface IndexSet { void set(int index); }
public interface FloatSet { void set(float value); }
public interface FloatText { String text(float value); }
/* Return false to keep the page open (e.g. invalid input was reported). */
public interface CloseCheck { boolean canClose(); }

public final MainActivity act;
public final Context ctx;
public final ViewGroup root;
public final MaterialToolbar toolbar;
public final NestedScrollView scroll;
public final LinearLayout list;
private LinearLayout bottomBar;
private final ArrayList<Runnable> refreshers=new ArrayList<>();
private final ArrayList<Runnable> onClose=new ArrayList<>();
private CloseCheck closeCheck;
private boolean closed=false;
/* True while refresh() sets values, so change listeners do not write back. */
public boolean updating=false;
private int insetLeft,insetTop,insetRight,insetBottom;

/* ---------- theme ---------- */

public static Context themed(Context base) {
    final int theme=getInvertColors()?R.style.Juggluco_M3_Dark:R.style.Juggluco_M3_Light;
    return DynamicColors.wrapContextIfAvailable(new ContextThemeWrapper(base,theme));
    }
public static boolean dark() {
    return getInvertColors();
    }
public static int color(Context ctx,int attr) {
    TypedValue tv=new TypedValue();
    ctx.getTheme().resolveAttribute(attr,tv,true);
    return tv.data;
    }
public int color(int attr) {
    return color(ctx,attr);
    }
public static int dp(Context ctx,float dp) {
    return Math.round(dp*ctx.getResources().getDisplayMetrics().density);
    }
public int dp(float v) {
    return dp(ctx,v);
    }

/* ---------- opening and closing ---------- */

public static M3Page open(MainActivity act,CharSequence title) {
    return new M3Page(act,title);
    }
public static M3Page open(MainActivity act,int title) {
    return new M3Page(act,act.getString(title));
    }

private M3Page(MainActivity act,CharSequence title) {
    this.act=act;
    ctx=themed(act);
    tk.glucodata.help.hidekeyboard(act);
    root=(ViewGroup)LayoutInflater.from(ctx).inflate(R.layout.material_settings,null,false);
    root.setTag(R.id.jm_m3_page,this);
    toolbar=root.findViewById(R.id.jm_settings_toolbar);
    toolbar.setTitle(title);
    toolbar.setNavigationOnClickListener(v -> MainActivity.doonback());
    scroll=root.findViewById(R.id.jm_settings_scroll);
    list=root.findViewById(R.id.jm_settings_list);
    handleInsets();
    /* Not addMyContentView(): that restyles widgets for the old app themes. */
    act.addContentView(root,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    M3Bars.watch(act);
    MainActivity.setonback(this::backPressed);
    }

private void backPressed() {
    if(closeCheck!=null&&!closeCheck.canClose()) {
        MainActivity.setonback(this::backPressed); // stay open, keep handling Back
        return;
        }
    remove();
    }

/* Close from code (Save, Cancel): like Back, but takes this page's Back
 * handler off the stack. */
public void close() {
    if(closed)
        return;
    if(closeCheck!=null&&!closeCheck.canClose())
        return;
    MainActivity.poponback();
    remove();
    }

/* Close without the check, e.g. after Save already validated. */
public void dismiss() {
    if(closed)
        return;
    MainActivity.poponback();
    remove();
    }

private void remove() {
    if(closed)
        return;
    closed=true;
    tk.glucodata.help.hidekeyboard(act);
    ViewGroup parent=(ViewGroup)root.getParent();
    if(parent!=null)
        parent.removeView(root);
    for(Runnable r:onClose) {
        try { r.run(); }
        catch(Throwable th) { Log.stack(LOG_ID,"onClose",th); }
        }
    M3Bars.update(act);
    }

public boolean isOpen() {
    return !closed;
    }
public M3Page onClose(Runnable r) {
    onClose.add(r);
    return this;
    }
public M3Page closeCheck(CloseCheck check) {
    closeCheck=check;
    return this;
    }
public M3Page subtitle(CharSequence s) {
    toolbar.setSubtitle(s);
    return this;
    }

/* The page on top, if it is an M3Page. */
public static M3Page top(MainActivity act) {
    View content=act.findViewById(android.R.id.content);
    if(!(content instanceof ViewGroup))
        return null;
    ViewGroup group=(ViewGroup)content;
    for(int i=group.getChildCount()-1;i>=0;i--) {
        Object tag=group.getChildAt(i).getTag(R.id.jm_m3_page);
        if(tag instanceof M3Page)
            return (M3Page)tag;
        }
    return null;
    }

/* ---------- top app bar ---------- */

public MenuItem action(int icon,CharSequence title,Runnable click) {
    Menu menu=toolbar.getMenu();
    MenuItem item=menu.add(Menu.NONE,View.generateViewId(),menu.size(),title);
    if(icon!=0) {
        item.setIcon(icon);
        if(item.getIcon()!=null)
            item.getIcon().setTint(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
    else
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    item.setOnMenuItemClickListener(m -> { click.run(); return true; });
    return item;
    }

/* Help icon in the app bar showing one of Juggluco's help texts. */
public MenuItem help(int helpString) {
    return action(R.drawable.ic_jm_help,act.getString(R.string.helpname),() -> tk.glucodata.help.help(helpString,act));
    }
public MenuItem help(String helpText) {
    return action(R.drawable.ic_jm_help,act.getString(R.string.helpname),() -> tk.glucodata.help.help(helpText,act));
    }

/* ---------- insets and keyboard ---------- */

private void applyInsets() {
    toolbar.setPadding(insetLeft,insetTop,insetRight,0);
    ViewGroup.LayoutParams params=toolbar.getLayoutParams();
    params.height=insetTop+dp(64); // M3 small top app bar
    toolbar.setLayoutParams(params);
    final int bottom=bottomBar!=null?0:insetBottom;
    scroll.setPadding(insetLeft,0,insetRight,bottom);
    if(bottomBar!=null)
        bottomBar.setPadding(insetLeft+dp(16),dp(12),insetRight+dp(16),insetBottom+dp(12));
    }

private void handleInsets() {
    insetLeft=MainActivity.systembarLeft;
    insetTop=MainActivity.systembarTop;
    insetRight=MainActivity.systembarRight;
    insetBottom=MainActivity.systembarBottom;
    applyInsets();
    ViewCompat.setOnApplyWindowInsetsListener(root,(v,windowInsets) -> {
        Insets bars=windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
        Insets ime=windowInsets.getInsets(WindowInsetsCompat.Type.ime());
        insetLeft=bars.left;
        insetTop=bars.top;
        insetRight=bars.right;
        insetBottom=Math.max(bars.bottom,ime.bottom);
        applyInsets();
        if(ime.bottom>0)
            scroll.post(this::showFocused);
        return windowInsets;
        });
    root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View v) {
            ViewCompat.requestApplyInsets(v);
            }
        @Override
        public void onViewDetachedFromWindow(@NonNull View v) { }
        });
    final int flat=color(com.google.android.material.R.attr.colorSurface);
    final int lifted=color(com.google.android.material.R.attr.colorSurfaceContainer);
    scroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)(sv,x,y,ox,oy) ->
        toolbar.setBackgroundColor(y>0?lifted:flat));
    }

/* Keep the focused field above the keyboard. */
private void showFocused() {
    View focus=root.findFocus();
    if(focus==null)
        return;
    android.graphics.Rect r=new android.graphics.Rect();
    focus.getDrawingRect(r);
    scroll.offsetDescendantRectToMyCoords(focus,r);
    final int visible=scroll.getHeight()-scroll.getPaddingBottom();
    final int margin=dp(24);
    if(r.bottom+margin>scroll.getScrollY()+visible)
        scroll.smoothScrollTo(0,r.bottom+margin-visible);
    else if(r.top-margin<scroll.getScrollY())
        scroll.smoothScrollTo(0,Math.max(0,r.top-margin));
    }

public void hideKeyboard() {
    View focus=root.findFocus();
    InputMethodManager imm=(InputMethodManager)act.getSystemService(Context.INPUT_METHOD_SERVICE);
    if(imm!=null&&focus!=null)
        imm.hideSoftInputFromWindow(focus.getWindowToken(),0);
    if(focus!=null)
        focus.clearFocus();
    }

/* ---------- refreshing ---------- */

public void refresher(Runnable r) {
    refreshers.add(r);
    }
/* Re-read every value shown; also run when the page comes on top again. */
public void refresh() {
    updating=true;
    try {
        for(Runnable r:refreshers)
            r.run();
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"refresh",th);
        }
    updating=false;
    }

/* ---------- sections ---------- */

/* Titled card; rows go into the returned layout.  Title may be null. */
public LinearLayout section(@Nullable CharSequence title) {
    View sec=LayoutInflater.from(ctx).inflate(R.layout.material_settings_section,list,false);
    TextView head=sec.findViewById(R.id.jm_section_title);
    if(title==null||title.length()==0)
        head.setVisibility(View.GONE);
    else
        head.setText(title);
    list.addView(sec);
    return sec.findViewById(R.id.jm_section_items);
    }
public LinearLayout section(int title) {
    return section(act.getString(title));
    }

/* The card around a section, to hide or show the whole section. */
public static View sectionView(LinearLayout items) {
    return (View)items.getParent().getParent();
    }

/* Supporting text, inside a section (parent!=null) or between sections. */
public TextView note(@Nullable LinearLayout parent,CharSequence text) {
    TextView view=new TextView(ctx);
    view.setText(text);
    view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    view.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
    view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    if(parent!=null) {
        view.setPadding(dp(16),dp(8),dp(16),dp(8));
        parent.addView(view,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        }
    else {
        view.setPadding(dp(16),dp(8),dp(16),0);
        list.addView(view,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        }
    return view;
    }

/* Large centred message for empty lists. */
public View empty(LinearLayout parent,int icon,CharSequence title,@Nullable CharSequence body) {
    LinearLayout box=new LinearLayout(ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    box.setPadding(dp(24),dp(32),dp(24),dp(32));
    if(icon!=0) {
        ImageView image=new ImageView(ctx);
        image.setImageResource(icon);
        image.setColorFilter(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(image,new LinearLayout.LayoutParams(dp(40),dp(40)));
        }
    TextView head=new TextView(ctx);
    head.setText(title);
    head.setGravity(Gravity.CENTER_HORIZONTAL);
    head.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    head.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
    head.setPadding(0,dp(12),0,0);
    box.addView(head);
    if(body!=null) {
        TextView text=new TextView(ctx);
        text.setText(body);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        text.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        text.setPadding(0,dp(4),0,0);
        box.addView(text);
        }
    parent.addView(box,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return box;
    }

/* ---------- list rows ---------- */

/* Plain list row: optional leading icon, title, optional summary, and an
 * empty end slot (jm_row_end) for a trailing control. */
public ViewGroup row(LinearLayout parent,int icon,CharSequence title,@Nullable CharSequence summary) {
    ViewGroup row=(ViewGroup)LayoutInflater.from(ctx).inflate(R.layout.material_settings_row,parent,false);
    ImageView iconView=row.findViewById(R.id.jm_row_icon);
    if(icon!=0)
        iconView.setImageResource(icon);
    else
        iconView.setVisibility(View.GONE);
    ((TextView)row.findViewById(R.id.jm_row_title)).setText(title);
    setSummary(row,summary);
    parent.addView(row);
    return row;
    }

public static void setSummary(View row,@Nullable CharSequence summary) {
    TextView view=row.findViewById(R.id.jm_row_summary);
    if(summary==null||summary.length()==0)
        view.setVisibility(View.GONE);
    else {
        view.setText(summary);
        view.setVisibility(View.VISIBLE);
        }
    }
public static void setTitle(View row,CharSequence title) {
    ((TextView)row.findViewById(R.id.jm_row_title)).setText(title);
    }
public static FrameLayout end(View row) {
    return row.findViewById(R.id.jm_row_end);
    }

/* Row that opens something else; chevron at the end. */
public ViewGroup link(LinearLayout parent,int icon,CharSequence title,@Nullable CharSequence summary,Runnable click) {
    ViewGroup row=row(parent,icon,title,summary);
    ImageView chevron=new ImageView(ctx);
    chevron.setImageResource(R.drawable.ic_jm_chevron_right);
    chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    chevron.setColorFilter(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
    if(act.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL)
        chevron.setScaleX(-1f);
    end(row).addView(chevron);
    row.setOnClickListener(v -> click.run());
    return row;
    }

/* Row that does something right away (no chevron). */
public ViewGroup item(LinearLayout parent,int icon,CharSequence title,@Nullable CharSequence summary,@Nullable Runnable click) {
    ViewGroup row=row(parent,icon,title,summary);
    if(click!=null)
        row.setOnClickListener(v -> click.run());
    else {
        row.setBackground(null);
        row.setClickable(false);
        }
    return row;
    }

/* Row with a switch; the whole row toggles it. */
public MaterialSwitch toggle(LinearLayout parent,int icon,CharSequence title,@Nullable CharSequence summary,BoolGet get,BoolSet set) {
    ViewGroup row=row(parent,icon,title,summary);
    MaterialSwitch sw=new MaterialSwitch(ctx);
    sw.setClickable(false);
    sw.setFocusable(false);
    sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    sw.setChecked(get.get());
    sw.setOnCheckedChangeListener((b,isChecked) -> {
        if(!updating)
            set.set(isChecked);
        });
    end(row).addView(sw);
    row.setOnClickListener(v -> { if(row.isEnabled()) sw.toggle(); });
    ViewCompat.setAccessibilityDelegate(row,new AccessibilityDelegateCompat() {
        @Override
        public void onInitializeAccessibilityNodeInfo(@NonNull View host,@NonNull AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host,info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(sw.isChecked());
            }
        });
    refreshers.add(() -> sw.setChecked(get.get()));
    return sw;
    }

/* Row whose switch enables a feature that also has its own settings page:
 * tapping the text opens the page, the switch turns it on and off. */
public MaterialSwitch toggleLink(LinearLayout parent,int icon,CharSequence title,@Nullable CharSequence summary,BoolGet get,BoolSet set,Runnable open) {
    ViewGroup row=row(parent,icon,title,summary);
    MaterialSwitch sw=new MaterialSwitch(ctx);
    sw.setChecked(get.get());
    sw.setOnCheckedChangeListener((b,isChecked) -> {
        if(!updating)
            set.set(isChecked);
        });
    sw.setContentDescription(title);
    View divider=new View(ctx);
    divider.setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant));
    LinearLayout endBox=new LinearLayout(ctx);
    endBox.setGravity(Gravity.CENTER_VERTICAL);
    endBox.addView(divider,new LinearLayout.LayoutParams(dp(1),dp(32)));
    LinearLayout.LayoutParams swParams=new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT);
    swParams.setMarginStart(dp(16));
    endBox.addView(sw,swParams);
    end(row).addView(endBox);
    row.setOnClickListener(v -> open.run());
    refreshers.add(() -> sw.setChecked(get.get()));
    return sw;
    }

/* Single choice from a few options as segmented buttons on its own line. */
public MaterialButtonToggleGroup segmented(LinearLayout parent,@Nullable CharSequence label,CharSequence[] items,int selected,IndexSet set) {
    LinearLayout box=fieldBox(parent,label);
    MaterialButtonToggleGroup group=new MaterialButtonToggleGroup(ctx);
    group.setSingleSelection(true);
    final int[] ids=new int[items.length];
    for(int i=0;i<items.length;i++) {
        MaterialButton b=new MaterialButton(ctx,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
        ids[i]=View.generateViewId();
        b.setId(ids[i]);
        b.setText(items[i]);
        b.setMaxLines(1);
        b.setPadding(dp(8),b.getPaddingTop(),dp(8),b.getPaddingBottom());
        group.addView(b,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
        }
    if(selected>=0&&selected<items.length)
        group.check(ids[selected]);
    group.setSelectionRequired(true);
    group.addOnButtonCheckedListener((g,id,isChecked) -> {
        if(updating||!isChecked)
            return;
        for(int i=0;i<ids.length;i++)
            if(ids[i]==id)
                set.set(i);
        });
    box.addView(group,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return group;
    }

/* ---------- fields ---------- */

private LinearLayout fieldBox(LinearLayout parent,@Nullable CharSequence label) {
    LinearLayout box=new LinearLayout(ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16),dp(8),dp(16),dp(8));
    if(label!=null&&label.length()>0) {
        TextView head=new TextView(ctx);
        head.setText(label);
        head.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        head.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        head.setPadding(0,0,0,dp(8));
        box.addView(head);
        }
    parent.addView(box,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return box;
    }

private TextInputLayout textLayout(boolean dropdown) {
    final int style=dropdown?com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_ExposedDropdownMenu
        :com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox;
    Context wrapped=new ContextThemeWrapper(ctx,style);
    TextInputLayout layout=new TextInputLayout(wrapped,null,dropdown?com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle:com.google.android.material.R.attr.textInputOutlinedStyle);
    if(dropdown)
        layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
    return layout;
    }

/* Outlined text field with floating label.  onDone runs when the value is
 * changed and the field loses focus or the keyboard action is pressed, not on
 * every key; null means read with field.getText() at Save. */
public TextInputEditText field(LinearLayout parent,CharSequence label,@Nullable CharSequence value,int inputType,@Nullable CharSequence suffix,@Nullable TextSet onDone) {
    TextInputLayout layout=textLayout(false);
    layout.setHint(label);
    if(suffix!=null)
        layout.setSuffixText(suffix);
    if((inputType&InputType.TYPE_MASK_VARIATION)==InputType.TYPE_TEXT_VARIATION_PASSWORD)
        layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
    TextInputEditText edit=new TextInputEditText(layout.getContext());
    edit.setInputType(inputType);
    edit.setSingleLine(true);
    edit.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    if(value!=null)
        edit.setText(value);
    layout.addView(edit,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    params.setMargins(dp(16),dp(8),dp(16),dp(8));
    parent.addView(layout,params);
    if(onDone!=null) {
        final String[] last={value==null?"":value.toString()};
        Runnable commit=() -> {
            String now=edit.getText()==null?"":edit.getText().toString();
            if(!updating&&!now.equals(last[0])) {
                last[0]=now;
                onDone.set(now);
                }
            };
        edit.setOnFocusChangeListener((v,has) -> { if(!has) commit.run(); });
        edit.setOnEditorActionListener((v,actionId,ev) -> {
            commit.run();
            hideKeyboard();
            return true;
            });
        onClose.add(0,commit); // also save a value still being typed when Back is pressed
        }
    edit.setOnFocusChangeListener(chain(edit.getOnFocusChangeListener(),(v,has) -> { if(has) scroll.postDelayed(this::showFocused,300); }));
    return edit;
    }

private static View.OnFocusChangeListener chain(@Nullable View.OnFocusChangeListener a,View.OnFocusChangeListener b) {
    if(a==null)
        return b;
    return (v,has) -> { a.onFocusChange(v,has); b.onFocusChange(v,has); };
    }

/* Error shown under a field (null clears). */
public static void error(TextInputEditText edit,@Nullable CharSequence message) {
    ViewGroup p=(ViewGroup)edit.getParent();
    while(p!=null&&!(p instanceof TextInputLayout))
        p=(ViewGroup)p.getParent();
    if(p!=null)
        ((TextInputLayout)p).setError(message);
    }

public static String text(TextInputEditText edit) {
    return edit.getText()==null?"":edit.getText().toString().trim();
    }

/* Number with locale decimal comma accepted; NaN if empty or invalid. */
public static float number(TextInputEditText edit) {
    String s=text(edit).replace(',','.');
    if(s.isEmpty())
        return Float.NaN;
    try { return Float.parseFloat(s); }
    catch(NumberFormatException e) { return Float.NaN; }
    }

/* Exposed dropdown menu (M3 replacement for Spinner). */
public MaterialAutoCompleteTextView choice(LinearLayout parent,CharSequence label,List<? extends CharSequence> items,int selected,IndexSet set) {
    TextInputLayout layout=textLayout(true);
    layout.setHint(label);
    MaterialAutoCompleteTextView auto=new MaterialAutoCompleteTextView(layout.getContext());
    auto.setInputType(InputType.TYPE_NULL);
    auto.setKeyListener(null);
    ArrayAdapter<CharSequence> adapter=new ArrayAdapter<>(layout.getContext(),com.google.android.material.R.layout.m3_auto_complete_simple_item,new ArrayList<>(items)) {
        @NonNull @Override
        public android.widget.Filter getFilter() { // never filter: show every option
            return new android.widget.Filter() {
                @Override protected FilterResults performFiltering(CharSequence c) { FilterResults r=new FilterResults(); r.values=items; r.count=items.size(); return r; }
                @Override protected void publishResults(CharSequence c,FilterResults r) { notifyDataSetChanged(); }
                };
            }
        };
    auto.setAdapter(adapter);
    if(selected>=0&&selected<items.size())
        auto.setText(items.get(selected),false);
    auto.setOnItemClickListener((p,v,pos,id) -> {
        if(!updating)
            set.set(pos);
        });
    layout.addView(auto,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    params.setMargins(dp(16),dp(8),dp(16),dp(8));
    parent.addView(layout,params);
    return auto;
    }
public MaterialAutoCompleteTextView choice(LinearLayout parent,CharSequence label,CharSequence[] items,int selected,IndexSet set) {
    return choice(parent,label,java.util.Arrays.asList(items),selected,set);
    }
public static void select(MaterialAutoCompleteTextView auto,CharSequence item) {
    auto.setText(item,false);
    }

/* Slider with label and the current value at the end of the label line. */
public Slider slider(LinearLayout parent,CharSequence label,float from,float to,float step,float value,FloatText format,@Nullable FloatSet set) {
    LinearLayout box=new LinearLayout(ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16),dp(8),dp(16),0);
    LinearLayout head=new LinearLayout(ctx);
    TextView name=new TextView(ctx);
    name.setText(label);
    name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    name.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
    head.addView(name,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
    TextView shown=new TextView(ctx);
    shown.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
    shown.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
    head.addView(shown);
    box.addView(head,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    Slider slider=new Slider(ctx);
    slider.setValueFrom(from);
    slider.setValueTo(to);
    if(step>0)
        slider.setStepSize(step);
    float v=Math.max(from,Math.min(to,value));
    if(step>0)
        v=from+Math.round((v-from)/step)*step;
    slider.setValue(v);
    slider.setLabelFormatter(format::text);
    slider.setTickVisible(false); // M3: no stop dots for fine continuous values
    slider.setTrackInactiveTintList(ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOutlineVariant)));
    slider.setContentDescription(label);
    shown.setText(format.text(v));
    slider.addOnChangeListener((s,val,fromUser) -> {
        shown.setText(format.text(val));
        if(fromUser&&!updating&&set!=null)
            set.set(val);
        });
    box.addView(slider,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    parent.addView(box,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return slider;
    }

/* ---------- buttons ---------- */

public MaterialButton button(Style style,CharSequence text,int icon,Runnable click) {
    final int attr;
    switch(style) {
        case FILLED: attr=com.google.android.material.R.attr.materialButtonStyle; break;
        case OUTLINED: attr=com.google.android.material.R.attr.materialButtonOutlinedStyle; break;
        case TEXT: attr=com.google.android.material.R.attr.borderlessButtonStyle; break;
        default: attr=0;
        }
    MaterialButton b=style==Style.TONAL?new MaterialButton(new ContextThemeWrapper(ctx,com.google.android.material.R.style.Widget_Material3_Button_TonalButton),null,0)
        :new MaterialButton(ctx,null,attr);
    if(style==Style.TONAL) {
        b.setBackgroundTintList(ColorStateList.valueOf(color(com.google.android.material.R.attr.colorSecondaryContainer)));
        b.setTextColor(color(com.google.android.material.R.attr.colorOnSecondaryContainer));
        b.setIconTint(ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSecondaryContainer)));
        b.setCornerRadius(dp(20));
        b.setMinHeight(dp(40));
        b.setInsetTop(dp(4));
        b.setInsetBottom(dp(4));
        }
    b.setText(text);
    b.setAllCaps(false);
    if(icon!=0) {
        b.setIconResource(icon);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        }
    b.setOnClickListener(v -> click.run());
    return b;
    }

/* Row of buttons aligned to the end (Material dialog/card action style),
 * wrapping onto a second line if they do not fit. */
public ViewGroup buttons(LinearLayout parent,MaterialButton... buttons) {
    com.google.android.material.chip.ChipGroup flow=new com.google.android.material.chip.ChipGroup(ctx);
    flow.setChipSpacingHorizontal(dp(8));
    flow.setChipSpacingVertical(0);
    for(MaterialButton b:buttons)
        flow.addView(b);
    LinearLayout box=new LinearLayout(ctx);
    box.setGravity(Gravity.END);
    box.setPadding(dp(16),dp(4),dp(16),dp(8));
    box.addView(flow,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
    (parent!=null?parent:list).addView(box,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return box;
    }

/* Sticky bar under the list, for Save/Cancel style actions. */
public LinearLayout bottomBar(MaterialButton... buttons) {
    if(bottomBar==null) {
        com.google.android.material.divider.MaterialDivider divider=new com.google.android.material.divider.MaterialDivider(ctx);
        ((LinearLayout)root).addView(divider,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        bottomBar=new LinearLayout(ctx);
        bottomBar.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer));
        ((LinearLayout)root).addView(bottomBar,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        }
    for(MaterialButton b:buttons) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT);
        params.setMarginStart(dp(8));
        bottomBar.addView(b,params);
        }
    applyInsets();
    return bottomBar;
    }

/* Any other view, full width inside a section or the page. */
public <T extends View> T custom(@Nullable LinearLayout parent,T view) {
    (parent!=null?parent:list).addView(view,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return view;
    }

/* ---------- dialogs ---------- */

public static MaterialAlertDialogBuilder dialog(Context base) {
    return new MaterialAlertDialogBuilder(themed(base));
    }
public static void confirm(Context base,CharSequence title,@Nullable CharSequence message,CharSequence yes,Runnable onYes) {
    MaterialAlertDialogBuilder b=dialog(base).setTitle(title).setPositiveButton(yes,(d,w) -> onYes.run()).setNegativeButton(android.R.string.cancel,null);
    if(message!=null)
        b.setMessage(message);
    b.show();
    }
public static void message(Context base,@Nullable CharSequence title,CharSequence message) {
    MaterialAlertDialogBuilder b=dialog(base).setMessage(message).setPositiveButton(android.R.string.ok,null);
    if(title!=null)
        b.setTitle(title);
    b.show();
    }

public void toast(CharSequence text) {
    com.google.android.material.snackbar.Snackbar.make(root,text,com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
    }
}
