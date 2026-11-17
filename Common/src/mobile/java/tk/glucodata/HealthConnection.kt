/* This file is part of Juggluco. Copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission.Companion.getWritePermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Device.Companion.TYPE_UNKNOWN
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.glucodata.Log.doLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class HealthConnection private constructor(private val client:HealthConnectClient,context:Context) {
    private val appContext=context.applicationContext
    val active=AtomicBoolean(false)
    private val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
    private val meterAdapter=MeterExportAndroid(appContext,client)
    private val meterExporter=MeterExporter(meterAdapter,meterAdapter)
    private val republishRequested=AtomicBoolean(false)
    private val meterWorker=MeterExportWorker(scope,{syncMeter()},{error->
        // Never include provider messages, records, device names or client IDs.
        Log.e(LOG_ID,"Meter export failed (${HealthConnectStatusStore.error(error).name}); durable intent retained")
    })

    private fun requestMeterSync(republishCurrent:Boolean=false) {
        if(republishCurrent)republishRequested.set(true)
        meterWorker.request()
    }

    private fun recheckMeter():Deferred<Unit> = scope.async {syncMeter(explicit=true)}

    private suspend fun snapshot():LongArray?=withContext(Dispatchers.Main.immediate) {
        // Normal unit/label edits run on Main; native mirror stores are separately locked.
        Natives.healthConnectBloodSnapshot()
    }
    private fun counts(raw:LongArray?,state:MeterExportState):HealthCounts {
        val rows=MeterExportPolicy.snapshot(raw)
        val pending=rows?.let { readings ->
            (state.intent.map {it.clientId}+readings.map {it.clientId}.filter {it !in state.owned}).distinct().size
        }
        return HealthCounts(rows?.size,state.owned.size,pending)
    }
    private suspend fun syncMeter(explicit:Boolean=false) {
        // Cross-generation guard also covers before/after ledger observations.
        meterMutex.withLock {
            currentCoroutineContext().ensureActive()
            if(instance!==this||!Natives.gethealthConnect())return
            var raw:LongArray?=null
            var began=false
            var acknowledged=0
            val republish=republishRequested.getAndSet(false)||explicit
            var settled=false
            try {
                hasPermission=client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
                HealthConnectStatusStore.update(appContext) {
                    it.environment(true,HealthProvider.AVAILABLE,hasPermission,now())
                    began=it.begin(now())
                }
                if(!began)return
                raw=snapshot()
                val previous=meterAdapter.load()
                HealthConnectStatusStore.update(appContext) {it.counts(counts(raw,previous))}
                val result=meterExporter.sync(raw,republishCurrent=republish,onAcknowledged={acknowledged+=it})
                settled=result==MeterExporter.Result.COMPLETE||result==MeterExporter.Result.CLEANUP_DEFERRED
                val after=meterAdapter.load()
                val phase=when(result.name) {
                    "COMPLETE" -> HealthPhase.COMPLETE
                    "CLEANUP_DEFERRED" -> HealthPhase.CLEANUP_DEFERRED
                    "NO_PERMISSION" -> HealthPhase.PERMISSION_NEEDED
                    else -> HealthPhase.SAFETY_PAUSED
                }
                HealthConnectStatusStore.update(appContext) {
                    it.environment(Natives.gethealthConnect(),HealthProvider.AVAILABLE,hasPermission,now())
                    it.finish(phase,counts(raw,after),now(),accepted=acknowledged>0)
                }
            } catch(e:CancellationException) {
                if(began)withContext(NonCancellable+Dispatchers.IO) {recordEnd(HealthPhase.CANCELLED,raw,acknowledged>0)}
                throw e
            } catch(e:Exception) {
                recordEnd(HealthPhase.ERROR,raw,acknowledged>0,e)
                throw e
            } finally {
                // A failed request must not silently downgrade to incremental polling.
                // Payloads already queued in the ledger also replay after process restart.
                if(republish&&!settled)republishRequested.set(true)
            }
        }
    }
    private fun recordEnd(phase:HealthPhase,raw:LongArray?,acknowledged:Boolean,error:Exception?=null) {
        try {
            val after=try {meterAdapter.load()}catch(_:Exception){null}
            HealthConnectStatusStore.update(appContext) {
                it.finish(phase,after?.let {ledger->counts(raw,ledger)}?:HealthCounts(),now(),
                    accepted=acknowledged,
                    error=error?.let(HealthConnectStatusStore::error)?:HealthError.NONE)
            }
        } catch(_:Exception) {Log.e(LOG_ID,"Status persistence unavailable")}
    }

    private fun start(activity:MainActivity) {
        // init() is already called by MainActivity startup and enable toggles.
        // Runs while the app process is alive, independent of CGM arrivals.
        scope.launch {
            while(isActive) {
                meterWorker.request()
                delay(30_000L)
            }
        }
        // Lifecycle/startup and periodic work only observe grants, never prompt.
        refreshStatus(activity)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun writeAllIns(sensorptr:Long,sensorName:String) {
        if(active.getAndSet(true)) {
            if(doLog)Log.i(LOG_ID,"writeAll already active")
            return
        }
        scope.launch {
            try {
                // Always recheck; cached grant state is not proof after revocation.
                hasPermission=client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
                if(!hasPermission)return@launch
                val endstart=Natives.healthConnectfromSensorptr(sensorptr)
                val end=endstart ushr 16
                var start=endstart and 0xFFFF
                if(start==end)return@launch
                val meta=Metadata.unknownRecordingMethod(device=Device(TYPE_UNKNOWN,"Libre",sensorName))
                while(start<end) {
                    currentCoroutineContext().ensureActive()
                    val take=min(end-start,500)
                    val size=client.insertRecords(GlucoseList(meta,sensorptr,start,take)).recordIdsList.size
                    if(size!=take) {
                        Log.e(LOG_ID,"Incomplete CGM insert acknowledgement; cursor unchanged")
                        return@launch
                    }
                    start+=take
                    Natives.healthConnectWritten(sensorptr,start)
                }
            } catch(e:CancellationException){throw e}
              catch(e:Exception){Log.e(LOG_ID,"Sensor export failed (${HealthConnectStatusStore.error(e).name})")}
            finally {active.set(false)}
        }
    }

    companion object {
        val PERMISSIONS=if(Build.VERSION.SDK_INT<28)setOf("") else setOf(getWritePermission(BloodGlucoseRecord::class))
        @Volatile var hasPermission=false
        private const val LOG_ID="HealthConnection"
        @Volatile private var instance:HealthConnection?=null
        private val meterMutex=Mutex()
        @Volatile private var permissionRequested=false
        private val statusScope=CoroutineScope(Dispatchers.IO+SupervisorJob())
        private val foregroundRequest=MeterExportForeground(statusScope)
        @Volatile private var statusContext:Context?=null
        private fun now()=System.currentTimeMillis()
        private fun provider(context:Context)=if(Build.VERSION.SDK_INT<28)HealthProvider.UNAVAILABLE else when(HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthProvider.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthProvider.UPDATE_REQUIRED
            else -> HealthProvider.UNAVAILABLE
        }

        /** Observation only: safe for onResume and Refresh status. Never inserts or prompts. */
        fun refreshStatus(context:Context):Job {
            val app=context.applicationContext
            statusContext=app
            return statusScope.launch {
                try {
                    val available=provider(app)
                    val granted=available==HealthProvider.AVAILABLE&&HealthConnectClient.getOrCreate(app)
                        .permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
                    hasPermission=granted
                    HealthConnectStatusStore.update(app) {it.environment(Natives.gethealthConnect(),available,granted,now())}
                    meterMutex.withLock {
                        val health=instance
                        if(health!=null) {
                            val raw=health.snapshot()
                            val ledger=health.meterAdapter.load()
                            HealthConnectStatusStore.update(app) {it.counts(health.counts(raw,ledger))}
                        }
                    }
                } catch(e:CancellationException){throw e}
                  catch(e:Exception) {
                    try {HealthConnectStatusStore.update(app) {it.finish(HealthPhase.ERROR,HealthCounts(),now(),error=HealthConnectStatusStore.error(e))}}
                    catch(_:Exception){Log.e(LOG_ID,"Status persistence unavailable")}
                }
            }
        }

        /** Explicit foreground action only. Disabled means disabled; this never silently enables. */
        fun requestSync(activity:MainActivity):Job {
            if(!Natives.gethealthConnect())return refreshStatus(activity)
            statusContext=activity.applicationContext
            return foregroundRequest.request {
                var exporting=false
                try {
                    if(provider(activity)!=HealthProvider.AVAILABLE) {refreshStatus(activity).join();return@request}
                    val granted=HealthConnectClient.getOrCreate(activity).permissionController.getGrantedPermissions()
                    hasPermission=granted.containsAll(PERMISSIONS)
                    var export:Deferred<Unit>?=null
                    withContext(Dispatchers.Main.immediate) {
                        if(!Natives.gethealthConnect())return@withContext
                        init(activity)
                        if(hasPermission) {
                            export=instance?.recheckMeter()
                            exporting=export!=null
                        }
                        else if(!permissionRequested) {
                            val launcher=activity.permHealth?.permissionsLauncher
                            if(launcher!=null) {
                                permissionRequested=true
                                launcher.launch(granted+PERMISSIONS)
                            }
                        }
                    }
                    // Await the real export in the instance's cancel-on-stop scope.
                    // Returning after queueing would let pull report completion too early.
                    export?.await()
                    refreshStatus(activity).join()
                } catch(e:CancellationException){throw e}
                  catch(e:Exception) {
                    // syncMeter already records real partial ACKs, counts and uncertain intent.
                    if(!exporting)HealthConnectStatusStore.update(activity) {it.finish(HealthPhase.ERROR,HealthCounts(),now(),error=HealthConnectStatusStore.error(e))}
                }
            }
        }

        fun setEnabled(activity:MainActivity,enabled:Boolean) {
            Natives.sethealthConnect(enabled)
            MainActivity.tryHealth=if(enabled)5 else 0
            if(enabled) {init(activity);requestSync(activity)} else stop()
            refreshStatus(activity)
        }

        /** SDK-provided settings action, covering Android 14 framework and earlier provider app. */
        fun openSettings(activity:MainActivity) {
            try {
                if(Build.VERSION.SDK_INT<28)return
                if(HealthConnectClient.getSdkStatus(activity)==HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                    googleplay(activity)
                else activity.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            } catch(_:Exception) {
                tk.glucodata.m3.M3Page.message(activity,"Health Connect",activity.getString(R.string.jm_hc_settings_unavailable))
            }
        }

        /** Settings warning only: never resets ownership to bypass a safety stop. */
        fun meterExportWarning(context:Context):Int {
            if(!Natives.gethealthConnect())return 0
            val saved=context.getSharedPreferences("health_connect_meter_export_v1",Context.MODE_PRIVATE)
                .getString("ledger",null)?:return 0
            val state=try {MeterExportCodec.decode(saved)}
                catch(e:Exception){return R.string.jm_health_meter_ledger_warning}
            if((state.owned.isNotEmpty()||state.intent.isNotEmpty())&&
                (state.unit!=Natives.getunit().toLong()||state.bloodLabel!=Natives.getbloodvar().toLong()))
                return R.string.jm_health_meter_config_warning
            return 0
        }

        private fun googleplay(context:ComponentActivity) {
            val intent=Intent(Intent.ACTION_VIEW)
            intent.setPackage("com.android.vending")
            intent.data=Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect://onboarding")
            intent.putExtra("overlay",true)
            intent.putExtra("callerId",context.packageName)
            context.startActivity(intent)
        }

        /** Existing Java startup/enable entry point; no synthetic/release QA hooks. */
        @Synchronized fun init(context:MainActivity) {
            if(Build.VERSION.SDK_INT<28||!Natives.gethealthConnect())return
            instance?.let {it.meterWorker.request();return}
            try {
                when(HealthConnectClient.getSdkStatus(context)) {
                    HealthConnectClient.SDK_AVAILABLE -> {
                        val health=HealthConnection(HealthConnectClient.getOrCreate(context),context)
                        instance=health
                        health.start(context)
                        MainActivity.tryHealth=0
                    }
                    else -> refreshStatus(context)
                }
            } catch(e:CancellationException){throw e}
              catch(e:Exception){Log.e(LOG_ID,"Initialize Health Connect failed (${HealthConnectStatusStore.error(e).name})");refreshStatus(context)}
        }

        /** The shared ActivityResult callback immediately resumes the entire backfill. */
        fun permissionResult(granted:Set<String>) {
            permissionRequested=false
            hasPermission=granted.containsAll(PERMISSIONS)
            if(hasPermission&&Natives.gethealthConnect())instance?.requestMeterSync(republishCurrent=true)
            statusContext?.let(::refreshStatus)
        }
        fun writeAll(sensorptr:Long,sensorname:String) {instance?.writeAllIns(sensorptr,sensorname)}
        @Synchronized fun stop() {
            val old=instance
            instance=null
            foregroundRequest.cancel()
            old?.meterWorker?.cancel()
            old?.scope?.cancel()
            hasPermission=false
            permissionRequested=false
            // Deliberately retain ownership/binding/intent across disable/restart.
        }
    }
}
