#!/usr/bin/env python3
"""Small offline storage regressions; all fixtures live in temporary PC/CI directories."""
from pathlib import Path
import shutil,subprocess,tempfile,sys
root=Path(__file__).resolve().parents[1]
source=root/'android/app/src/main/java/com/xxxk2020/zssteink'
names=['NativeStorageLock','NativeMaintenance','NativeProgressJson','NativeRepository','NativeProgress','NativeTransfer','NativePortablePaper','StudyProgressStore','ResourcePackStorage','ResourcePackManager','ImportCheckpoint']
files=[source/(n+'.java') for n in names]
files += [root/'tools/tests'/(n+'.java') for n in ['ResourcePackStorageTest','ResourcePackManagerTest','NativeStorageTest','ResourcePackExampleTest']]
for directory in ['host','android-json']:
    files+=sorted((root/'tools/tests'/directory).rglob('*.java'))
java,javac=shutil.which('java'),shutil.which('javac')
if not java or not javac:raise SystemExit('JDK 17 is required')
with tempfile.TemporaryDirectory(prefix='zsst-native-storage-') as out:
    subprocess.run([javac,'-encoding','UTF-8','--release','8','-d',out]+list(map(str,files)),check=True,timeout=90)
    for suite in ['ResourcePackStorageTest','ResourcePackManagerTest','NativeStorageTest']:
        subprocess.run([java,'-Xmx192m','-cp',out,'com.xxxk2020.zssteink.'+suite],check=True,timeout=90)
    packs=Path(out)/'example-packs'
    subprocess.run([sys.executable,str(root/'tools/make_resource_packs.py'),'--source',str(root/'examples/source'),'--out',str(packs),'--single-pack'],check=True,timeout=30)
    subprocess.run([java,'-Xmx128m','-cp',out,'com.xxxk2020.zssteink.ResourcePackExampleTest',str(packs/'zsst-all-resources.zip')],check=True,timeout=60)

