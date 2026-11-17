// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

/** Real state/serialization tests, no Android or patient fixtures. */
object HealthConnectStatusTest {
    @JvmStatic fun main(args:Array<String>) {
        check(runCatching {HealthCounts(-1,0,0)}.isFailure) { "Negative counts must not reach a persisted activity log" }
        var disk=""
        val status=HealthConnectStatusCore { disk=it }
        check(!status.begin(10)) { "Disabled export must not start" }
        status.environment(true,HealthProvider.AVAILABLE,false,11)
        check(status.state.activity.last().event==HealthEvent.PERMISSION_NEEDED) { "Permission loss must appear in the local activity log" }
        check(!status.begin(12)) { "Permission denial must not start export" }
        status.environment(true,HealthProvider.UNAVAILABLE,true,12)
        check(!status.begin(12)) { "Unavailable providers must never start export" }
        status.environment(true,HealthProvider.AVAILABLE,true,13)
        check(status.begin(14)) { "Enabled permitted export must start" }
        check(!status.begin(15)) { "Busy presses must coalesce" }
        status.finish(HealthPhase.COMPLETE,HealthCounts(3,3,0),16,accepted=true)
        check(status.state.lastAttempt==14L && status.state.lastAcceptance==16L)
        check(status.state.phase==HealthPhase.COMPLETE)
        val restored=HealthConnectStatusCore.restore(disk) { disk=it }
        check(restored.state.lastAcceptance==16L && restored.state.counts==HealthCounts(3,3,0))
        check(restored.begin(17))
        restored.finish(HealthPhase.COMPLETE,HealthCounts(3,3,0),18,accepted=false)
        check(restored.state.lastAcceptance==16L) { "A no-op is not a new provider acceptance" }
        check(restored.begin(19))
        val interrupted=HealthConnectStatusCore.restore(disk) { disk=it }
        check(interrupted.state.phase==HealthPhase.CANCELLED) { "Process restart must not leave a permanently busy status" }
        check(interrupted.state.lastAcceptance==16L)
        interrupted.finish(HealthPhase.SAFETY_PAUSED,HealthCounts(4,3,1),20)
        check(interrupted.state.phase==HealthPhase.SAFETY_PAUSED)
        interrupted.finish(HealthPhase.ERROR,HealthCounts(4,3,1),21,error=HealthError.SECURITY)
        check(interrupted.state.activity.last().error==HealthError.SECURITY)
        interrupted.finish(HealthPhase.CANCELLED,HealthCounts(4,3,1),22)
        check(interrupted.state.lastAcceptance==16L)
        interrupted.finish(HealthPhase.CLEANUP_DEFERRED,HealthCounts(4,4,0),23,accepted=true)
        check(interrupted.state.lastAcceptance==23L && interrupted.state.phase==HealthPhase.CLEANUP_DEFERRED)
        check(interrupted.state.activity.last().event==HealthEvent.CLEANUP_DEFERRED)
        repeat(100) { interrupted.finish(HealthPhase.COMPLETE,HealthCounts(4,3,1),100L+it) }
        check(interrupted.state.activity.size==HealthConnectStatusCore.LOG_LIMIT)
        val reloaded=HealthConnectStatusCore.restore(disk) { disk=it }
        check(reloaded.state.activity.size==HealthConnectStatusCore.LOG_LIMIT)
        check(reloaded.state.activity.first().at==160L && reloaded.state.activity.last().at==199L)
        // Adversarial persisted text cannot become displayed log text.
        val poisoned=disk.replace("NO_CHANGE","patient=123.456;meter=private")
        check(HealthConnectStatusCore.restore(poisoned){}.state.activity.isEmpty())
        check(!disk.contains("patient")&&!disk.contains("private"))
        check(HealthConnectStatusCore.restore("garbage"){}.state.phase==HealthPhase.ERROR)
        println("PASS disabled/provider/permission/busy/complete/no-op/persistence/restart/cancel/safety/cleanup-deferred/error/log-bounds/privacy")
    }
}
