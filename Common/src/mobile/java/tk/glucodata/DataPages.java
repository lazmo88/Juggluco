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

/* Registers the data area phone pages (called from m3.DataScreens); here so
 * the pages can stay package-private in tk.glucodata. */
public final class DataPages {
private DataPages() {}

public static void register() {
    PhoneUi.register("amount",(act,args) -> AmountPage.show(act,(Long)args[0],(Integer)args[1]));
    PhoneUi.register("amountlist",(act,args) -> AmountListPage.show(act));
    PhoneUi.register("search",(act,args) -> SearchPage.show(act));
    PhoneUi.register("datepick",(act,args) -> {
        final long tim=(Long)args[0];
        tk.glucodata.m3.DataPickers.date(act,act.getString(R.string.date),tim,(year,month,day) -> {
            Natives.movedate(tim,year,month,day);
            act.requestRender();
            },null);
        });
    }
}
