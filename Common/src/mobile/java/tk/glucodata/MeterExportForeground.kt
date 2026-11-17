// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

import kotlinx.coroutines.*

/** A returned Job covers actual foreground work; busy callers share its completion. */
internal class MeterExportForeground(private val scope:CoroutineScope) {
    private val lock=Any()
    private var current:Job?=null

    fun request(operation:suspend ()->Unit):Job=synchronized(lock) {
        current?.takeUnless {it.isCompleted}?.let {return@synchronized it}
        val job=scope.launch(start=CoroutineStart.LAZY) {operation()}
        current=job
        job.invokeOnCompletion {synchronized(lock) {if(current===job)current=null}}
        job.start()
        job
    }

    fun cancel() {
        val job=synchronized(lock) {current}
        job?.cancel()
    }
}
