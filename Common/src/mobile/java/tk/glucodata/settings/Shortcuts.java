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
/*                                                                                   */
/*      Fri Jan 27 15:32:11 CET 2023                                                 */


package tk.glucodata.settings;

import android.text.InputType;
import android.widget.LinearLayout;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;

import tk.glucodata.Applic;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.m3.M3Page;
import tk.glucodata.m3.TimeDialog;

import static tk.glucodata.Log.doLog;

/*
 * Shortcuts for the Garmin watch app (Kerfstok) as Material 3 pages: a list
 * of name/value items, tap to edit, New to add.  As before, nothing is
 * stored until Save on the list; Back discards (after asking, if something
 * was changed).
 */
public class Shortcuts {
private static final String LOG_ID="Shortcuts";

private ArrayList<ArrayList<Object>> shortcuts;
private boolean changed=false;
private M3Page page;
private LinearLayout sec;

public void mkshortlistview(MainActivity act) {
    shortcuts=Natives.getShortcuts();
    if(shortcuts==null)
        shortcuts=new ArrayList<>();
    if(doLog) {Log.i(LOG_ID,"mkshortlistview "+shortcuts.size());};
    page=M3Page.open(act,R.string.shutcuts);
    page.help(R.string.shortcuthelp);
    sec=page.section((CharSequence)null);
    fill();
    page.closeCheck(() -> {
        if(!changed)
            return true;
        TimeDialog.show(M3Page.dialog(act).setTitle(R.string.jm_alerts_discard)
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.jm_alerts_discard_yes,(d,w) -> page.dismiss()));
        return false;
        });
    page.bottomBar(
        page.button(M3Page.Style.TONAL,act.getString(R.string.newname),R.drawable.ic_jm_add,() -> edit(act,null)),
        page.button(M3Page.Style.FILLED,act.getString(R.string.save),0,() -> saveall(act)));
    }

private void fill() {
    sec.removeAllViews();
    if(shortcuts.isEmpty()) {
        page.empty(sec,R.drawable.ic_jm_bolt,page.act.getString(R.string.jm_alerts_shortcuts_empty),page.act.getString(R.string.jm_alerts_shortcuts_empty_body));
        return;
        }
    for(ArrayList<Object> el:shortcuts)
        page.link(sec,R.drawable.ic_jm_bolt,(String)el.get(0),(String)el.get(1),() -> edit(page.act,el));
    }

/* The old saveall(). */
private void saveall(MainActivity act) {
    if(changed) {
        final int nr=shortcuts.size();
        for(int i=0;i<nr;i++) {
            ArrayList<Object> el=shortcuts.get(i);
            int ret;
            if((ret=Natives.setShortcut(i,(String)el.get(0),(String)el.get(1)))!=-1) {
                if(ret==-5)
                    page.toast(act.getString(R.string.jm_alerts_index_too_large));
                else
                    page.toast(act.getString(R.string.jm_alerts_too_long,(String)el.get(ret)));
                return;
                }
            }
        Natives.setnrshortcuts(nr);
        Applic app=(Applic)act.getApplication();
        if(app.numdata!=null)
            app.numdata.sendshortcuts(shortcuts);
        Applic.wakemirrors();
        }
    page.dismiss();
    }

/* The old mkshort(): current==null is a new shortcut. */
private void edit(MainActivity act,ArrayList<Object> current) {
    final M3Page sub=M3Page.open(act,current==null?R.string.jm_alerts_new_shortcut:R.string.shortcut);
    sub.help(R.string.shortcuthelp);
    LinearLayout box=sub.section((CharSequence)null);
    final TextInputEditText labedit=sub.field(box,act.getString(R.string.jm_alerts_name),current==null?"":(String)current.get(0),
        InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,null,null);
    /* Values can be numbers or operators with numbers (e.g. *0.5). */
    final TextInputEditText valedit=sub.field(box,act.getString(R.string.value),current==null?"":(String)current.get(1),InputType.TYPE_CLASS_PHONE,null,null);
    if(current!=null) {
        sub.bottomBar(sub.button(M3Page.Style.TEXT,act.getString(R.string.delete),R.drawable.ic_jm_delete,() -> {
            int index=shortcuts.indexOf(current);
            if(index>=0) {
                shortcuts.remove(index);
                changed=true;
                fill();
                }
            sub.dismiss();
            }));
        }
    sub.bottomBar(sub.button(M3Page.Style.FILLED,act.getString(R.string.ok),0,() -> {
        String label=M3Page.text(labedit);
        String values=M3Page.text(valedit);
        if(current==null) {
            ArrayList<Object> el=new ArrayList<>();
            el.add(label);
            el.add(values);
            shortcuts.add(el);
            }
        else {
            current.set(0,label);
            current.set(1,values);
            }
        changed=true;
        fill();
        sub.dismiss();
        }));
    }
}
