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

package tk.glucodata;

import java.util.HashMap;

/*
 * Hook from shared (main) code to the phone's Material 3 screens.
 *
 * Code in src/main is also built for Wear OS, which does not have the phone
 * screens (src/mobile/java/tk/glucodata/m3).  A shared screen starts with
 *
 *     if(PhoneUi.show("talker",act)) return;
 *
 * and keeps its old code for the watch.  The phone build registers the
 * replacements at start (m3.Screens.register()).  Names are the old entry
 * points, lower case, e.g. "talker", "settings.alarms".
 */
public final class PhoneUi {
private PhoneUi() {}

public interface Screen {
    void show(MainActivity act,Object[] args);
    }

private static final HashMap<String,Screen> screens=new HashMap<>();

public static void register(String name,Screen screen) {
    screens.put(name,screen);
    }

public static boolean has(String name) {
    return !Applic.isWearable&&screens.containsKey(name);
    }

/* Shows the Material 3 screen and returns true, or returns false when there
 * is none (watch, or not converted), so the caller shows its old screen. */
public static boolean show(String name,MainActivity act,Object... args) {
    if(Applic.isWearable)
        return false;
    Screen screen=screens.get(name);
    if(screen==null)
        return false;
    try {
        screen.show(act,args);
        return true;
        }
    catch(Throwable th) {
        Log.stack("PhoneUi","show "+name,th);
        return false;
        }
    }
}
