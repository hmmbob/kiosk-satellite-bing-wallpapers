"""Compile and exercise the plugin against the real SDK, without an Android device."""
import argparse, os, subprocess, tempfile, sys, unittest
from build_identity import test_identity_source
from pathlib import Path
from android_sdk import android_platform
from texts import check_generated
check_generated()

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--test', action='store_true', help='Exercise the isolated test identity')
parser.add_argument('smoke_output', nargs='?', help='Optional live smoke-test output directory')
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
suite=unittest.defaultTestLoader.discover(str(root/'tests'), pattern='test_*.py')
if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
    raise SystemExit(1)
sdk=Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')))
platform=str(android_platform(sdk))
java=Path(os.environ['JAVA_HOME'])/'bin'
sources=[*sorted((root/'sdk/src').rglob('*.java')),*sorted((root/'src').rglob('*.java')),*sorted((root/'tests').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='bing-tests-') as folder:
    if args.test:
        sources=[p for p in sources if p != root/'src/kiosk/plugins/bing/BuildIdentity.java']
        identity=Path(folder)/'BuildIdentity.java'
        identity.write_text(test_identity_source(), encoding='utf-8')
        sources.append(identity)
    subprocess.run([str(java/'javac'),'--release','8','-encoding','UTF-8','-cp',platform,'-d',folder,*map(str,sources)],check=True)
    if args.smoke_output:
        subprocess.run([str(java/'java'),'-ea','-cp',os.pathsep.join([folder,platform]),'kiosk.plugins.bing.LiveSmoke',args.smoke_output],check=True)
    else:
        subprocess.run([str(java/'java'),'-ea','-cp',os.pathsep.join([folder,platform]),'kiosk.plugins.bing.PluginTest','test' if args.test else 'production'],check=True)
