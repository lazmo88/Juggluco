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

import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Locale;

import tk.glucodata.m3.M3Page;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/* Phone help viewer: every help text (first-run help, Help buttons, About)
 * as an M3 page with a close button, a search action and the HTML body in
 * the theme's text colours.  The index and language links in the texts stay
 * clickable.  OK at the bottom closes, like the old OK button. */
final class HelpPage {
private final M3Page page;
private final TextView body;
private final ArrayList<int[]> hits=new ArrayList<>();
private int current=-1;
private View searchBar;
private TextInputEditText query;
private TextView counter;

static void show(MainActivity act,String text,Consumer<ViewGroup> okproc) {
    new HelpPage(act,text,okproc);
    }

private HelpPage(MainActivity act,String text,Consumer<ViewGroup> okproc) {
    page=M3Page.open(act,R.string.helpname);
    page.toolbar.setNavigationIcon(R.drawable.ic_jm_close);
    page.toolbar.setNavigationIconTint(page.color(com.google.android.material.R.attr.colorOnSurface));
    page.toolbar.setNavigationContentDescription(R.string.closename);
    page.action(R.drawable.ic_jm_search,act.getString(R.string.search),this::toggleSearch);

    searchBar=makeSearchBar(act);
    searchBar.setVisibility(View.GONE);
    LinearLayout root=(LinearLayout)page.root;
    root.addView(searchBar,root.indexOfChild(page.toolbar)+1,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

    body=htmlBody(page,text);

    page.bottomBar(page.button(M3Page.Style.FILLED,act.getString(R.string.ok),0,page::close));
    page.onClose(() -> okproc.accept(page.root));
    }

/* Help HTML in the page's theme colours, links clickable. */
static TextView htmlBody(M3Page page,String text) {
    TextView body=new TextView(page.ctx);
    help.setHtmlIgnoringHtmlColors(body,text);
    body.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    body.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurface));
    body.setLinkTextColor(page.color(com.google.android.material.R.attr.colorPrimary));
    body.setLineSpacing(0f,1.15f);
    body.setTextIsSelectable(true);
    body.setMovementMethod(LinkMovementMethod.getInstance());
    body.setLinksClickable(true);
    body.setTextDirection(MainActivity.rtl?View.TEXT_DIRECTION_RTL:View.TEXT_DIRECTION_LTR);
    body.setPadding(page.dp(8),page.dp(8),page.dp(8),page.dp(8));
    page.custom(null,body);
    return body;
    }

private View makeSearchBar(MainActivity act) {
    LinearLayout bar=new LinearLayout(page.ctx);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(page.dp(16),0,page.dp(8),page.dp(8));
    bar.setBackgroundColor(page.color(com.google.android.material.R.attr.colorSurface));
    TextInputLayout layout=new TextInputLayout(page.ctx,null,com.google.android.material.R.attr.textInputOutlinedStyle);
    layout.setHint(act.getString(R.string.search));
    query=new TextInputEditText(layout.getContext());
    query.setSingleLine(true);
    query.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    layout.addView(query,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    bar.addView(layout,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
    counter=new TextView(page.ctx);
    counter.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
    counter.setTextColor(page.color(com.google.android.material.R.attr.colorOnSurfaceVariant));
    counter.setPadding(page.dp(8),0,page.dp(4),0);
    bar.addView(counter);
    bar.addView(icon(R.drawable.ic_jm_chevron_left,act.getString(R.string.jm_data_previous),v -> select(-1)));
    bar.addView(icon(R.drawable.ic_jm_chevron_right,act.getString(R.string.jm_data_next),v -> select(1)));
    query.addTextChangedListener(new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s,int a,int b,int c) {}
        @Override public void onTextChanged(CharSequence s,int a,int b,int c) {}
        @Override public void afterTextChanged(Editable s) { find(s.toString()); }
        });
    query.setOnEditorActionListener((v,id,ev) -> { select(1); page.hideKeyboard(); return true; });
    return bar;
    }

private MaterialButton icon(int icon,String desc,View.OnClickListener click) {
    MaterialButton b=new MaterialButton(page.ctx,null,com.google.android.material.R.attr.materialIconButtonStyle);
    b.setIconResource(icon);
    b.setIconTint(ColorStateList.valueOf(page.color(com.google.android.material.R.attr.colorOnSurfaceVariant)));
    b.setContentDescription(desc);
    b.setOnClickListener(click);
    return b;
    }

private void toggleSearch() {
    if(searchBar.getVisibility()==View.VISIBLE) {
        searchBar.setVisibility(View.GONE);
        query.setText("");
        page.hideKeyboard();
        return;
        }
    searchBar.setVisibility(View.VISIBLE);
    query.requestFocus();
    help.showkeyboard(page.act,query);
    }

private void clearSpans() {
    CharSequence t=body.getText();
    if(t instanceof Spannable) {
        Spannable sp=(Spannable)t;
        for(Object o:sp.getSpans(0,sp.length(),HitSpan.class))
            sp.removeSpan(o);
        }
    }
private static final class HitSpan extends BackgroundColorSpan {
    HitSpan(int color) { super(color); }
    }

private void find(String what) {
    clearSpans();
    hits.clear();
    current=-1;
    if(what.length()>0&&body.getText() instanceof Spannable) {
        String all=body.getText().toString().toLowerCase(Locale.getDefault());
        String q=what.toLowerCase(Locale.getDefault());
        for(int i=all.indexOf(q);i>=0;i=all.indexOf(q,i+Math.max(1,q.length())))
            hits.add(new int[]{i,i+q.length()});
        if(!hits.isEmpty())
            current=0;
        }
    paint();
    }
private void select(int step) {
    if(hits.isEmpty())
        return;
    current=(current+step+hits.size())%hits.size();
    paint();
    }
private void paint() {
    clearSpans();
    counter.setText(hits.isEmpty()?(M3Page.text(query).isEmpty()?"":"0/0"):(current+1)+"/"+hits.size());
    if(hits.isEmpty())
        return;
    Spannable sp=(Spannable)body.getText();
    final int all=page.color(com.google.android.material.R.attr.colorSecondaryContainer);
    final int cur=page.color(com.google.android.material.R.attr.colorTertiaryContainer);
    for(int i=0;i<hits.size();i++) {
        int[] h=hits.get(i);
        sp.setSpan(new HitSpan(i==current?cur:all),h[0],h[1],Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    final int start=hits.get(current)[0];
    body.post(() -> {
        android.text.Layout layout=body.getLayout();
        if(layout==null)
            return;
        int line=layout.getLineForOffset(start);
        int y=body.getTop()+body.getPaddingTop()+layout.getLineTop(line);
        page.scroll.smoothScrollTo(0,Math.max(0,y-page.dp(48)));
        });
    }
}
