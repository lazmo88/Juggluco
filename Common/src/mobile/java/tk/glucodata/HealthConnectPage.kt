// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import tk.glucodata.m3.M3Page
import java.text.DateFormat
import java.util.Date

/** Local, actionable status. No Google account, cloud, provider read permission or patient log. */
object HealthConnectPage {
    @JvmStatic fun show(act:MainActivity) {
        val page=M3Page.open(act,R.string.jm_hc_title)
        val scope=CoroutineScope(Dispatchers.Main.immediate+SupervisorJob())
        // Keep progress/results visible even if the user is scrolled far down the activity log.
        // The original toolbar/Back handler and the scroll view itself remain unchanged.
        val feedbackLine=LinearLayout(page.ctx).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(page.dp(16f),page.dp(8f),page.dp(16f),page.dp(8f))
        }
        val spinner=ProgressBar(page.ctx).apply {
            isIndeterminate=true
            visibility=View.GONE
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        feedbackLine.addView(spinner,LinearLayout.LayoutParams(page.dp(24f),page.dp(24f)).apply {marginEnd=page.dp(12f)})
        val feedback=TextView(page.ctx).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(page.color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        feedbackLine.addView(feedback,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val scrollParent=page.scroll.parent as ViewGroup
        scrollParent.addView(feedbackLine,scrollParent.indexOfChild(page.scroll),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))

        val controls=page.section(R.string.jm_hc_export)
        page.toggle(controls,R.drawable.ic_jm_favorite,act.getString(R.string.jm_hc_enable),act.getString(R.string.jm_health_export_summary),
            {Natives.gethealthConnect()},{enabled->HealthConnection.setEnabled(act,enabled)})
        val current=page.section(R.string.jm_hc_status)
        val provider=page.row(current,0,act.getString(R.string.jm_hc_provider),null)
        val permission=page.row(current,0,act.getString(R.string.jm_hc_permission),null)
        val progress=page.row(current,0,act.getString(R.string.jm_hc_export),null)
        val checkedAt=page.row(current,0,act.getString(R.string.jm_hc_status_checked),act.getString(R.string.jm_hc_unknown))
        val attempts=page.row(current,0,act.getString(R.string.jm_hc_last_attempt),null)
        val acceptedAt=page.row(current,0,act.getString(R.string.jm_hc_last_acceptance),null)
        val counts=page.note(current,"")
        page.note(current,act.getString(R.string.jm_hc_counts_note))

        lateinit var actions:HealthConnectRefreshActions
        lateinit var pull:HealthConnectPullContainer
        lateinit var sync:MaterialButton
        lateinit var check:MaterialButton
        var localBusy=false
        var localExport=false
        var result:String?=null
        var lastChecked:Long?=null
        var lastPhase=HealthConnectStatusStore.state.value.phase
        fun time(value:Long?)=value?.let {DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.MEDIUM).format(Date(it))}?:act.getString(R.string.jm_hc_unknown)
        fun hint(release:Boolean=false)=act.getString(
            if(Natives.gethealthConnect()) {
                if(release)R.string.jm_hc_pull_release else R.string.jm_hc_pull_hint
            } else if(release)R.string.jm_hc_pull_release_disabled else R.string.jm_hc_pull_hint_disabled)
        fun outcome(s:HealthStatus)=when {
            !s.enabled -> R.string.jm_hc_disabled
            s.provider==HealthProvider.UPDATE_REQUIRED -> R.string.jm_hc_update
            s.provider!=HealthProvider.AVAILABLE -> R.string.jm_hc_unavailable
            !s.permitted -> R.string.jm_hc_needed
            else -> phase(s.phase)
        }
        fun updateFeedback() {
            val s=HealthConnectStatusStore.state.value
            val busy=localBusy||s.phase==HealthPhase.RUNNING
            pull.setBusy(busy)
            sync.isEnabled=s.enabled&&s.provider==HealthProvider.AVAILABLE&&!busy
            check.isEnabled=!busy
            spinner.visibility=if(busy)View.VISIBLE else View.GONE
            feedback.text=if(busy)act.getString(if(localExport||s.phase==HealthPhase.RUNNING)R.string.jm_hc_syncing_saved else R.string.jm_hc_checking)
                else result?:hint()
        }
        actions=HealthConnectRefreshActions(
            {Natives.gethealthConnect()},
            {HealthConnectStatusStore.state.value.phase==HealthPhase.RUNNING},
            {export,complete ->
                scope.launch {
                    try {
                        // Backend Jobs are the completion boundary, not a timer or a changed-state
                        // guess. Manual request joins the foreground export, including an upsert of
                        // already acknowledged saved readings; observation never writes or prompts.
                        if(export)HealthConnection.requestSync(act).join()
                        HealthConnection.refreshStatus(act).join()
                        lastChecked=System.currentTimeMillis()
                        M3Page.setSummary(checkedAt,time(lastChecked))
                        val s=HealthConnectStatusStore.state.value
                        result=if(export)act.getString(R.string.jm_hc_sync_finished_at,time(lastChecked),act.getString(outcome(s)))
                            else act.getString(R.string.jm_hc_checked_at,time(lastChecked))
                    } finally {complete.run()}
                }
            },
            {busy,export ->
                localBusy=busy;localExport=export
                updateFeedback()
            })
        pull=HealthConnectPullContainer.wrap(page.scroll,{actions.request(true)},{fraction ->
            if(!localBusy&&HealthConnectStatusStore.state.value.phase!=HealthPhase.RUNNING)
                feedback.text=if(fraction>0)hint(fraction>=1f) else result?:hint()
        })
        sync=page.button(M3Page.Style.FILLED,act.getString(R.string.jm_hc_sync),R.drawable.ic_jm_sync_alt) {actions.request(true)}
        check=page.button(M3Page.Style.OUTLINED,act.getString(R.string.jm_hc_refresh),0) {actions.request(false)}
        page.buttons(controls,sync,check)
        page.note(controls,act.getString(R.string.jm_hc_refresh_note))
        val settings=page.section(R.string.jm_hc_settings)
        val grant=page.link(settings,R.drawable.ic_jm_favorite,act.getString(R.string.jm_hc_grant),act.getString(R.string.jm_hc_grant_note)) {
            if(Natives.gethealthConnect())actions.request(true)
            else page.toast(act.getString(R.string.jm_hc_enable_first))
        }
        page.link(settings,R.drawable.ic_jm_favorite,act.getString(R.string.jm_hc_open_settings),act.getString(R.string.jm_hc_open_settings_note)) {HealthConnection.openSettings(act)}
        val steps=page.section(R.string.jm_hc_path)
        page.note(steps,act.getString(R.string.jm_hc_path_note))
        page.note(steps,act.getString(R.string.jm_hc_history_note))
        val log=page.section(R.string.jm_hc_log)
        val logText=page.note(log,"")
        page.note(log,act.getString(R.string.jm_hc_log_note))
        fun render(s:HealthStatus) {
            page.refresh()
            M3Page.setSummary(provider,act.getString(when(s.provider) {
                HealthProvider.AVAILABLE -> R.string.jm_hc_available
                HealthProvider.UPDATE_REQUIRED -> R.string.jm_hc_update
                HealthProvider.UNAVAILABLE -> R.string.jm_hc_unavailable
                else -> R.string.jm_hc_unknown
            }))
            M3Page.setSummary(permission,act.getString(if(s.permitted)R.string.jm_hc_granted else R.string.jm_hc_needed))
            M3Page.setSummary(progress,act.getString(outcome(s)))
            M3Page.setSummary(attempts,time(s.lastAttempt))
            M3Page.setSummary(acceptedAt,time(s.lastAcceptance))
            fun number(n:Int?)=n?.toString()?:act.getString(R.string.jm_hc_unknown)
            counts.text=act.getString(R.string.jm_hc_counts,number(s.counts.eligible),number(s.counts.accepted),number(s.counts.pending))
            grant.visibility=if(s.permitted)View.GONE else View.VISIBLE
            grant.isEnabled=!actions.isBusy
            logText.text=if(s.activity.isEmpty())act.getString(R.string.jm_hc_log_empty) else s.activity.asReversed().joinToString("\n\n") {entry->
                time(entry.at)+" — "+act.getString(event(entry.event))+
                    "\n"+act.getString(R.string.jm_hc_counts,number(entry.counts.eligible),number(entry.counts.accepted),number(entry.counts.pending))+
                    if(entry.error==HealthError.NONE)"" else "\n"+act.getString(R.string.jm_hc_error_code,entry.error.name)
            }
            // Also surface completion of an export started by enable/grant or a background worker.
            if(lastPhase==HealthPhase.RUNNING&&s.phase!=HealthPhase.RUNNING&&!localBusy)
                result=act.getString(R.string.jm_hc_sync_finished_at,time(System.currentTimeMillis()),act.getString(outcome(s)))
            lastPhase=s.phase
            updateFeedback()
        }
        lateinit var observer:LifecycleEventObserver
        var closed=false
        fun closePage() {
            if(closed)return
            closed=true
            actions.close()
            scope.cancel()
            act.lifecycle.removeObserver(observer)
        }
        observer=LifecycleEventObserver {_,event->
            when(event) {
                Lifecycle.Event.ON_RESUME -> if(!closed)actions.request(false)
                Lifecycle.Event.ON_DESTROY -> closePage()
                else -> Unit
            }
        }
        act.lifecycle.addObserver(observer)
        page.onClose {closePage()}
        scope.launch {HealthConnectStatusStore.state.collect {render(it)}}
        updateFeedback()
        actions.request(false)
    }
    private fun phase(p:HealthPhase)=when(p) {
        HealthPhase.RUNNING -> R.string.jm_hc_running
        HealthPhase.COMPLETE -> R.string.jm_hc_complete
        HealthPhase.CLEANUP_DEFERRED -> R.string.jm_hc_cleanup_deferred
        HealthPhase.PERMISSION_NEEDED -> R.string.jm_hc_needed
        HealthPhase.SAFETY_PAUSED -> R.string.jm_hc_safety
        HealthPhase.ERROR -> R.string.jm_hc_error
        HealthPhase.CANCELLED -> R.string.jm_hc_cancelled
        else -> R.string.jm_hc_idle
    }
    private fun event(e:HealthEvent)=when(e) {
        HealthEvent.ENABLED -> R.string.jm_hc_enabled
        HealthEvent.DISABLED -> R.string.jm_hc_disabled
        HealthEvent.STARTED -> R.string.jm_hc_running
        HealthEvent.COMPLETE -> R.string.jm_hc_accepted
        HealthEvent.CLEANUP_DEFERRED -> R.string.jm_hc_cleanup_deferred
        HealthEvent.NO_CHANGE -> R.string.jm_hc_no_change
        HealthEvent.PERMISSION_NEEDED -> R.string.jm_hc_needed
        HealthEvent.SAFETY_PAUSED -> R.string.jm_hc_safety
        HealthEvent.ERROR -> R.string.jm_hc_error
        HealthEvent.CANCELLED -> R.string.jm_hc_cancelled
    }
}
