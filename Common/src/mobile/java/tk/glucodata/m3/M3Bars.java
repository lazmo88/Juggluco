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

import static android.view.View.VISIBLE;
import static tk.glucodata.Natives.getInvertColors;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import tk.glucodata.GlucoseCurve;
import tk.glucodata.MainActivity;
import tk.glucodata.R;

/*
 * Status and navigation bar icon colour for whatever full screen view is on
 * top: dark icons on light screens, light icons on dark screens.  One global
 * layout listener on the content view, installed once per activity.
 * Views can say what they are with setTag(R.id.jm_bars_light,Boolean).
 * The native graph (GlucoseCurve) keeps managing the bars itself.
 */
public final class M3Bars {
private M3Bars() {}

public static void watch(MainActivity act) {
    View content=act.findViewById(android.R.id.content);
    if(content==null||content.getTag(R.id.jm_bars_light)!=null)
        return;
    content.setTag(R.id.jm_bars_light,Boolean.TRUE); // installed
    ViewTreeObserver.OnGlobalLayoutListener listener=() -> update(act);
    content.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

private static boolean appThemeIsLight(MainActivity act) {
    TypedValue value=new TypedValue();
    return act.getTheme().resolveAttribute(R.attr.isAppLightTheme,value,true)&&value.data!=0;
    }

/* Colour of a full screen view, looking through views without a background. */
private static Integer backgroundColor(View view) {
    for(int depth=0;view!=null&&depth<4;depth++) {
        Drawable d=view.getBackground();
        if(d instanceof ColorDrawable) {
            int col=((ColorDrawable)d).getColor();
            if((col>>>24)>=0xF0)
                return col;
            }
        if(d!=null)
            return null;
        if(!(view instanceof ViewGroup)||((ViewGroup)view).getChildCount()==0)
            return null;
        view=((ViewGroup)view).getChildAt(0);
        }
    return null;
    }

static void update(MainActivity act) {
    if(Build.VERSION.SDK_INT<23)
        return;
    View c=act.findViewById(android.R.id.content);
    if(!(c instanceof ViewGroup))
        return;
    ViewGroup content=(ViewGroup)c;
    final int height=content.getHeight();
    View top=null;
    for(int i=content.getChildCount()-1;i>=0;i--) {
        final View child=content.getChildAt(i);
        if(child.getVisibility()!=VISIBLE||child instanceof FloatingActionButton)
            continue;
        if(child.getTop()>0||child.getHeight()<height/2) // dialogs and panels
            continue;
        top=child;
        break;
        }
    if(top==null||top instanceof GlucoseCurve)
        return;
    final boolean light;
    Object tag=top.getTag(R.id.jm_bars_light);
    if(top.getTag(R.id.jm_m3_page)!=null)
        light=!getInvertColors();
    else if(tag instanceof Boolean)
        light=(Boolean)tag;
    else {
        Integer col=backgroundColor(top);
        light=col!=null?ColorUtils.calculateLuminance(col)>0.5:appThemeIsLight(act);
        }
    var win=act.getWindow();
    var controller=WindowCompat.getInsetsController(win,win.getDecorView());
    if(controller.isAppearanceLightStatusBars()!=light||controller.isAppearanceLightNavigationBars()!=light)
        act.lightBars(light);
    }
}
