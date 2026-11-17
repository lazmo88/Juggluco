// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata;

import java.util.function.BooleanSupplier;

/** Main-thread, page-scoped gate shared by pull and accessible buttons. No queued
 * follow-up request: work remains busy until its real completion callback fires.
 * Export intent is read at invocation; this class never changes the enable flag. */
final class HealthConnectRefreshActions {
    interface Work { void run(boolean export, Runnable complete); }
    interface Feedback { void changed(boolean busy, boolean export); }
    private final BooleanSupplier enabled,backendBusy;
    private final Work work;
    private final Feedback feedback;
    private boolean active,closed;
    private long generation;

    HealthConnectRefreshActions(BooleanSupplier enabled,BooleanSupplier backendBusy,Work work,Feedback feedback) {
        this.enabled=enabled;this.backendBusy=backendBusy;this.work=work;this.feedback=feedback;
    }
    boolean isBusy() {return active||backendBusy.getAsBoolean();}
    boolean request(boolean manual) {
        if(closed||isBusy())return false;
        boolean export=manual&&enabled.getAsBoolean();
        active=true;
        long token=++generation;
        feedback.changed(true,export);
        work.run(export,() -> {
            if(closed||token!=generation||!active)return;
            active=false;
            feedback.changed(false,export);
        });
        return true;
    }
    void close() {closed=true;active=false;generation++;}
}
