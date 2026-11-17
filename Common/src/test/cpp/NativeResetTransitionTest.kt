package tk.glucodata

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Consumes the ACTUAL native before/mid/after snapshots from the C++ harness. */
object NativeResetTransitionTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        fun raw(name: String): LongArray? {
            val text = Files.readString(Path.of(args.single(), name)).trim()
            return if (text == "null") null else text.split(Regex("\\s+")).map(String::toLong).toLongArray()
        }
        val before = checkNotNull(raw("before.txt"))
        val mid = raw("mid.txt")
        val after = checkNotNull(raw("after.txt"))
        check(mid == null) { "Unseen initialization window must be unavailable, not old rows in a new complete scope" }
        val oldInventory = checkNotNull(MeterExportPolicy.inventory(before))
        check(oldInventory.sourcesCoverage.keys.map { it.databaseIdent }.toSet() == setOf(0L, 1L))
        checkNotNull(MeterExportPolicy.inventory(after))
        class Ledger : MeterExportLedger {
            var state = MeterExportState()
            override fun load() = state
            override fun save(value: MeterExportState) { state = value }
        }
        class Store : MeterExportStore {
            val inserted = mutableSetOf<String>()
            val deleted = mutableListOf<String>()
            override suspend fun permitted() = true
            override suspend fun insert(readings: List<MeterExportReading>) { inserted.addAll(readings.map { it.clientId }) }
            override suspend fun delete(ids: List<String>) { deleted.addAll(ids) }
        }
        // Original audit: old contents were first observed in the init window.
        val unseenLedger = Ledger(); val unseenStore = Store()
        val unseen = MeterExporter(unseenLedger, unseenStore)
        check(unseen.sync(mid) == MeterExporter.Result.INCOMPLETE)
        check(unseenLedger.state.owned.isEmpty() && unseenStore.inserted.isEmpty())
        check(unseen.sync(after) == MeterExporter.Result.COMPLETE)
        check(unseenStore.deleted.isEmpty())
        // Previously observed memberships must not be deletion proof after reset.
        val ledger = Ledger(); val store = Store(); val exporter = MeterExporter(ledger, store)
        check(exporter.sync(before) == MeterExporter.Result.COMPLETE)
        val old = ledger.state.owned.toSet()
        check(old.isNotEmpty() && old.all { ledger.state.memberships[it]?.isNotEmpty() == true })
        check(exporter.sync(mid) == MeterExporter.Result.INCOMPLETE)
        check(exporter.sync(after) == MeterExporter.Result.CLEANUP_DEFERRED)
        check(store.deleted.isEmpty() && old.all { it in ledger.state.owned })
        println("PASS actual native-to-Kotlin inventories: fresh zero raw IDs use logical slots; unseen init exports nothing; prior observed scope cannot authorize reset deletion")
    }
}
