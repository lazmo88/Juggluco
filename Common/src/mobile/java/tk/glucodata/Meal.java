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


package tk.glucodata;

/* Meals on phones.  The screens (meal items, item editor, rounding,
 * ingredients, food database, nutrients) are Material 3 pages since the
 * redesign: MealPage, IngredientPage and FoodPage.  This class keeps the
 * entry point used by AmountPage and NumberView, and the shared helpers.
 * Wear OS has its own Meal.java. */
class Meal {
private static final String LOG_ID="Meal";

/* Meal list of mealptr on top of the amount being edited.  give(carb,mealptr)
 * runs at once and after every change, endrun when the list closes.  The old
 * floating Layout is gone; the page lives on MainActivity's back stack, so
 * there is no view to return (NumberView's mealview stays null). */
static Layout menuview(final NumberView numb,MainActivity act,int mealptr,ObjIntConsumer<Float> give,Runnable endrun) {
    MealPage.show(act,numb,mealptr,give,endrun);
    return null;
    }

public static String ondecimal(final float fl,final float nr) {
    return Float.toString(Math.round(fl*nr)/nr);
    }

static String[] compnames=Natives.getcomponentlabels( );
static String[] compunits=Natives.getcomponentunits( );
}
