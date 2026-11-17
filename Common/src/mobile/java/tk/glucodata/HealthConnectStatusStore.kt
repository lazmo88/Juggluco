// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** Access only on IO. The separate mutex in HealthConnection guards ledger observations. */
internal object HealthConnectStatusStore {
    private var core:HealthConnectStatusCore?=null
    private val mutable=MutableStateFlow(HealthStatus())
    val state=mutable.asStateFlow()
    @Synchronized fun update(context:Context,change:(HealthConnectStatusCore)->Unit) {
        val c=core?:run {
            val prefs=context.applicationContext.getSharedPreferences("health_connect_status_v1",Context.MODE_PRIVATE)
            HealthConnectStatusCore.restore(prefs.getString("status",null)) {
                if(!prefs.edit().putString("status",it).commit())throw IOException("Status persistence failed")
            }.also {core=it}
        }
        try {change(c)} finally {mutable.value=c.state}
    }
    fun error(error:Exception)=when(error) {
        is SecurityException -> HealthError.SECURITY
        is IOException -> HealthError.IO
        is IllegalStateException -> HealthError.STATE
        else -> HealthError.PROVIDER
    }
}
