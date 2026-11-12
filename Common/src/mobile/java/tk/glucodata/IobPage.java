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

import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;

import tk.glucodata.m3.M3Page;

/* Phone IOB (IOB.mkview): the IOB switch and the insulin type of each
 * label.  Both apply at once, as in the old screen (setIOB, setInsulinType). */
final class IobPage {
private IobPage() {}

static final int[] TYPES={R.string.not,R.string.humaninsulin,R.string.aspart,R.string.lispro,R.string.glulisine,R.string.fiasp,R.string.urli,R.string.afrezza};

static String typeName(MainActivity act,int type) {
    return type>=0&&type<TYPES.length?act.getString(TYPES[type]):String.valueOf(type);
    }

static void show(MainActivity act) {
    M3Page page=M3Page.open(act,"IOB");
    page.help(R.string.IOBhelp);
    LinearLayout top=page.section((CharSequence)null);
    final MaterialSwitch[] iob={null};
    iob[0]=page.toggle(top,R.drawable.ic_jm_vaccines,act.getString(R.string.jm_data_iobsummary),null,Natives::getIOB,on -> {
        if(on&&!Natives.setIOB(true)) {
            page.updating=true;
            iob[0].setChecked(false);
            page.updating=false;
            tk.glucodata.help.help(R.string.IOBhelp,act);
            }
        else if(!on)
            Natives.setIOB(false);
        });

    ArrayList<String> labels=AmountPage.labelNames();
    LinearLayout sec=page.section(R.string.jm_data_insulintypes);
    if(labels.isEmpty())
        page.empty(sec,R.drawable.ic_jm_label,act.getString(R.string.jm_data_nolabels),null);
    for(int i=0;i<labels.size();i++) {
        final int index=i;
        final String name=labels.get(i);
        final View[] row={null};
        row[0]=page.link(sec,R.drawable.ic_jm_label,name,typeName(act,Natives.getInsulinType(index)),() -> {
            CharSequence[] items=new CharSequence[TYPES.length];
            for(int t=0;t<TYPES.length;t++)
                items[t]=act.getString(TYPES[t]);
            M3Page.dialog(act)
                .setTitle(name)
                .setSingleChoiceItems(items,Natives.getInsulinType(index),(d,which) -> {
                    Natives.setInsulinType(index,which);
                    M3Page.setSummary(row[0],typeName(act,which));
                    d.dismiss();
                    })
                .setNegativeButton(R.string.cancel,null)
                .show();
            });
        }
    }
}
