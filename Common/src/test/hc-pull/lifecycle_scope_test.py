#!/usr/bin/env python3
"""Exercise the verbatim production HC-page observer/close block.

Uses real cached LifecycleRegistry, StateFlow and CoroutineScope. Page-close and
refresh-action collaborators are small fixtures; this is not a full Activity/APK
or native/provider test. No medical data, network or emulator access.
"""
from pathlib import Path
import glob, os, re, subprocess

root=Path(__file__).resolve().parents[4]
source=(root/'Common/src/mobile/java/tk/glucodata/HealthConnectPage.kt').read_text()
start=re.search(r'^        (?:val observer=|lateinit var observer:)',source,re.M)
assert start, 'Production lifecycle registration block missing'
end=source.index('        scope.launch {HealthConnectStatusStore.state.collect',start.start())
block=source[start.start():end]
cache=Path('/home/hermes/dev/scratch/herald-tc/gradle-home/caches/modules-2/files-2.1')
def jar(group,artifact,version):
    files=list((cache/group/artifact/version).glob('*/*.jar'))
    assert len(files)==1,(group,artifact,version,files)
    return str(files[0])
java=glob.glob(os.environ['TMPDIR']+'/meter-jdk/*/bin/java')[0]
compiler=[jar('org.jetbrains.kotlin',n,'2.4.20') for n in ('kotlin-compiler-embeddable','kotlin-build-tools-api','kotlin-stdlib','kotlin-script-runtime','kotlin-daemon-embeddable')]
compiler += [jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0')]
annotations=str(list((cache/'org.jetbrains/annotations').glob('*/*/*.jar'))[-1])
compiler.append(annotations)
# Match the cached androidx JVM lifecycle family; do not mix JetBrains and AndroidX forks.
common=list((cache/'androidx.lifecycle/lifecycle-common-jvm').glob('*/*/*.jar'))
assert common, 'Cached JVM lifecycle required'
common.sort(key=lambda p:tuple(int(n) for n in re.findall(r'\d+',p.parts[-3])))
version=common[-1].parts[-3]
classpath=[jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.20'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),annotations,str(common[-1]),jar('androidx.lifecycle','lifecycle-runtime-desktop',version)]
classpath += list(map(str,(cache/'androidx.arch.core/core-common').glob('*/*/*.jar')))
collections=list((cache/'androidx.collection/collection-jvm').glob('*/*/*.jar'))
assert collections, 'Cached JVM collection runtime required'
collections.sort(key=lambda p:tuple(int(n) for n in re.findall(r'\d+',p.parts[-3])))
classpath.append(str(collections[-1]))
out=Path(os.environ['TMPDIR'])/'hc-lifecycle-tests';out.mkdir(exist_ok=True)
kotlin='''package tk.glucodata
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
class Owner:LifecycleOwner {
    val registry=LifecycleRegistry.createUnsafe(this)
    override val lifecycle:Lifecycle get()=registry
}
class Page {
    var close:(()->Unit)?=null
    fun onClose(action:()->Unit) {close=action}
}
class Actions {
    var closed=false
    var refreshes=0
    fun request(manual:Boolean) {check(!manual);check(!closed);refreshes++}
    fun close() {closed=true}
}
fun install(act:Owner,page:Page,actions:Actions,scope:CoroutineScope) {
'''+block+'''}
object HealthConnectPageLifecycleTest {
    @JvmStatic fun main(args:Array<String>)=runBlocking {
        val state=MutableStateFlow(0)
        val backend=CoroutineScope(coroutineContext+SupervisorJob())
        val backendWork=backend.launch {awaitCancellation()}
        val scopes=mutableListOf<CoroutineScope>()
        try {
            repeat(3) {round->
                val act=Owner();val page=Page();val actions=Actions()
                val scope=CoroutineScope(coroutineContext+SupervisorJob());scopes+=scope
                act.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                act.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                act.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                install(act,page,actions,scope)
                var renders=0
                scope.launch {state.collect {renders++}}
                yield();check(state.subscriptionCount.value==1)
                check(actions.refreshes==1)
                val before=renders
                act.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                act.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                act.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                yield()
                state.value=round+1;yield()
                check(state.subscriptionCount.value==0) {"Destroyed page collector still subscribed"}
                check(renders==before) {"Destroyed page rendered a later global status update"}
                check(actions.closed&&!scope.isActive)
                check(backendWork.isActive) {"Page destruction must not cancel independent backend work"}
                page.close!!.invoke();check(act.registry.observerCount==0)
            }
            val act=Owner();val page=Page();val actions=Actions()
            val scope=CoroutineScope(coroutineContext+SupervisorJob());scopes+=scope
            act.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            install(act,page,actions,scope)
            page.close!!.invoke();page.close!!.invoke()
            act.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            act.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            check(actions.closed&&actions.refreshes==0&&!scope.isActive)
            check(act.registry.observerCount==0)
            println("PASS actual page observer block: destroy/recreate cancels collectors, suppresses detached rendering, removes observer, preserves backend and idempotent close")
        } finally {scopes.forEach {it.cancel()};backend.cancel()}
    }
}
'''
file=out/'HealthConnectPageLifecycleTest.kt';file.write_text(kotlin)
cp=':'.join(classpath)
subprocess.run([java,'-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(out),str(file)],check=True)
subprocess.run([java,'-ea','-cp',str(out)+':'+cp,'tk.glucodata.HealthConnectPageLifecycleTest'],check=True)
