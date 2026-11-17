// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.core.widget.NestedScrollView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real Android ViewGroup dispatch under Robolectric, never a real phone/provider test. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=35)
public class HealthConnectPullDispatchTest {
    static final class Fixture {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final NestedScrollView scroll=new NestedScrollView(activity);
        final FrameLayout host=new FrameLayout(activity);
        final Button child=new Button(activity);
        HealthConnectPullContainer container;
        float pullProgress;
        int refreshes, clicks, cancels;
        boolean enabled=true;
        Boolean exported;
        final HealthConnectRefreshActions actions=new HealthConnectRefreshActions(
            () -> enabled,() -> false,(export,done) -> {refreshes++;exported=export;},(busy,export) -> {});
        final float density=activity.getResources().getDisplayMetrics().density;
        Fixture() {this(false);}
        Fixture(boolean legacyListener) {
            LinearLayout content=new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            child.setText("Synthetic child-owned button");
            child.setOnClickListener(v -> clicks++);
            child.setOnTouchListener((v,e) -> {if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)cancels++;return false;});
            content.addView(child,new LinearLayout.LayoutParams(400,200));
            View filler=new View(activity);
            content.addView(filler,new LinearLayout.LayoutParams(400,2000));
            scroll.addView(content);
            host.addView(scroll,new FrameLayout.LayoutParams(400,600));
            if(legacyListener) {
                final float[] down={Float.NaN};
                scroll.setOnTouchListener((v,e) -> {
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN)down[0]=scroll.canScrollVertically(-1)?Float.NaN:e.getY();
                    if(e.getActionMasked()==MotionEvent.ACTION_UP&&e.getY()-down[0]>96*density)refreshes++;
                    return false;
                });
            } else container=HealthConnectPullContainer.wrap(scroll,() -> actions.request(true),fraction -> pullProgress=fraction);
            activity.setContentView(host);
            host.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
            host.layout(0,0,400,600);
        }
        void event(int action,float x,float y) {
            MotionEvent e=MotionEvent.obtain(0,16,action,x,y,0);
            host.dispatchTouchEvent(e);e.recycle();
        }
        void pull() {
            event(MotionEvent.ACTION_DOWN,100,40);
            event(MotionEvent.ACTION_MOVE,100,80);
            event(MotionEvent.ACTION_MOVE,100,40+140*density);
            event(MotionEvent.ACTION_UP,100,40+140*density);
        }
    }
    @Test public void legacyListenerControlMissesChildOwnedDown() {
        Fixture f=new Fixture(true);f.pull();
        assertEquals("Control must reproduce the reported original defect",0,f.refreshes);
    }
    @Test public void pullStartingOnClickableChildInvokesRefresh() {
        Fixture f=new Fixture();f.pull();
        assertEquals("The real callback must run, not just a swipe command",1,f.refreshes);
        assertEquals("Enabled pull is a manual saved-reading export",Boolean.TRUE,f.exported);
        assertEquals("Pull cannot also click the original child",0,f.clicks);
        assertEquals("Child must receive normal dispatch CANCEL",1,f.cancels);
    }
    @Test public void dispatchedPullWithExportOffOnlyObservesStatus() {
        Fixture f=new Fixture();f.enabled=false;f.pull();
        assertEquals(1,f.refreshes);assertEquals(Boolean.FALSE,f.exported);assertFalse(f.enabled);
    }
    @Test public void repeatedDispatchedPullsCoalesceUntilRealCompletion() {
        Fixture f=new Fixture();f.pull();f.pull();
        assertEquals(1,f.refreshes);assertTrue(f.actions.isBusy());
    }
    @Test public void cancelledPullDoesNotRefreshAndNextGestureWorks() {
        Fixture f=new Fixture();
        f.event(MotionEvent.ACTION_DOWN,100,40);
        f.event(MotionEvent.ACTION_MOVE,100,40+140*f.density);
        assertEquals(1,f.pullProgress,0);
        f.event(MotionEvent.ACTION_CANCEL,100,40+140*f.density);
        assertEquals(0,f.refreshes);assertEquals(0,f.pullProgress,0);
        f.pull();assertEquals(1,f.refreshes);
    }
    @Test public void horizontalDragCannotBecomeAPull() {
        Fixture f=new Fixture();
        f.event(MotionEvent.ACTION_DOWN,50,40);
        f.event(MotionEvent.ACTION_MOVE,250,45);
        f.event(MotionEvent.ACTION_MOVE,250,40+140*f.density);
        f.event(MotionEvent.ACTION_UP,250,40+140*f.density);
        assertEquals(0,f.refreshes);
    }
    @Test public void upwardDragStillScrollsAndDoesNotRefresh() {
        Fixture f=new Fixture();
        f.event(MotionEvent.ACTION_DOWN,100,180);
        f.event(MotionEvent.ACTION_MOVE,100,100);
        f.event(MotionEvent.ACTION_MOVE,100,20);
        f.event(MotionEvent.ACTION_UP,100,20);
        assertTrue("Ordinary content scrolling was swallowed",f.scroll.getScrollY()>0);
        assertEquals(0,f.refreshes);
    }
    @Test public void dragBeginningAwayFromTopDoesNotRefreshEvenAfterReachingTop() {
        Fixture f=new Fixture();f.scroll.scrollTo(0,100);
        f.pull();assertEquals(0,f.refreshes);
    }
    @Test public void shortPullDoesNotRefreshOrClickChild() {
        Fixture f=new Fixture();
        f.event(MotionEvent.ACTION_DOWN,100,40);
        f.event(MotionEvent.ACTION_MOVE,100,40+30*f.density);
        assertTrue(f.pullProgress>0&&f.pullProgress<1);
        f.event(MotionEvent.ACTION_UP,100,40+30*f.density);
        assertEquals(0,f.refreshes);assertEquals(0,f.clicks);
    }
    @Test public void normalTapAndAccessibilityClickAreUnchanged() {
        Fixture f=new Fixture();
        f.event(MotionEvent.ACTION_DOWN,100,40);f.event(MotionEvent.ACTION_UP,100,40);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1,f.clicks);assertEquals(0,f.refreshes);
        assertTrue(f.child.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null));
        assertEquals(2,f.clicks);assertEquals(0,f.refreshes);
    }
    @Test public void descendantDisallowInterceptCannotHideDownFromWrapper() {
        Fixture f=new Fixture();
        f.child.setOnTouchListener((v,e) -> {v.getParent().requestDisallowInterceptTouchEvent(true);return false;});
        f.pull();assertEquals(1,f.refreshes);assertEquals(0,f.clicks);
    }
    @Test public void busyPullDoesNotQueueAnotherRequest() {
        Fixture f=new Fixture();f.container.setBusy(true);f.pull();assertEquals(0,f.refreshes);
        f.container.setBusy(false);f.pull();assertEquals(1,f.refreshes);
    }
    @Test public void secondPointerCancelsRefreshEligibility() {
        Fixture f=new Fixture();
        f.event(MotionEvent.ACTION_DOWN,100,40);
        f.event(MotionEvent.ACTION_MOVE,100,40+140*f.density);
        MotionEvent.PointerProperties first=new MotionEvent.PointerProperties(),second=new MotionEvent.PointerProperties();
        first.id=0;second.id=1;first.toolType=second.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords a=new MotionEvent.PointerCoords(),b=new MotionEvent.PointerCoords();
        a.x=100;a.y=40+140*f.density;b.x=150;b.y=200;
        a.pressure=b.pressure=1;a.size=b.size=1;
        MotionEvent multi=MotionEvent.obtain(0,32,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,new MotionEvent.PointerProperties[]{first,second},new MotionEvent.PointerCoords[]{a,b},0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        f.host.dispatchTouchEvent(multi);
        multi.setAction(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        f.host.dispatchTouchEvent(multi);multi.recycle();
        f.event(MotionEvent.ACTION_UP,100,40+140*f.density);
        assertEquals(0,f.refreshes);
    }
    @Test public void finalReleasePositionMustStillMeetThreshold() {
        Fixture f=new Fixture();
        f.event(MotionEvent.ACTION_DOWN,100,40);
        f.event(MotionEvent.ACTION_MOVE,100,40+140*f.density);
        f.event(MotionEvent.ACTION_UP,100,45);
        assertEquals(0,f.refreshes);
        assertEquals(0,f.pullProgress,0);
    }
    @Test public void dragStartingOnExcludedChartIsNotAPull() {
        Fixture f=new Fixture();
        f.container.exclude(f.child); // stands in for the dashboard chart
        f.pull();
        assertEquals("Reading the chart by dragging must not refresh",0,f.refreshes);
        assertEquals(0,f.pullProgress,0);
    }
    @Test public void excludedViewThatIsHiddenDoesNotBlockPull() {
        Fixture f=new Fixture();
        f.container.exclude(f.child);
        f.child.setVisibility(View.GONE);
        f.pull();
        assertEquals(1,f.refreshes);
    }
}
