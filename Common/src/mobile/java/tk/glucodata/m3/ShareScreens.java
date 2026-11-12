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

import tk.glucodata.PhoneUi;

/* Exchanging data: broadcasts, mirror, web server, Nightscout, LibreView, Clarity, watches, Health Connect, export. */
final class ShareScreens {
private ShareScreens() {}

static void register() {
    PhoneUi.register("settings.exchanges",(act,args) -> tk.glucodata.settings.ExchangePage.show(act));
    PhoneUi.register("nightpost",(act,args) -> tk.glucodata.UploaderPage.show(act));
    }
}
