"""Compile and exercise the plugin against the real SDK, without an Android device."""
import os, subprocess, tempfile, sys
from pathlib import Path
from android_sdk import android_platform
from texts import check_generated
check_generated()

root=Path(__file__).resolve().parents[1]
sdk=Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')))
platform=str(android_platform(sdk))
java=Path(os.environ['JAVA_HOME'])/'bin'
sources=[*sorted((root/'sdk/src').rglob('*.java')),*sorted((root/'src').rglob('*.java')),*sorted((root/'tests').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='bing-tests-') as folder:
    subprocess.run([str(java/'javac'),'--release','8','-encoding','UTF-8','-cp',platform,'-d',folder,*map(str,sources)],check=True)
    if len(sys.argv)>1:
        subprocess.run([str(java/'java'),'-ea','-cp',os.pathsep.join([folder,platform]),'kiosk.plugins.bing.LiveSmoke',sys.argv[1]],check=True)
    else:
        subprocess.run([str(java/'java'),'-ea','-cp',os.pathsep.join([folder,platform]),'kiosk.plugins.bing.PluginTest'],check=True)
