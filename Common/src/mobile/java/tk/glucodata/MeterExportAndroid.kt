// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.time.Instant

/** Android boundary: no reads, no time-range deletes, no inferred device/provenance. */
internal class MeterExportAndroid(context:Context,private val client:HealthConnectClient):MeterExportLedger,MeterExportStore {
    private val preferences=context.getSharedPreferences("health_connect_meter_export_v1",Context.MODE_PRIVATE)
    override fun load():MeterExportState = preferences.getString("ledger",null)?.let(MeterExportCodec::decode)?:MeterExportState()
    override fun save(value:MeterExportState) {
        // One synchronous atomic preference transaction: intent MUST reach disk
        // before HC insertion. apply() is not crash-safe enough for this boundary.
        if(!preferences.edit().putString("ledger",MeterExportCodec.encode(value)).commit())
            throw IOException("Meter export ledger persistence failed")
    }
    override suspend fun permitted():Boolean {
        currentCoroutineContext().ensureActive()
        return client.permissionController.getGrantedPermissions().containsAll(HealthConnection.PERMISSIONS)
            .also {HealthConnection.hasPermission=it}
    }
    override suspend fun insert(readings:List<MeterExportReading>) {
        currentCoroutineContext().ensureActive()
        val records=readings.map {r-> BloodGlucoseRecord(
            time=Instant.ofEpochSecond(r.time),zoneOffset=null,
            metadata=Metadata.unknownRecordingMethod(clientRecordId=r.clientId,clientRecordVersion=1L),
            level=BloodGlucose.milligramsPerDeciliter(r.mgdl),
            // Saved blood-label rows do not distinguish manual input, Bluetooth,
            // capillary/interstitial source, or a particular meter model.
            specimenSource=BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN
        )}
        val result=client.insertRecords(records)
        check(result.recordIdsList.size==records.size) {"Incomplete meter export insert acknowledgement"}
    }
    override suspend fun delete(ids:List<String>) {
        currentCoroutineContext().ensureActive()
        client.deleteRecords(BloodGlucoseRecord::class,recordIdsList=emptyList(),clientRecordIdsList=ids)
    }
}
