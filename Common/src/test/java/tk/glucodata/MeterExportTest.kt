// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Synthetic-only host regression suite. No Android, JNI, device, or live account. */
object MeterExportTest {
    private fun source(ident:Long, rows:List<Pair<Long,Float>> = emptyList(),
        uuidHi:Long=ident, uuidLo:Long=-ident, epoch:Long=1,
        first:Long=0, complete:Long=if(first==0L)1 else 0):LongArray =
        longArrayOf(ident,uuidHi,uuidLo,epoch,first,first+rows.size,complete,rows.size.toLong(),
            *rows.flatMap { listOf(it.first,java.lang.Float.floatToRawIntBits(it.second).toLong() and 0xffffffffL) }.toLongArray())
    private fun inventory(unit:Long=2, a:LongArray=source(11), b:LongArray=source(22)):LongArray =
        longArrayOf(2,unit,7,2,*a,*b)
    private fun snapshot(unit:Long=2, vararg rows:Pair<Long,Float>):LongArray =
        inventory(unit,source(11,rows.toList()),source(22))

    // Both objects survive constructing a new exporter, like disk + Health Connect.
    private class Disk:MeterExportLedger {
        private var bytes:String?=null
        var saves=0
        var failSaveAt=0
        val state:MeterExportState get()=load()
        override fun load()=bytes?.let(MeterExportCodec::decode)?:MeterExportState()
        override fun save(value:MeterExportState) {
            saves++
            if(saves==failSaveAt)throw java.io.IOException("synthetic durable commit failure")
            bytes=MeterExportCodec.encode(value)
        }
    }
    private class Store:MeterExportStore {
        var granted=true
        val rows=linkedMapOf<String,MeterExportReading>()
        val deleted=mutableListOf<String>()
        var inserts=0
        var failInsertAt=0
        var loseResponseAt=0
        var failDelete=false
        override suspend fun permitted()=granted
        override suspend fun insert(readings:List<MeterExportReading>) {
            inserts++
            if(inserts==failInsertAt)throw java.io.IOException("synthetic batch failure")
            readings.forEach { rows[it.clientId]=it } // HC idempotent client IDs
            if(inserts==loseResponseAt)throw java.io.IOException("synthetic lost response/process death")
        }
        override suspend fun delete(ids:List<String>) {
            if(failDelete)throw java.io.IOException("synthetic deletion failure")
            deleted.addAll(ids);ids.forEach(rows::remove)
        }
    }
    private fun parserRejectsCorruptCoverage()=runBlocking {
        val disk=Disk();val store=Store()
        MeterExporter(disk,store).sync(snapshot(2,100L to 80f))
        val before=disk.state;val inserts=store.inserts
        val impossible=source(11,listOf(200L to 90f)).also {it[5]=0} // 1 blood row in zero raw positions
        val malformed=listOf<LongArray?>(
            inventory(a=impossible), null,
            longArrayOf(2,2,7,1,*source(11)), // disappearance is not an empty source
            longArrayOf(2,2,7,2,*source(11)),
            inventory()+longArrayOf(99), inventory().copyOf(19),
            inventory(a=source(11,first=1,complete=1)),
            inventory(a=source(11,complete=2)),
            inventory(a=source(11,first=-1)),
            inventory(a=source(11).also {it[5]=-1}),
            inventory(a=source(11).also {it[7]=-1}),
            inventory(a=source(11).also {it[7]=Long.MAX_VALUE}),
            inventory(a=source(11,uuidHi=0,uuidLo=0)),
            inventory(a=source(11,epoch=0)), inventory(a=source(11,epoch=-1)),
            inventory(a=source(11),b=source(11)),
            longArrayOf(2,2,7,2,*LongArray(16)), // placeholder-only is not genuinely empty
            longArrayOf(1,2,7),
            snapshot(2,0L to 90f),snapshot(2,100L to 901f),snapshot(2,100L to -1f),
            snapshot(2,100L to Float.NaN),snapshot(2,100L to Float.POSITIVE_INFINITY),
            snapshot(2,100L to 90f).also {it[13]=0x100000000L}
        )
        for((i,raw) in malformed.withIndex()) {
            check(MeterExportPolicy.inventory(raw)==null) {"Corrupt coverage fixture $i must not form deletion proof"}
            check(MeterExporter(disk,store).sync(raw)==MeterExporter.Result.INCOMPLETE)
            check(disk.state==before&&store.inserts==inserts&&store.deleted.isEmpty()) {"Malformed/unavailable snapshot must cause no writes"}
        }
        val signed=inventory(a=source(Long.MIN_VALUE,listOf(200L to 95f),uuidHi=Long.MIN_VALUE,uuidLo=Long.MAX_VALUE))
        check(MeterExportPolicy.snapshot(signed)?.single()==MeterExportReading(200,95000))
        println("PASS strict schema2 bounds/coverage/placeholder validation, unavailable snapshots have no effects")
    }

    private fun sourceIncarnationsAndMembershipUnion()=runBlocking {
        val original=MeterExportReading(100,80000);val row=listOf(100L to 80f)
        val replacements=listOf(
            source(33),source(11,uuidHi=123),source(11,uuidLo=456),source(11,epoch=2)
        )
        for(replacement in replacements) {
            val disk=Disk();val store=Store()
            MeterExporter(disk,store).sync(snapshot(2,*row.toTypedArray()))
            val proof=disk.state.memberships[original.clientId]
            repeat(2) {
                check(MeterExporter(disk,store).sync(inventory(a=replacement))==MeterExporter.Result.CLEANUP_DEFERRED)
                check(original.clientId in store.rows&&disk.state.memberships[original.clientId]==proof) {
                    "Database ID, either UUID half, or epoch replacement cannot rebaseline old membership"
                }
            }
            check(store.deleted.isEmpty())
        }
        // A source-less/null snapshot is unavailable even after an acknowledged write.
        val disk=Disk();val store=Store()
        MeterExporter(disk,store).sync(snapshot(2,*row.toTypedArray()))
        val a=MeterExportSource(11,11,-11,1);val b=MeterExportSource(22,22,-22,1)
        check(disk.state.memberships[original.clientId]==setOf(a))
        // Discover another copy later. The same ID is not reinserted, but both proofs persist.
        MeterExporter(disk,store).sync(inventory(a=source(11,row),b=source(22,row)))
        check(store.inserts==1&&disk.state.memberships[original.clientId]==setOf(a,b)) {
            "Repeated mirrored observation must union exact source memberships durably"
        }
        val before=disk.state
        check(MeterExporter(disk,store).sync(null)==MeterExporter.Result.INCOMPLETE)
        check(before==disk.state&&store.deleted.isEmpty())
        check(MeterExporter(disk,store).sync(inventory(b=source(22,first=1)))==MeterExporter.Result.CLEANUP_DEFERRED)
        check(original.clientId in store.rows) {"One pruned mirrored copy must veto deletion despite the other complete copy"}
        check(MeterExporter(disk,store).sync(inventory(b=source(33)))==MeterExporter.Result.CLEANUP_DEFERRED)
        check(disk.state.memberships[original.clientId]==setOf(a,b))
        // Restore both genuinely empty original incarnations; now absence has complete proof.
        check(MeterExporter(disk,store).sync(snapshot())==MeterExporter.Result.COMPLETE)
        check(store.rows.isEmpty()&&disk.state.memberships.isEmpty())
        // Enumeration order changes neither content IDs nor the source scope.
        val ordered=inventory(a=source(11,row),b=source(22,row))
        val reversed=inventory(a=source(22,row),b=source(11,row))
        check(MeterExportPolicy.inventory(ordered)==MeterExportPolicy.inventory(reversed))
        MeterExporter(disk,store).sync(ordered)
        val orderedState=disk.state;val inserts=store.inserts
        MeterExporter(disk,store).sync(reversed)
        check(disk.state==orderedState&&store.inserts==inserts)
        check(MeterExporter(disk,store).sync(inventory(a=source(22),b=source(11)))==MeterExporter.Result.COMPLETE)
        println("PASS ID/UUID/epoch replacement, multi-copy union/veto, genuine empty cleanup, source-order invariance")
    }

    private fun legacyUnknownAndUncertainProvenance()=runBlocking {
        val old=MeterExportReading(100,80000)
        val legacy="1|2|7\nO|${old.clientId}\nP|200|90000"
        val disk=Disk();val store=Store();disk.save(MeterExportCodec.decode(legacy))
        store.rows[old.clientId]=old
        // Even a present legacy record never gains deletion authority by baseline reset.
        MeterExporter(disk,store).sync(snapshot(2,100L to 80f,300L to 95f))
        check(old.clientId !in disk.state.memberships)
        check(MeterExporter(disk,store).sync(snapshot())==MeterExporter.Result.CLEANUP_DEFERRED)
        check(store.rows.keys==setOf(old.clientId,MeterExportReading(200,90000).clientId)) {
            "Legacy acknowledged and uncertain IDs must replay but never be deleted on unproven absence"
        }
        check(disk.state.intent.isEmpty()&&disk.state.memberships.isEmpty())
        for(replacement in listOf(false,true)) {
            val d=Disk();val s=Store();s.loseResponseAt=1
            try {MeterExporter(d,s).sync(snapshot(2,100L to 80f));error("Expected lost response")}
            catch(expected:java.io.IOException) { }
            val proof=d.state.memberships[old.clientId]
            check(proof==setOf(MeterExportSource(11,11,-11,1))) {
                "Uncertain insertion intent must persist its exact membership before insert"
            }
            val result=MeterExporter(d,s).sync(if(replacement)inventory(a=source(33)) else snapshot())
            check(d.state.intent.isEmpty()&&s.inserts==2)
            if(replacement) {
                check(result==MeterExporter.Result.CLEANUP_DEFERRED&&old.clientId in s.rows&&d.state.memberships[old.clientId]==proof)
            } else check(result==MeterExporter.Result.COMPLETE&&s.rows.isEmpty()&&d.state.memberships.isEmpty())
        }
        // Lost response + edit in a pruned source replays the old ID, inserts the new,
        // and retains both until complete same-incarnation evidence is available.
        val d=Disk();val s=Store();s.loseResponseAt=1
        try {MeterExporter(d,s).sync(snapshot(2,100L to 80f));error("Expected lost response")}
        catch(expected:java.io.IOException) { }
        check(MeterExporter(d,s).sync(inventory(a=source(11,listOf(100L to 95f),first=1)))==MeterExporter.Result.CLEANUP_DEFERRED)
        check(s.rows.size==2&&d.state.intent.isEmpty())
        check(MeterExporter(d,s).sync(snapshot(2,100L to 95f))==MeterExporter.Result.COMPLETE)
        check(s.rows.values.single().mgdl==95.0)
        println("PASS legacy v1 unknown proof retained; lost-response provenance replay with deletion/replacement/partial edit")
    }

    private fun prunedPrefixDefersCleanup()=runBlocking {
        val disk=Disk();val store=Store()
        MeterExporter(disk,store).sync(snapshot(2,100L to 80f,200L to 90f))
        val old=MeterExportReading(100,80000).clientId
        val result=MeterExporter(disk,store).sync(inventory(a=source(11,listOf(200L to 90f,300L to 95f),first=1)))
        check(old in store.rows&&old in disk.state.owned&&store.deleted.isEmpty()) {
            "Pruned source prefix is not evidence of deletion; retain previous ownership"
        }
        check(result==MeterExporter.Result.CLEANUP_DEFERRED&&store.rows.size==3) {
            "Partial coverage must still backfill new retained rows and report deferred cleanup"
        }
        check(MeterExporter(disk,store).sync(snapshot(2,200L to 90f,300L to 95f))==MeterExporter.Result.COMPLETE)
        check(old !in store.rows) { "Complete same-incarnation coverage can prove deletion" }
        // Incomplete A does not block B's proven cleanup or new retained backfill.
        val d=Disk();val s=Store()
        MeterExporter(d,s).sync(inventory(a=source(11,listOf(100L to 80f)),b=source(22,listOf(200L to 90f))))
        check(MeterExporter(d,s).sync(inventory(a=source(11,listOf(300L to 95f),first=1)))==MeterExporter.Result.CLEANUP_DEFERRED)
        check(s.rows.keys==setOf(MeterExportReading(100,80000).clientId,MeterExportReading(300,95000).clientId))
        check(s.deleted==listOf(MeterExportReading(200,90000).clientId))
        println("PASS prefix prune retains old ownership, continues backfill, full same-source cleanup")
    }

    private fun repeatedBackfill()=runBlocking {
        val disk=Disk();val store=Store()
        val raw=snapshot(2,100L to 80f,100L to 80f,200L to 95f)
        MeterExporter(disk,store).sync(raw)
        check(store.rows.size==2) { "Backfill must write saved meter rows" }
        repeat(3){MeterExporter(disk,store).sync(raw)}
        check(store.rows.size==2&&disk.state.owned==store.rows.keys)
        check(store.inserts==1) { "Durable ownership should skip unchanged batches on restart" }
        check(disk.state.unit==2L&&disk.state.bloodLabel==7L)
        println("PASS persistent idempotent store, restart/repeated backfill, ledger binding")
    }

    private fun permissionAndConfigurationGuards()=runBlocking {
        val disk=Disk();val store=Store();store.granted=false
        val raw=snapshot(2,100L to 90f)
        check(MeterExporter(disk,store).sync(raw)==MeterExporter.Result.NO_PERMISSION) { "Denied permission must defer export" }
        check(store.inserts==0&&disk.state.unit==0L)
        store.granted=true
        MeterExporter(disk,store).sync(raw) // permission grant: immediate full backfill
        val before=disk.state
        check(MeterExporter(disk,store).sync(snapshot(1,100L to 90f))==MeterExporter.Result.CONFIG_CHANGED) { "Report binding changes before attempting unsafe reinterpretation of old values" }
        check(MeterExporter(disk,store).sync(raw.copyOf().also{it[2]=8})==MeterExporter.Result.CONFIG_CHANGED)
        check(disk.state==before&&store.inserts==1&&store.deleted.isEmpty()) { "Changing display unit or blood label must freeze ownership and writes" }
        println("PASS permission denial/grant, unit-change and label-change fail closed")
    }

    private fun crashRecovery()=runBlocking {
        val disk=Disk();val store=Store();store.loseResponseAt=1
        val original=snapshot(2,100L to 90f)
        try {MeterExporter(disk,store).sync(original);error("Expected failure")}
        catch(expected:java.io.IOException) { }
        check(store.rows.size==1&&disk.state.owned.isEmpty())
        check(disk.state.intent.size==1) { "Persist crash-safe insertion intent BEFORE the Health Connect write" }
        val edited=snapshot(2,100L to 100f)
        MeterExporter(disk,store).sync(edited)
        check(store.rows.values.single().mgdl==100.0) { "Restart after uncertain insert + edit must not orphan the original" }
        check(disk.state.intent.isEmpty()&&disk.state.owned==store.rows.keys)
        println("PASS lost insert response, durable intent, restart+edit cleanup")
    }

    private fun emptyBindingCanChange()=runBlocking {
        val disk=Disk();val store=Store()
        MeterExporter(disk,store).sync(snapshot(1))
        check(MeterExporter(disk,store).sync(snapshot(2,100L to 95f))==MeterExporter.Result.COMPLETE) {
            "An empty, settled ledger must not block the first real reading after a unit change"
        }
        check(store.rows.values.single().mgdl==95.0&&disk.state.unit==2L)
        println("PASS empty initial ledger permits safe configuration binding")
    }

    private fun batchFailuresAndDeletionSafety()=runBlocking {
        val disk=Disk();val store=Store()
        // These represent unrelated Health Connect records, including same-app CGM.
        store.rows["unrelated-cgm"]=MeterExportReading(50,100000)
        store.rows["other-app-meter"]=MeterExportReading(50,100000)
        val original=snapshot(2,10L to 85f)
        MeterExporter(disk,store).sync(original)
        val oldOwned=disk.state.owned.single()
        store.failInsertAt=3 // initial write + first new 500 succeed; second new batch fails
        val large=snapshot(2,*(1..1001).map{(100L+it) to 99f}.toTypedArray())
        try {MeterExporter(disk,store).sync(large);error("Expected batch failure")}
        catch(expected:java.io.IOException) { }
        check(disk.state.owned.size==501&&disk.state.intent.size==501)
        check(oldOwned in store.rows&&store.deleted.isEmpty()) { "Partial failed backfill must not reconcile deletions" }
        val before=disk.state
        val variants=listOf<LongArray?>(null,longArrayOf(1,2,7,100),snapshot(2,100L to Float.NaN),snapshot(2,100L to Float.POSITIVE_INFINITY),snapshot(3,100L to 1f))
        for(raw in variants)check(MeterExporter(disk,store).sync(raw)==MeterExporter.Result.INCOMPLETE)
        check(disk.state==before&&store.inserts==3&&store.deleted.isEmpty())
        store.granted=false
        check(MeterExporter(disk,store).sync(large)==MeterExporter.Result.NO_PERMISSION)
        check(disk.state==before)
        store.granted=true
        MeterExporter(disk,store).sync(large)
        check(disk.state.owned.size==1001&&disk.state.intent.isEmpty())
        check(oldOwned !in store.rows&&store.rows.size==1003)
        store.failDelete=true
        try {MeterExporter(disk,store).sync(snapshot());error("Expected delete failure")}
        catch(expected:java.io.IOException) { }
        check(disk.state.owned.size==1001)
        store.failDelete=false
        MeterExporter(disk,store).sync(snapshot())
        check(disk.state.owned.isEmpty()&&store.rows.keys==setOf("unrelated-cgm","other-app-meter"))
        check(store.deleted.none{it=="unrelated-cgm"||it=="other-app-meter"})
        println("PASS partial batches, revoke/regrant, incomplete snapshots, owned-only edits/deletes")
    }

    private fun coalescedWorker()=runBlocking {
        val started=Channel<Unit>(Channel.UNLIMITED);val release=Channel<Unit>(Channel.UNLIMITED)
        var runs=0;var concurrent=0;var maxConcurrent=0
        val worker=MeterExportWorker(this,{
            concurrent++;maxConcurrent=maxOf(maxConcurrent,concurrent);runs++;started.send(Unit)
            try {release.receive()} finally {concurrent--}
        },{throw AssertionError("Unexpected worker failure",it)})
        worker.request()
        withTimeout(2000){started.receive()}
        repeat(100){worker.request()}
        release.send(Unit)
        withTimeout(2000){started.receive()}
        yield()
        check(runs==2&&maxConcurrent==1) { "Concurrent requests must serialize and coalesce, not disappear or fan out" }
        worker.cancel()
        worker.join()
        check(concurrent==0) { "Worker cancellation must propagate into suspended work" }
        println("PASS serialized/conflated requests and cancellation")
    }

    private fun ledgerCodec() {
        val reading=MeterExportReading(1234,100800)
        val sources=setOf(MeterExportSource(Long.MIN_VALUE,Long.MAX_VALUE,Long.MIN_VALUE,1),MeterExportSource(22,22,-22,7))
        val state=MeterExportState(2,7,setOf(reading.clientId),listOf(reading),mapOf(reading.clientId to sources))
        val encoded=MeterExportCodec.encode(state)
        check(encoded.startsWith("2|2|7\n"))
        check(MeterExportCodec.decode(encoded)==state) { "Durable ledger must round-trip ownership, binding, uncertain intent, and signed source tokens" }
        val unknown=MeterExportCodec.decode("1|2|7\nO|${reading.clientId}\nP|1234|100800")
        check(unknown.memberships.isEmpty()&&MeterExportCodec.decode(MeterExportCodec.encode(unknown))==unknown)
        val membership="M|${reading.clientId}|11|11|-11|1"
        for(invalid in listOf("","3|2|7","1|0|7\nO|unrelated", "1|2|7\nO|other-app-meter","1|2|7\nP|0|80",
            "2|2|7\n$membership", // orphan proof
            "1|2|7\nO|${reading.clientId}\n$membership", // v1 cannot claim provenance
            "2|2|7\nO|${reading.clientId}\n$membership\n$membership",
            "2|2|7\nO|${reading.clientId}\nM|${reading.clientId}|11|0|0|1",
            "2|2|7\nO|${reading.clientId}\nM|${reading.clientId}|11|11|-11|0",
            "2|2|7\nO|${reading.clientId}\nM|${reading.clientId}|11|11|-11|1|extra",
            "2|2|7\nM|other-app-meter|11|11|-11|1",
            "2|2|7\nO|juggluco:meter:v1:01234:100800",
            "2|2|7\nO|${reading.clientId}\nO|${reading.clientId}",
            "2|2|7\nP|1234|100800\nP|1234|100800")) {
            try {MeterExportCodec.decode(invalid);error("Malformed/unowned ledger must fail closed")}
            catch(expected:IllegalArgumentException) { }
        }
        println("PASS durable ledger codec and corrupt/unowned data refusal")
    }

    private fun persistenceFailureRecovery()=runBlocking {
        val raw=snapshot(2,100L to 120f)
        for(failAt in 1..2) {
            val disk=Disk();val store=Store();disk.failSaveAt=failAt
            try {MeterExporter(disk,store).sync(raw);error("Expected persistence failure")}
            catch(expected:java.io.IOException) { }
            check(disk.state.owned.isEmpty())
            if(failAt==1)check(store.rows.isEmpty()&&store.inserts==0)
            else check(store.rows.size==1&&disk.state.intent.size==1)
            MeterExporter(disk,store).sync(snapshot())
            check(store.rows.isEmpty()&&disk.state.owned.isEmpty()&&disk.state.intent.isEmpty())
        }
        println("PASS failed intent/ack persistence, restart with deletion, no orphans")
    }

    private fun providerLossWithIntactLedger()=runBlocking {
        val disk=Disk();val store=Store();val raw=snapshot(2,100L to 80f,200L to 90f)
        val exporter=MeterExporter(disk,store)
        exporter.sync(raw)
        val expected=store.rows.toMap();val proof=disk.state.memberships
        store.rows.clear() // Provider-only deletion; local ownership remains intact.
        check(disk.state.owned.size==2&&disk.state.intent.isEmpty())
        exporter.sync(raw)
        check(store.rows.isEmpty()) { "Automatic polling remains incremental; ownership alone is not provider readback" }
        var acknowledged=0
        exporter.sync(raw,republishCurrent=true,onAcknowledged={acknowledged+=it})
        check(store.rows==expected) { "Explicit sync must repair provider loss despite an intact local ledger" }
        check(acknowledged==2&&disk.state.memberships==proof&&disk.state.intent.isEmpty())
        repeat(3) { exporter.sync(raw,republishCurrent=true) }
        check(store.rows==expected&&disk.state.owned==expected.keys) { "Repeated explicit upserts must not duplicate readings" }
        println("PASS explicit sync repairs provider-only loss without resetting ownership or provenance")
    }

    private fun explicitRecheckGuardsAndRetry()=runBlocking {
        val raw=snapshot(2,100L to 80f);val disk=Disk();val store=Store()
        MeterExporter(disk,store).sync(raw)
        val original=disk.state;val inserts=store.inserts;var acknowledged=0
        store.granted=false
        check(MeterExporter(disk,store).sync(raw,true,{acknowledged+=it})==MeterExporter.Result.NO_PERMISSION)
        store.granted=true
        check(MeterExporter(disk,store).sync(null,true,{acknowledged+=it})==MeterExporter.Result.INCOMPLETE)
        check(MeterExporter(disk,store).sync(snapshot(1,100L to 80f),true,{acknowledged+=it})==MeterExporter.Result.CONFIG_CHANGED)
        check(store.inserts==inserts&&disk.state==original&&acknowledged==0)
        // Provider commits but response is lost: preserve owned+uncertain intent durably.
        store.rows.clear();store.loseResponseAt=store.inserts+1
        check(runCatching {MeterExporter(disk,store).sync(raw,true,{acknowledged+=it})}.exceptionOrNull() is java.io.IOException)
        check(disk.state.owned==original.owned&&disk.state.intent.size==1&&acknowledged==0)
        store.loseResponseAt=0
        MeterExporter(disk,store).sync(raw,onAcknowledged={acknowledged+=it}) // Restart: default replay must finish the manual intent.
        check(disk.state.intent.isEmpty()&&store.rows.size==1&&acknowledged==1)
        // Failed ACK persistence cannot report a durable completion; retry remains idempotent.
        disk.failSaveAt=disk.saves+2;acknowledged=0
        check(runCatching {MeterExporter(disk,store).sync(raw,true,{acknowledged+=it})}.exceptionOrNull() is java.io.IOException)
        check(acknowledged==0&&disk.state.intent.size==1)
        disk.failSaveAt=0
        MeterExporter(disk,store).sync(raw,onAcknowledged={acknowledged+=it})
        check(acknowledged==1&&store.rows.size==1&&disk.state.intent.isEmpty())
        // Legacy unknown membership stays unknown even after an explicit recheck.
        val legacy=Disk();val row=MeterExportReading(100,80000)
        legacy.save(MeterExportCodec.decode("1|2|7\nO|${row.clientId}"))
        MeterExporter(legacy,store).sync(raw,true)
        check(row.clientId !in legacy.state.memberships)
        println("PASS explicit recheck permission/config/coverage guards, uncertain-write/restart/receipt recovery, legacy provenance")
    }

    private fun foregroundCompletionBoundary()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        val gate=MeterExportForeground(scope)
        val entered=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>();var runs=0
        try {
            val first=gate.request {runs++;entered.complete(Unit);finished.await()}
            entered.await()
            check(first.isActive&&!first.isCompleted) { "Returned job must remain active through actual export, not just scheduling" }
            val busy=gate.request {runs++}
            check(busy===first&&runs==1) { "Busy foreground requests must share the same actual completion" }
            finished.complete(Unit);first.join()
            check(first.isCompleted&&runs==1)
            gate.request {runs++}.join();check(runs==2)
            val blocked=gate.request {awaitCancellation()}
            yield();gate.cancel();blocked.join()
            check(blocked.isCancelled) { "Disable/stop must cancel the foreground request" }
            gate.request {runs++}.join();check(runs==3) { "Cancellation must not permanently lock the gate" }
            println("PASS foreground real completion, busy-request coalescing, cancellation and reuse")
        } finally {scope.cancel()}
    }

    @JvmStatic fun main(args:Array<String>) {
        val old=946684800L // deliberately older than any 90-day window
        val records=checkNotNull(MeterExportPolicy.snapshot(snapshot(2,old to 100.8f,old to 100.8f))) {
            "Schema2 with two genuine source inventories must supply retained blood readings"
        }
        check(records.size==1) { "Mirrored blood-label rows must deduplicate, including old rows" }
        val mmol=MeterExportPolicy.snapshot(snapshot(1,old to 5.6f))!!.single()
        check(records.single()==mmol) { "Units and float noise must canonicalize to the same content ID/value" }
        check(mmol.mgdl==100.8)
        check(mmol.clientId.startsWith("juggluco:meter:v1:"))
        providerLossWithIntactLedger()
        explicitRecheckGuardsAndRetry()
        foregroundCompletionBoundary()
        parserRejectsCorruptCoverage()
        sourceIncarnationsAndMembershipUnion()
        legacyUnknownAndUncertainProvenance()
        prunedPrefixDefersCleanup()
        repeatedBackfill()
        permissionAndConfigurationGuards()
        crashRecovery()
        emptyBindingCanChange()
        batchFailuresAndDeletionSafety()
        coalescedWorker()
        ledgerCodec()
        persistenceFailureRecovery()
        println("PASS canonical units, mirror dedupe, stable namespaced identity, historical backfill")
    }
}
