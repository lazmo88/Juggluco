// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata;

import android.content.Context;
import android.content.res.Configuration;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import android.view.ContextThemeWrapper;
import android.widget.Button;

/**
 * Real Android measure/layout regression assertions, without a JUnit dependency.
 * Invoke verify(context) on the instrumentation main thread. Inflates only the
 * real menu resources, never MainActivity/Natives or patient data. Throws on
 * failure; it is deliberately not represented as a host-side passing UI test.
 */
public final class MenuTileLayoutAssertions {
    private static final int[][] ROWS={
        {R.id.jm_weekback,R.id.jm_dayback,R.id.jm_now,R.id.jm_daylater,R.id.jm_weeklater},
        {R.id.jm_date,R.id.jm_search},
        {R.id.jm_sensor,R.id.jm_meter},
        {R.id.jm_watch,R.id.jm_mirror},
        {R.id.jm_photo},
        {R.id.jm_newamount,R.id.jm_list},
        {R.id.jm_statistics,R.id.jm_export},
        {R.id.jm_settings,R.id.jm_talk},
        {R.id.jm_chatgpt,R.id.jm_about}
    };

    private MenuTileLayoutAssertions() {}

    public static void verify(Context base) {
        for(int theme:new int[]{R.style.Juggluco_M3_Light,R.style.Juggluco_M3_Dark}) {
            for(float fontScale:new float[]{1f,1.5f,2f}) {
                for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL}) {
                    Configuration config=new Configuration(base.getResources().getConfiguration());
                    config.fontScale=fontScale;
                    Context ctx=new ContextThemeWrapper(base.createConfigurationContext(config),theme);
                    View root=LayoutInflater.from(ctx).inflate(R.layout.material_menu,null,false);
                    root.setLayoutDirection(direction);
                    Button shortLabel=root.findViewById(R.id.jm_sensor);
                    Button wrapped=root.findViewById(R.id.jm_meter);
                    shortLabel.setText("Sensor");
                    wrapped.setText("Glucose\nmeter");
                    // Reuse the SAME hierarchy: catches stale exact heights after resize.
                    for(int widthDp:new int[]{320,480,800,320}) {
                        measure(root,ctx,widthDp);
                        assertRows(root);
                    }
                    int twoLineHeight=wrapped.getHeight();
                    wrapped.setText("Synthetic\nlong caption\nthird line\nfourth line");
                    measure(root,ctx,320);
                    assertRows(root);
                    check(wrapped.getLineCount()>=4,"Caption was capped at two lines");
                    check(wrapped.getHeight()>twoLineHeight,"Row did not grow for extra lines");
                    wrapped.setText("Glucose\nmeter");
                    measure(root,ctx,320);
                    assertRows(root);
                    check(wrapped.getHeight()==twoLineHeight,"Row retained a stale oversized height");
                    // GONE optional camera row must not disturb the remaining rows.
                    root.findViewById(R.id.jm_photo_row).setVisibility(View.GONE);
                    measure(root,ctx,480);
                    assertRows(root);
                }
            }
        }
    }

    private static void measure(View root,Context ctx,int widthDp) {
        int width=Math.round(widthDp*ctx.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
    }

    private static void assertRows(View root) {
        for(int[] ids:ROWS) {
            Button first=root.findViewById(ids[0]);
            if(((View)first.getParent()).getVisibility()==View.GONE)
                continue;
            check(first.getParent() instanceof LinearLayout,"Tile lost its horizontal row");
            LinearLayout parent=(LinearLayout)first.getParent();
            check(parent.getOrientation()==LinearLayout.HORIZONTAL,"Tile row is not horizontal");
            check(parent.getChildCount()==ids.length,"Column count changed");
            for(int id:ids) {
                Button tile=root.findViewById(id);
                check(tile.getParent()==parent,"Tile moved to another row");
                check(tile.getHeight()>0,"Tile was not measured");
                check(tile.getTop()==first.getTop()&&tile.getBottom()==first.getBottom(),
                      "Unequal tile rectangles: "+id);
                check(Math.abs(tile.getWidth()-first.getWidth())<=1,"Unequal weighted widths");
                check(tile.getLayoutParams().height==ViewGroup.LayoutParams.MATCH_PARENT,
                      "Tile does not participate in LinearLayout's uniform-height pass");
                check(tile.getMinLines()>=2,"Short captions do not reserve two lines");
                check(tile.getMaxLines()==Integer.MAX_VALUE,"Caption has a line cap");
                check(tile.getEllipsize()==null,"Caption can be truncated");
                check(java.util.Arrays.stream(tile.getCompoundDrawablesRelative()).anyMatch(d -> d!=null),"Tile lost its icon");
                Layout text=tile.getLayout();
                check(text!=null,"Caption has no measured layout");
                check(text.getLineEnd(text.getLineCount()-1)==text.getText().length(),
                      "Caption layout omitted trailing text");
                check(text.getHeight()<=tile.getHeight()-tile.getCompoundPaddingTop()
                        -tile.getCompoundPaddingBottom(),"Caption is vertically clipped: "+id);
            }
        }
    }

    private static void check(boolean condition,String message) {
        if(!condition)
            throw new AssertionError(message);
    }
}
