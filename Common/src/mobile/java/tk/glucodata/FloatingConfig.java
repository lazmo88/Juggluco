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
/*      Fri Jan 27 15:31:32 CET 2023                                                 */


package tk.glucodata;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.usedlocale;
import static tk.glucodata.Floating.rewritefloating;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.screenheight;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;


import java.util.Locale;

import tk.glucodata.m3.M3Page;
import yuku.ambilwarna.AmbilWarnaDialog;

/*
 * Floating glucose settings as a Material 3 page (phone).  The overlay
 * itself is Floating; this page only configures it.  Every change applies at
 * once, as on the old screen.
 */
public class FloatingConfig {
private static final String LOG_ID="FloatingConfig";
/* Which colour the picker edits. */
static private boolean background=true;
static public void    setcolor(int c) {
        if(doLog) {Log.i(LOG_ID,"setcolor("+(c&0xFFFFFFFF)+")");};
        if(background) {
                Floating.setbackgroundcolor(c);
                }
        else  {
              Floating.setforegroundcolor(c);
              }
        }
static public int    getcolor() {
        return background?Natives.getfloatingbackground( ):Natives.getfloatingforeground( );
        }

static public void show(MainActivity act,View parent) {
    show(act);
    }

static public void show(MainActivity act) {
    final M3Page page=M3Page.open(act,R.string.floatglucose);
    page.help(R.string.floatingconfig);
    final boolean[] hidden={Natives.gethidefloatinJuggluco()};
    if(hidden[0]) {
        Floating.makefloat(); // visible while configuring
        }
    page.onClose(() -> {
        if(hidden[0]) {
            Floating.removeFloating();
            }
        });

    /* Show */
    LinearLayout showSec=page.section((CharSequence)null);
    page.toggle(showSec,R.drawable.ic_jm_picture_in_picture,act.getString(R.string.jm_alerts_show_floating),act.getString(R.string.jm_alerts_show_floating_summary),
        Natives::getfloatglucose,on -> Floating.setfloatglucose(act,on));
    page.toggle(showSec,R.drawable.ic_jm_layers,act.getString(R.string.jm_alerts_float_above),act.getString(R.string.jm_alerts_float_above_summary),
        () -> !hidden[0],on -> {
            hidden[0]=!on;
            Natives.sethidefloatinJuggluco(!on);
            });
    page.toggle(showSec,R.drawable.ic_jm_schedule,act.getString(R.string.time),act.getString(R.string.jm_alerts_float_time_summary),
        () -> Floating.showtime,on -> {
            Floating.showtime=on;
            Natives.setfloattime(on);
            rewritefloating(act);
            });
    page.toggle(showSec,R.drawable.ic_jm_touch_app,act.getString(R.string.touchable),act.getString(R.string.jm_alerts_float_touch_summary),
        Natives::getfloatingTouchable,Floating::setTouchable);
    /* Turning it on can go to Android's overlay permission screen; show the
     * real state when we are back. */
    final ViewTreeObserver.OnWindowFocusChangeListener focus=has -> {
        if(has&&page.isOpen())
            page.refresh();
        };
    page.root.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
    page.onClose(() -> {
        ViewTreeObserver obs=page.root.getViewTreeObserver();
        if(obs.isAlive())
            obs.removeOnWindowFocusChangeListener(focus);
        });

    /* Size */
    LinearLayout look=page.section(R.string.jm_alerts_section_look);
    final int height=GlucoseCurve.getheight();
    final int maxfont=Math.max(6,height*7/10);
    int currentfont=Natives.getfloatingFontsize();
    if(currentfont<5||currentfont>(int)(screenheight*.8)) {
        currentfont=(int)Notify.glucosesize;
        }
    page.slider(look,act.getString(R.string.fontsize),5,maxfont,1,currentfont,v -> String.format(Locale.getDefault(),"%d",Math.round(v)),v -> {
        Natives.setfloatingFontsize(Math.round(v));
        rewritefloating(act);
        });

    /* Colour: a row per colour; the picker opens in a dialog so it never
     * fights the page's scrolling.  Changes show live on the overlay, Cancel
     * puts the old colour back. */
    LinearLayout col=page.section(R.string.jm_alerts_section_colour);
    final ViewGroup[] rows={null,null};
    rows[0]=page.item(col,R.drawable.ic_jm_format_color_text,act.getString(R.string.foreground),hex(Natives.getfloatingforeground()),() -> pickColour(page,false,rows[0]));
    final boolean transp=Color.alpha(Natives.getfloatingbackground())!=0xFF;
    page.toggle(col,R.drawable.ic_jm_opacity,act.getString(R.string.transparent),act.getString(R.string.jm_alerts_transparent_summary),() -> Color.alpha(Natives.getfloatingbackground())!=0xFF,on -> {
        Floating.setbackgroundalpha(on?0:0xff);
        Floating.invalidatefloat();
        RingTonePage.enable(rows[1],!on);
        });
    rows[1]=page.item(col,R.drawable.ic_jm_format_color_fill,act.getString(R.string.background),hex(Natives.getfloatingbackground()),() -> pickColour(page,true,rows[1]));
    RingTonePage.enable(rows[1],!transp);
    swatch(page,rows[0],Natives.getfloatingforeground());
    swatch(page,rows[1],Natives.getfloatingbackground());
    }

static String hex(int c) {
    return String.format(Locale.US,"#%06X",c&0xFFFFFF);
    }

/* A round sample of the colour at the end of the row. */
static void swatch(M3Page page,ViewGroup row,int c) {
    ViewGroup end=row.findViewById(R.id.jm_row_end);
    if(end==null)
        return;
    View dot=end.findViewWithTag(LOG_ID);
    if(dot==null) {
        dot=new View(page.ctx);
        dot.setTag(LOG_ID);
        int size=page.dp(24);
        end.addView(dot,new FrameLayout.LayoutParams(size,size,Gravity.CENTER));
        }
    android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();
    g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    g.setColor(c|0xFF000000);
    g.setStroke(page.dp(1),page.color(com.google.android.material.R.attr.colorOutline));
    dot.setBackground(g);
    }

static void pickColour(M3Page page,boolean bg,ViewGroup row) {
    final MainActivity act=page.act;
    background=bg;
    final int old=getcolor();
    AmbilWarnaDialog picker=new AmbilWarnaDialog(page.ctx,old,c -> {
        if(doLog) {Log.i(LOG_ID,String.format(usedlocale,"col=%x",c));};
        background=bg;
        setcolor(c);
        Floating.invalidatefloat();
        M3Page.setSummary(row,hex(c));
        swatch(page,row,c);
        },v -> {});
    View view=picker.getview();
    View noclose=view.findViewById(R.id.closeambi);
    if(noclose!=null)
        noclose.setVisibility(View.GONE);
    View nohelp=view.findViewById(R.id.helpambi);
    if(nohelp!=null)
        nohelp.setVisibility(View.GONE);
    FrameLayout frame=new FrameLayout(page.ctx);
    frame.addView(view,new FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT,Gravity.CENTER_HORIZONTAL));
    frame.setPadding(0,page.dp(16),0,0);
    tk.glucodata.m3.TimeDialog.show(M3Page.dialog(act)
        .setTitle(bg?R.string.background:R.string.foreground)
        .setView(frame)
        .setNegativeButton(android.R.string.cancel,(d,w) -> {
            background=bg;
            setcolor(old);
            Floating.invalidatefloat();
            M3Page.setSummary(row,hex(old));
            swatch(page,row,old);
            })
        .setPositiveButton(android.R.string.ok,null));
    }
}
