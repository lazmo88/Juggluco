// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

internal data class MeterExportState(
    val unit:Long=0,val bloodLabel:Long=-1,
    val owned:Set<String> = emptySet(),val intent:List<MeterExportReading> = emptyList(),
    // Missing entry means unknown legacy provenance: never upgrade it to deletion proof.
    // Includes uncertain intents as well as acknowledged owned IDs.
    val memberships:Map<String,Set<MeterExportSource>> = emptyMap()
)
internal interface MeterExportLedger {
    fun load():MeterExportState
    fun save(value:MeterExportState)
}
internal interface MeterExportStore {
    suspend fun permitted():Boolean
    suspend fun insert(readings:List<MeterExportReading>)
    suspend fun delete(ids:List<String>)
}
internal class MeterExporter(private val ledger:MeterExportLedger,private val store:MeterExportStore) {
    enum class Result { COMPLETE, NO_PERMISSION, INCOMPLETE, CONFIG_CHANGED, CLEANUP_DEFERRED }
    suspend fun sync(raw:LongArray?,republishCurrent:Boolean=false,onAcknowledged:(Int)->Unit={}):Result {
        if(!store.permitted())return Result.NO_PERMISSION
        if(raw==null||raw.size<4||raw[0]!=2L||raw[1] !in 1L..2L||raw[2]<0L)return Result.INCOMPLETE
        var state=ledger.load()
        // Only a settled, empty ledger can safely rebind interpretation.
        if(state.owned.isEmpty()&&state.intent.isEmpty())state=state.copy(unit=0,bloodLabel=-1,memberships=emptyMap())
        if(state.unit!=0L&&(state.unit!=raw[1]||state.bloodLabel!=raw[2]))return Result.CONFIG_CHANGED
        val inventory=MeterExportPolicy.inventory(raw)?:return Result.INCOMPLETE
        val readings=inventory.readings
        if(state.unit==0L)state=state.copy(unit=inventory.unit,bloodLabel=inventory.bloodLabel)
        val tracked=state.owned+state.intent.map{it.clientId}
        val memberships=state.memberships.toMutableMap()
        for((reading,observed) in inventory.memberships) {
            val id=reading.clientId
            if(id !in tracked)memberships[id]=observed
            else memberships[id]?.let { memberships[id]=it+observed }
            // Previously unknown intent/ownership remains unknown forever.
        }
        // Explicit Sync now rechecks current saved rows with idempotent provider upserts.
        // Local ownership is not proof that a row still exists in Health Connect (the
        // user/provider can delete it independently). Automatic polling stays incremental.
        // Never republish retained obsolete IDs: their source data/interpretation may be gone.
        val fresh=readings.filter{republishCurrent||it.clientId !in state.owned}
        val pending=(state.intent+fresh).distinctBy{it.clientId}
        state=state.copy(intent=pending,memberships=memberships)
        ledger.save(state) // Durable payload AND provenance precede uncertain external writes.
        for(batch in pending.chunked(500)) {
            store.insert(batch)
            val ids=batch.map{it.clientId}.toSet()
            state=state.copy(owned=state.owned+ids,intent=state.intent.filter{it.clientId !in ids})
            ledger.save(state)
            onAcknowledged(batch.size) // Actual provider ACK + durable receipt, not an ownership-set diff.
        }
        // Replay uncertain writes before cleanup, even after source edits/deletions.
        // Source loss cannot reset membership: every historically observed copy matters.
        val desired=readings.map{it.clientId}.toSet()
        val obsolete=state.owned-desired
        val deletable=obsolete.filter { id ->
            val proof=state.memberships[id]
            !proof.isNullOrEmpty()&&proof.all { inventory.sourcesCoverage[it]?.complete==true }
        }.toSet()
        for(batch in deletable.sorted().chunked(500)) {
            store.delete(batch)
            state=state.copy(owned=state.owned-batch.toSet(),memberships=state.memberships-batch.toSet())
            ledger.save(state)
        }
        return if((obsolete-deletable).isEmpty())Result.COMPLETE else Result.CLEANUP_DEFERRED
    }
}
