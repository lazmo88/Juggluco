// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One active run and at most one follow-up; cancellation never becomes a retry. */
internal class MeterExportWorker(scope:CoroutineScope,operation:suspend ()->Unit,failed:(Exception)->Unit) {
    private val requests=Channel<Unit>(Channel.CONFLATED)
    private val job=scope.launch {
        for(ignored in requests) {
            try {operation()}
            catch(e:CancellationException){throw e}
            catch(e:Exception){failed(e)}
        }
    }
    fun request() {requests.trySend(Unit)}
    fun cancel() {requests.close();job.cancel()}
    suspend fun join() {job.join()}
}
