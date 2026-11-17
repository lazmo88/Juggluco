// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

internal enum class HealthProvider { UNKNOWN, AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }
internal enum class HealthPhase { IDLE, RUNNING, COMPLETE, CLEANUP_DEFERRED, PERMISSION_NEEDED, SAFETY_PAUSED, ERROR, CANCELLED }
internal enum class HealthEvent { ENABLED, DISABLED, STARTED, COMPLETE, CLEANUP_DEFERRED, NO_CHANGE, PERMISSION_NEEDED, SAFETY_PAUSED, ERROR, CANCELLED }
internal enum class HealthError { NONE, SECURITY, IO, PROVIDER, STATE, UNKNOWN }
/** Counts are exporter-ledger counts, never provider readback or Google Health counts. */
internal data class HealthCounts(val eligible:Int?=null,val accepted:Int?=null,val pending:Int?=null) {
    init {require(listOfNotNull(eligible,accepted,pending).all {it>=0})}
}
internal data class HealthActivity(val at:Long,val event:HealthEvent,val counts:HealthCounts,val error:HealthError=HealthError.NONE)
internal data class HealthStatus(
    val enabled:Boolean=false,val provider:HealthProvider=HealthProvider.UNKNOWN,val permitted:Boolean=false,
    val phase:HealthPhase=HealthPhase.IDLE,val lastAttempt:Long?=null,val lastAcceptance:Long?=null,
    val counts:HealthCounts=HealthCounts(),val activity:List<HealthActivity> = emptyList()
)

/** Privacy by construction: only enums, non-negative counts, and action timestamps enter persistence.
 * No free text, exception messages/classes, client IDs, device IDs, or glucose values. */
internal class HealthConnectStatusCore(private val persist:(String)->Unit) {
    var state=HealthStatus(); private set
    private fun save() { persist(encode(state)) }
    private fun event(at:Long,kind:HealthEvent,error:HealthError=HealthError.NONE) {
        state=state.copy(activity=(state.activity+HealthActivity(at,kind,state.counts,error)).takeLast(LOG_LIMIT))
    }
    fun environment(enabled:Boolean,provider:HealthProvider,permitted:Boolean,at:Long) {
        val priorPhase=state.phase
        val changed=enabled!=state.enabled
        state=state.copy(enabled=enabled,provider=provider,permitted=permitted)
        if(changed)event(at,if(enabled)HealthEvent.ENABLED else HealthEvent.DISABLED)
        if(!enabled&&state.phase==HealthPhase.RUNNING)state=state.copy(phase=HealthPhase.CANCELLED)
        if(enabled&&provider==HealthProvider.AVAILABLE&&!permitted&&state.phase!=HealthPhase.RUNNING)
            state=state.copy(phase=HealthPhase.PERMISSION_NEEDED)
        else if(permitted&&state.phase==HealthPhase.PERMISSION_NEEDED)state=state.copy(phase=HealthPhase.IDLE)
        if(state.phase==HealthPhase.PERMISSION_NEEDED&&priorPhase!=HealthPhase.PERMISSION_NEEDED)event(at,HealthEvent.PERMISSION_NEEDED)
        save()
    }
    fun counts(counts:HealthCounts) { state=state.copy(counts=counts);save() }
    fun begin(at:Long):Boolean {
        if(!state.enabled||state.provider!=HealthProvider.AVAILABLE||!state.permitted||state.phase==HealthPhase.RUNNING)return false
        state=state.copy(phase=HealthPhase.RUNNING,lastAttempt=at)
        event(at,HealthEvent.STARTED);save();return true
    }
    fun finish(phase:HealthPhase,counts:HealthCounts,at:Long,accepted:Boolean=false,error:HealthError=HealthError.NONE) {
        state=state.copy(phase=phase,counts=counts,lastAcceptance=if(accepted)at else state.lastAcceptance)
        event(at,when(phase) {
            HealthPhase.COMPLETE -> if(accepted)HealthEvent.COMPLETE else HealthEvent.NO_CHANGE
            HealthPhase.CLEANUP_DEFERRED -> HealthEvent.CLEANUP_DEFERRED
            HealthPhase.PERMISSION_NEEDED -> HealthEvent.PERMISSION_NEEDED
            HealthPhase.SAFETY_PAUSED -> HealthEvent.SAFETY_PAUSED
            HealthPhase.CANCELLED -> HealthEvent.CANCELLED
            else -> HealthEvent.ERROR
        },error)
        save()
    }
    companion object {
        const val LOG_LIMIT=40
        private fun count(s:String)=if(s=="-")null else s.toInt().also {require(it>=0)}
        private fun time(s:String)=if(s=="-")null else s.toLong().also {require(it>=0)}
        private fun c(c:HealthCounts)=listOf(c.eligible,c.accepted,c.pending).joinToString(","){it?.toString()?:"-"}
        private fun counts(s:String):HealthCounts { val p=s.split(',');require(p.size==3);return HealthCounts(count(p[0]),count(p[1]),count(p[2])) }
        private fun encode(s:HealthStatus):String = (listOf(listOf("1",s.enabled.toString(),s.provider.name,s.permitted.toString(),s.phase.name,s.lastAttempt?.toString()?:"-",s.lastAcceptance?.toString()?:"-",c(s.counts)).joinToString("|"))+
            s.activity.map {listOf(it.at,it.event.name,c(it.counts),it.error.name).joinToString("|")}).joinToString("\n")
        fun restore(raw:String?,persist:(String)->Unit):HealthConnectStatusCore {
            val core=HealthConnectStatusCore(persist)
            if(raw.isNullOrEmpty())return core
            try {
                require(raw.length<=16_384)
                val rows=raw.lines();val h=rows[0].split('|');require(h.size==8&&h[0]=="1")
                val logs=rows.drop(1).takeLast(LOG_LIMIT).map {
                    val p=it.split('|');require(p.size==4)
                    HealthActivity(time(p[0])?:error("time"),HealthEvent.valueOf(p[1]),counts(p[2]),HealthError.valueOf(p[3]))
                }
                val phase=HealthPhase.valueOf(h[4]).let {if(it==HealthPhase.RUNNING)HealthPhase.CANCELLED else it}
                core.state=HealthStatus(h[1].toBooleanStrict(),HealthProvider.valueOf(h[2]),h[3].toBooleanStrict(),phase,time(h[5]),time(h[6]),counts(h[7]),logs)
            } catch(_:Exception) { core.state=HealthStatus(phase=HealthPhase.ERROR) }
            return core
        }
    }
}
