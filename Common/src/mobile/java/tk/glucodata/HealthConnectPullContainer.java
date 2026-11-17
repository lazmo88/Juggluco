// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.core.widget.NestedScrollView;

/** Page-local pull owner. Dispatch, unlike an OnTouchListener on the scroll view,
 * sees DOWN even when a clickable descendant owns the stream. Only a downward,
 * single-pointer drag begun at the top is claimed; all other streams are unchanged.
 * Once claimed, CANCEL goes through normal dispatch before we consume the drag,
 * so a button/switch cannot also activate on release. No Activity hooks. */
public final class HealthConnectPullContainer extends FrameLayout {
    public interface Progress { void changed(float fraction); }
    private final NestedScrollView scroll;
    private final Runnable refresh;
    private final Progress progress;
    private final float slop, threshold;
    private float downX, downY, distance;
    private boolean candidate, pulling, busy;

    HealthConnectPullContainer(Context context, NestedScrollView scroll, Runnable refresh, Progress progress) {
        super(context);
        this.scroll=scroll;this.refresh=refresh;this.progress=progress;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        threshold=96*getResources().getDisplayMetrics().density;
        addView(scroll,new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        setFocusable(false);
    }

    public static HealthConnectPullContainer wrap(NestedScrollView scroll, Runnable refresh, Progress progress) {
        ViewGroup parent=(ViewGroup)scroll.getParent();
        int index=parent.indexOfChild(scroll);
        ViewGroup.LayoutParams params=scroll.getLayoutParams();
        parent.removeView(scroll);
        HealthConnectPullContainer container=new HealthConnectPullContainer(scroll.getContext(),scroll,refresh,progress);
        parent.addView(container,index,params);
        return container;
    }

    public void setBusy(boolean value) {
        busy=value;
        if(value)candidate=false;
    }

    private final java.util.ArrayList<android.view.View> excluded=new java.util.ArrayList<>();
    /** A stream that starts on this view (for example a chart read by dragging) is never a pull. */
    public void exclude(android.view.View view) {excluded.add(view);}
    private boolean startsOnExcluded(MotionEvent event) {
        final android.graphics.Rect r=new android.graphics.Rect();
        for(android.view.View v:excluded) {
            if(!v.isShown()||!isDescendant(v))continue;
            v.getDrawingRect(r);
            offsetDescendantRectToMyCoords(v,r); // accounts for the scroll position
            if(r.contains((int)event.getX(),(int)event.getY()))return true;
        }
        return false;
    }
    private boolean isDescendant(android.view.View v) {
        for(android.view.ViewParent p=v.getParent();p!=null;p=p.getParent())
            if(p==this)return true;
        return false;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            candidate=!busy&&!scroll.canScrollVertically(-1)&&event.getPointerCount()==1&&!startsOnExcluded(event);
            pulling=false;distance=0;downX=event.getX();downY=event.getY();
        } else if(action==MotionEvent.ACTION_POINTER_DOWN||event.getPointerCount()!=1) {
            candidate=false;
            if(pulling) {distance=0;progress.changed(0);}
        } else if(action==MotionEvent.ACTION_MOVE&&candidate&&!pulling) {
            float dx=Math.abs(event.getX()-downX),dy=event.getY()-downY;
            if(scroll.canScrollVertically(-1)||dy < -slop||dx>slop&&dx>=Math.abs(dy))candidate=false;
            else if(dy>slop&&dy>dx) {
                pulling=true;
                MotionEvent cancel=MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
        }
        if(pulling) {
            if(action==MotionEvent.ACTION_MOVE&&candidate) {
                distance=Math.max(0,event.getY()-downY);
                progress.changed(Math.min(1,distance/threshold));
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL) {
                if(action==MotionEvent.ACTION_UP)distance=Math.max(0,event.getY()-downY);
                boolean trigger=action==MotionEvent.ACTION_UP&&candidate&&!busy&&distance>=threshold;
                pulling=false;candidate=false;distance=0;progress.changed(0);
                if(trigger)refresh.run();
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)candidate=false;
        return super.dispatchTouchEvent(event);
    }
}
