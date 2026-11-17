#!/usr/bin/env python3
"""Execute verbatim meter JNI and Numdata insertion on synthetic MAP_SHARED files.
Platform-only JNI, notifications, and auxiliary bookkeeping are harness shims.
Never reads device/user data. TMPDIR must be the configured scratch directory.
"""
from pathlib import Path
import os, subprocess, tempfile, sys
root = Path(__file__).resolve().parents[2] / 'main/cpp'
def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    end, depth = brace + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]
text = (root/'nums/numdata.hpp').read_text()
sigs = ['unsigned char *raw()', 'const unsigned char *raw() const',
    'template <class Cl,typename T>\nstatic T *begin(', 'template <class Cl,typename T>\nstatic T *end(',
    'const Num *startdata() const', 'Num *startdata()  {',
    'Num *begin() { return begin<', 'const Num *begin() const { return begin<',
    'Num *end() { return end<', 'const Num *end() const { return end<',
    'int getlastpos()', 'int *lastposptr()', 'void inclastpos()', 'void setlastpos(',
    'void setlastpolledpos(', 'int getlastpolledpos()', 'void inclastpolledpos()', 'int getfirstpos()',
    'int index(', 'bool mappingsAvailable()', 'size_t capacity()', 'void setchangetimes(',
    'int32_t &getlibresend()', 'int32_t &getlibrechangednr()',
    'void    libremovelarger(', 'void addlibrechange(',
    'Num* firstAfter(', 'bool nearprevious(', 'bool nearnext(', 'Num * numsaveonly(']
# Signature whitespace varies; resolve the two mutable iterators literally.
for i,s in enumerate(sigs):
    if s not in text and s.startswith('Num *') and ' return ' in s:
        import re
        match = re.search(r'Num\s*\*\s*'+('begin' if 'begin()' in s else 'end')+r'\(\)\s*\{ return [^\n]+',text)
        assert match is not None, s
        sigs[i]=match.group().split('{')[0]
if 'Num *numsaveonlyLocked(' in text: sigs += ['Num *numsaveonlyLocked(']
methods='\n'.join(method(text,s) for s in sigs)
if (root/'meter/acceptedstorage.inc').exists(): methods += '\n'+(root/'meter/acceptedstorage.inc').read_text()
java=(root/'meter/java.cpp').read_text()
jnis=method(java,'extern "C" JNIEXPORT jboolean JNICALL   fromjava(GlucoseMeterSave)')
if 'fromjava(GlucoseMeterImportAccepted)' in java:
    jnis += '\n'+method(java,'extern "C" JNIEXPORT jint JNICALL fromjava(GlucoseMeterImportAccepted)')
    jnis += '\n'+method(java,'extern "C" JNIEXPORT jlong JNICALL fromjava(GlucoseMeterCorrectedTime)')
    jnis += '\n'+method(java,'extern "C" JNIEXPORT jbyteArray JNICALL fromjava(GlucoseMeterPrepareAccepted)')
    jnis='#include "meter/importbinding.hpp"\n'+jnis
    prefix='#include "meter/importreceipt.hpp"\n#define HAS_RECEIPTS 1\n'
else: prefix='enum class MeterImportReceipt { Rejected=0, Persisted=1, AlreadyPresent=2 };\n'
source=Path(__file__).with_name('meter_import_contract_fixture.cpp').read_text()
settings=(root/'settings/settings.hpp').read_text()
units=settings[settings.index('#define CONV18'):settings.index('static constexpr const double convertmultmg=')]
import re
limit=re.search(r'inline constexpr const int maxvarnr=\d+;',settings)
assert limit is not None
units+='\n'+limit.group()
source=prefix+source.replace('// NUMDATA_BODIES',methods).replace('// JNI_BODIES',jnis).replace('// UNIT_CONSTANTS',units)
with tempfile.TemporaryDirectory(prefix='juggluco-meter-',dir=os.environ.get('TMPDIR','/var/lib/hermes/cache/scratch')) as tmp:
    cpp,binary=Path(tmp)/'contract.cpp',Path(tmp)/'contract'
    cpp.write_text(source)
    subprocess.run(['g++','-std=c++20','-O1','-g','-Wall','-Wextra','-Wno-unused-parameter',
        '-Wno-missing-field-initializers','-Wno-unused-variable','-Wno-sign-compare','-Wno-ignored-qualifiers',
        '-fsanitize=address,undefined','-fno-sanitize-recover=all','-fno-omit-frame-pointer','-I',str(root),str(cpp),'-o',str(binary),'-pthread'],check=True)
    subprocess.run([str(binary),tmp,*sys.argv[1:]],check=True,env={**os.environ,'TZ':'UTC'})
    for failure in range(5):
        subprocess.run([str(binary),tmp,f'--context-seed-{failure}'],check=True,env={**os.environ,'TZ':'UTC'})
        if os.environ.get('METER_BINDING_JAVA_CLASSES'):
            subprocess.run([os.environ['JDK']+'/bin/java','-ea','-cp',os.environ['METER_BINDING_JAVA_CLASSES'],
                'tk.glucodata.MeterBindingPersistenceTest',str(Path(tmp)/f'context{failure}.binding')],check=True)
        subprocess.run([str(binary),tmp,f'--context-replay-{failure}'],check=True,env={**os.environ,'TZ':'UTC'})
