#!/usr/bin/env python3
"""Local Robolectric pull regressions, without changing application dependencies.
Uses already cached dependencies; only Robolectric's Android runtime may download
from Maven Central. No device/emulator, app data, JNI or provider is accessed.
"""
import glob
import os
from pathlib import Path
import subprocess
import sys
import zipfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
CACHE=Path(os.environ.get('HC_TEST_CACHE','/home/hermes/dev/scratch/herald-tc/gradle-home/caches/modules-2/files-2.1'))
OUT=Path(os.environ['TMPDIR'])/'hc-pull-tests'
OUT.mkdir(parents=True,exist_ok=True)
java_home=os.environ.get('JAVA_HOME')
JAVA=Path(java_home)/'bin/java' if java_home else Path(glob.glob(os.environ['TMPDIR']+'/meter-jdk/*/bin/java')[0])
ANDROID=Path(os.environ.get('ANDROID_JAR','/home/hermes/dev/scratch/herald-tc/sdk/platforms/android-37.0/android.jar'))
# Select exactly one version per artifact. This harness uses a shared dependency
# cache, not a Gradle resolution graph. Keep incompatible BC provider families out.
versions={}
for p in CACHE.glob('*/*/*/*/*.jar'):
    group,artifact,version=p.parts[-5:-2]
    if group in ('com.android.tools','com.android.tools.build','org.jetbrains.kotlin'):
        if (group,artifact)!=('org.jetbrains.kotlin','kotlin-stdlib'):continue
    if group=='org.bouncycastle' and (artifact,version)!=('bcprov-jdk18on','1.85'):continue
    key=(group,artifact)
    # Prefer the latest numeric version available (all used runtime pins below are
    # the cached Robolectric 4.17 dependency family).
    import re
    rank=tuple(int(x) if x.isdigit() else -1 for x in re.split(r'[.-]',version))
    if key not in versions or rank>versions[key][0]:versions[key]=(rank,p)
jars=[p for _,p in versions.values()]
# Pin the scroll implementation to Common/build.gradle rather than silently
# exercising a newer core version from the unrelated shared build cache.
CORE=OUT/'core-1.12.0.aar'
if not CORE.exists():
    from urllib.request import urlretrieve
    urlretrieve('https://dl.google.com/dl/android/maven2/androidx/core/core/1.12.0/core-1.12.0.aar',CORE)
aars=[CORE]
for group in ('androidx.test','androidx.test.espresso','androidx.customview','androidx.annotation','androidx.collection','androidx.versionedparcelable'):
    aars+=list((CACHE/group).glob('*/*/*/*.aar'))
for aar in aars:
    target=OUT/(aar.stem+'.jar')
    with zipfile.ZipFile(aar) as z:
        if 'classes.jar' in z.namelist():
            target.write_bytes(z.read('classes.jar'))
            jars.append(target)
# Generate library R symbols from the real pinned core AAR, as AGP normally does.
with zipfile.ZipFile(CORE) as z:
    groups={}
    for line in z.read('R.txt').decode().splitlines():
        typ,group,name,value=line.split(' ',3)
        groups.setdefault(group,[]).append(f'public static final {typ} {name} = {value};')
rsrc=OUT/'src/androidx/core/R.java'
rsrc.parent.mkdir(parents=True,exist_ok=True)
rsrc.write_text('package androidx.core; public final class R {'+''.join('public static final class '+k+' {'+''.join(v)+'}' for k,v in groups.items())+'}')
cp=':'.join(map(str,jars))
sources=list(HERE.glob('*.java'))+[rsrc]
production=ROOT/'Common/src/mobile/java/tk/glucodata'
sources += [production/'HealthConnectPullContainer.java',production/'HealthConnectRefreshActions.java']
subprocess.run([str(JAVA.with_name('javac')),'-proc:none','-cp',str(ANDROID)+':'+cp,'-d',str(OUT),*map(str,sources)],check=True)
tests=sys.argv[1:] or ['tk.glucodata.HealthConnectPullDispatchTest','tk.glucodata.HealthConnectRefreshActionsTest']
raise SystemExit(subprocess.run([str(JAVA),'-Drobolectric.enabledSdks=35','-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2','-cp',str(OUT)+':'+str(ANDROID)+':'+cp,'org.junit.runner.JUnitCore',*tests]).returncode)
