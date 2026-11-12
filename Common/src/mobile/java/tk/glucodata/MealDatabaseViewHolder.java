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

package tk.glucodata;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/* One food row of FoodPage.database: tap shows its nutrients. */
public class MealDatabaseViewHolder extends RecyclerView.ViewHolder {
    MealDatabaseViewHolder(View view,FoodPage.Adapter adapt) {
        super(view);
        view.setOnClickListener(v -> {
            final int pos=getAbsoluteAdapterPosition();
            if(pos>=0&&pos<adapt.getItemCount())
                adapt.open(pos);
            });
        }
}
