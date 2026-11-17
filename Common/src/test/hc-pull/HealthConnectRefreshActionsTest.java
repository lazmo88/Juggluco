// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata;

import android.app.Activity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
public class HealthConnectRefreshActionsTest {
    static final class Fixture {
        final Activity act=Robolectric.buildActivity(Activity.class).setup().get();
        final Button sync=new Button(act),status=new Button(act);
        final ArrayList<Boolean> exports=new ArrayList<>();
        final ArrayList<Runnable> completions=new ArrayList<>();
        boolean enabled=true,backendBusy=false;
        int starts,finishes;
        final HealthConnectRefreshActions actions=new HealthConnectRefreshActions(
            () -> enabled,() -> backendBusy,
            (export,done) -> {exports.add(export);completions.add(done);},
            (busy,export) -> {if(busy)starts++;else finishes++;});
        Fixture() {
            sync.setOnClickListener(v -> request(true));
            status.setOnClickListener(v -> request(false));
        }
        boolean request(boolean manual) {return actions.request(manual);}
    }
    @Test public void disabledPullAndAccessibleSyncOnlyCheckStatus() {
        Fixture f=new Fixture();f.enabled=false;
        f.request(true);assertEquals(Boolean.FALSE,f.exports.get(0));
        f.completions.get(0).run();
        f.sync.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null);
        assertEquals(Boolean.FALSE,f.exports.get(1));assertFalse(f.enabled);
    }
    @Test public void refreshStatusAccessibleButtonNeverExportsEvenWhenEnabled() {
        Fixture f=new Fixture();
        f.status.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null);
        assertEquals(Boolean.FALSE,f.exports.get(0));assertTrue(f.actions.isBusy());
        assertEquals(0,f.finishes);f.completions.get(0).run();
        assertEquals(1,f.finishes);assertFalse(f.actions.isBusy());
    }
    @Test public void activeBackendCoalescesBothAccessibleAndPullPaths() {
        Fixture f=new Fixture();f.backendBusy=true;
        f.sync.performClick();f.request(true);assertEquals(0,f.exports.size());
        f.backendBusy=false;f.request(true);assertEquals(Boolean.TRUE,f.exports.get(0));
        f.backendBusy=true;f.completions.get(0).run();
        f.request(true);assertEquals(1,f.exports.size());
    }
    @Test public void duplicateCompletionCannotFinishNewRequest() {
        Fixture f=new Fixture();f.request(true);Runnable old=f.completions.get(0);
        old.run();f.request(true);old.run();
        assertTrue(f.actions.isBusy());assertEquals(1,f.finishes);
        f.completions.get(1).run();assertFalse(f.actions.isBusy());assertEquals(2,f.finishes);
    }
    @Test public void closingPageSuppressesLateCompletionAndNewRequests() {
        Fixture f=new Fixture();f.request(true);f.actions.close();
        f.completions.get(0).run();assertEquals(0,f.finishes);
        assertFalse(f.request(true));assertEquals(1,f.exports.size());
    }
    @Test public void accessibleSyncAndPullShareOneBusyGate() {
        Fixture f=new Fixture();
        assertTrue(f.sync.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null));
        f.request(true); // same manual action used by the pull callback
        assertEquals("A busy pull must not queue a second foreground sync",1,f.exports.size());
        f.completions.get(0).run();
        assertEquals(1,f.finishes);
        f.sync.performClick();assertEquals(2,f.exports.size());
    }
}
