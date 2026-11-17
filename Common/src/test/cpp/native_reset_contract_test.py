#!/usr/bin/env python3
"""Synthetic durable-file tests of verbatim Numdata/JNI bodies and real headers.
Run from the repository: python3 Common/src/test/cpp/native_reset_contract_test.py
No Android, device, raw source files, or patient data is accessed.
"""
from pathlib import Path
import os
import sys
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2] / 'main/cpp'
text = (root / 'nums/numdata.hpp').read_text()

def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    end, depth = brace + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

signatures = ['bool validtime(', 'bool valid(', 'const int getindex()',
    'unsigned char *raw()', 'const unsigned char *raw() const',
    'template <class Cl,typename T>\nstatic T *begin(',
    'template <class Cl,typename T>\nstatic T *end(',
    'const Num *startdata() const', 'Num *startdata()  {',
    'const Num *begin() const { return begin<', 'const Num *end() const { return end<',
    'Num &at(const int pos) {',
    'void setfirstpos(', 'int getlastpos()', 'int *lastposptr()', 'void setlastpos(',
    'void setlastpolledpos(', 'int getlastpolledpos()', 'void setnewident(',
    'identtype  getnewident()', 'void updated(int pos)', 'void updated(int start,int end)',
    'int getfirstpos()', 'bool healthInventoryToken(', 'bool invalidateHealthInventory()',
    'bool mirrorRepairPending()', 'bool numbackupinit(', 'bool receivelastpos(',
    'bool backupnums(', 'static bool    sendlastpos(', 'bool sendbackupinit(', 'int update(']
# Keep the pre-fix body executable too, so RED is an assertion, not a compile error.
signatures += ['bool finishMirrorRepair(' if 'bool finishMirrorRepair(' in text else 'void finishMirrorRepair(']
if 'bool beginHealthInventoryReset(' in text:
    signatures += ['bool beginHealthInventoryReset(']
methods = '\n'.join(method(text, signature) for signature in signatures)
java = (root / 'nums/numjava.cpp').read_text()
jnis = '\n'.join(method(java, 'extern "C" JNIEXPORT void JNICALL fromjava(' + name + ')')
    for name in ['setident', 'setfirstNum', 'setlastNum', 'setlastpolledNum', 'updatedNum', 'updatedNumstartend'])
source = Path(__file__).with_name('native_reset_contract_fixture.cpp').read_text()
source = source.replace('// NUMDATA_BODIES', methods).replace('// JNI_BODIES', jnis)
base = os.environ.get('TMPDIR', '/var/lib/hermes/cache/scratch')
with tempfile.TemporaryDirectory(prefix='juggluco-reset-contract-', dir=base) as tmp:
    cpp, binary = Path(tmp) / 'contract.cpp', Path(tmp) / 'contract'
    cpp.write_text(source)
    subprocess.run(['g++', '-std=c++20', '-O1', '-g', '-Wall', '-Wextra',
        '-Wno-unused-parameter', '-Wno-sign-compare', '-Wno-type-limits',
        '-Wno-ignored-qualifiers', '-Wno-missing-field-initializers',
        '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-I', str(root),
        str(cpp), '-o', str(binary)], check=True)
    subprocess.run([str(binary), tmp, *sys.argv[1:]], check=True)
