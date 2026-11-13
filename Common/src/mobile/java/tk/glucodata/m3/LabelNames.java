package tk.glucodata.m3;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

import tk.glucodata.Applic;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;

/*
 * Earlier versions created the number labels of a new installation as words
 * cut off at the 11 characters a label can hold: "Carbohydra", "Fast Insuli",
 * "Long Insuli".  New installations now get whole words (jugglucotext.cpp).
 *
 * For an existing installation this renames, once, the labels that still have
 * exactly one of those three cut-off default names to the new default.  It is
 * the same call as editing the name under Settings → Number Labels
 * (Natives.setlabel with the label's own rounding and weight); amounts refer
 * to a label by its position, so nothing else changes.  Labels with any other
 * name are not touched, and neither are labels that are received from a
 * mirror (Natives.staticnum).  It runs once: a label that is renamed back by
 * hand stays as it is.
 */
public final class LabelNames {
private LabelNames() {}

private static final String LOG_ID="LabelNames";
private static final String PREFS="jm_ui",KEY="label_names_done";
private static final String[][] RENAME={
    {"Carbohydra","Carbs"},
    {"Fast Insuli","Fast-acting"},
    {"Long Insuli","Long-acting"}};

private static boolean checked=false;

public static void once(MainActivity act) {
    if(checked)
        return;
    checked=true;
    try {
        final SharedPreferences prefs=act.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(prefs.getBoolean(KEY,false))
            return;
        if(Natives.staticnum())
            return; // labels come from the mirror; ask again at a later start
        final ArrayList<String> labels=Natives.getLabels();
        final int count=labels.size()-1; // the last entry is the empty "none" choice
        boolean changed=false;
        for(int i=0;i<count;i++) {
            final String name=labels.get(i);
            for(String[] pair:RENAME) {
                if(pair[0].equals(name)&&!labels.contains(pair[1])) {
                    if(Natives.setlabel(i,pair[1],Natives.getprec(i),Natives.getweight(i))) {
                        changed=true;
                        Log.i(LOG_ID,"label "+i+": "+pair[0]+" -> "+pair[1]);
                        }
                    break;
                    }
                }
            }
        prefs.edit().putBoolean(KEY,true).apply();
        if(changed&&Natives.shouldsendlabels()) { // as when Number Labels closes
            Applic.wakemirrors();
            ((Applic)act.getApplication()).sendlabels();
            }
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"once",th);
        }
    }
}
