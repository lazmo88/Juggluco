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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import tk.glucodata.Natives;

/*
 * Light or dark: follow Android's dark theme, unless the user chose one.
 *
 * The app itself only knows "dark mode" on or off (Natives.getInvertColors(),
 * used by the graph and by every Material 3 screen).  Following the system
 * means writing the system's state into that setting at start and whenever
 * Android's dark theme changes.  Choosing Light or Dark (Appearance in the menu) stops the following.
 */
public final class ThemeMode {
private ThemeMode() {}

public static final int SYSTEM=0,LIGHT=1,DARK=2;

private static final String PREFS="jm_ui",KEY="theme_mode";

private static SharedPreferences prefs(Context ctx) {
    return ctx.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

public static int get(Context ctx) {
    final int mode=prefs(ctx).getInt(KEY,SYSTEM);
    return mode==LIGHT||mode==DARK?mode:SYSTEM;
    }

private static boolean systemDark(Context ctx) {
    return (ctx.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }

/* Makes the app's dark mode agree with the chosen mode.  Returns true when
 * it changed, so the caller can rebuild what is on screen. */
public static boolean sync(Context ctx) {
    final int mode=get(ctx);
    final boolean dark=mode==SYSTEM?systemDark(ctx):mode==DARK;
    if(Natives.getInvertColors()==dark)
        return false;
    Natives.setInvertColors(dark);
    return true;
    }

/* Returns true when the colours changed. */
public static boolean set(Context ctx,int mode) {
    prefs(ctx).edit().putInt(KEY,mode).apply();
    return sync(ctx);
    }
}
