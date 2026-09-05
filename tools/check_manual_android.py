"""Run the installed app's offline Android instrumentation; assert its actual result."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def run(args, timeout=90):
    p = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    print(p.stdout)
    if p.returncode:
        print(p.stderr)
        raise RuntimeError('Android test command failed')
    return p.stdout

run(['adb','install','-r',str(ROOT/'android/app/build/outputs/apk/debug/app-debug.apk')])
run(['adb','install','-r',str(ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')])
output = run(['adb','shell','am','instrument','-w','dev.mich.quotile.test/dev.mich.quotile.ManualModeTests'])
if 'INSTRUMENTATION_CODE: -1' not in output or 'PASS: settings launch' not in output or 'FAIL:' in output:
    raise RuntimeError('Manual-mode instrumentation did not pass')
print('Android 16 manual-mode verification passed.')
