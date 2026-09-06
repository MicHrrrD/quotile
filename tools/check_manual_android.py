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
# The disposable emulator hosts its own widget to exercise AppWidgetService's real
# sized-RemoteViews delivery. Restore both grants/settings afterwards.
animation_scale = run(['adb','shell','settings','get','global','animator_duration_scale']).strip()
run(['adb','shell','appwidget','grantbind','--package','dev.mich.quotile','--user','0'])
try:
    run(['adb','shell','settings','put','global','animator_duration_scale','1'])
    # Raw mode preserves INSTRUMENTATION_CODE even when the runner returns a stream.
    output = run(['adb','shell','am','instrument','-w','-r','dev.mich.quotile.test/dev.mich.quotile.ManualModeTests'])
finally:
    run(['adb','shell','appwidget','revokebind','--package','dev.mich.quotile','--user','0'])
    if animation_scale == 'null':
        run(['adb','shell','settings','delete','global','animator_duration_scale'])
    else:
        run(['adb','shell','settings','put','global','animator_duration_scale',animation_scale])
if 'INSTRUMENTATION_CODE: -1' not in output or 'PASS: settings launch' not in output or 'FAIL:' in output:
    raise RuntimeError('Manual-mode instrumentation did not pass')
print('Android 16 manual-mode verification passed.')
preview_dir=ROOT/'design/android-previews'
preview_dir.mkdir(parents=True,exist_ok=True)
names=run(['adb','shell','run-as','dev.mich.quotile','ls','files/widget-previews']).splitlines()
for name in names:
    if not name.endswith('.png') or '/' in name or name.startswith('.'):
        raise RuntimeError('Unexpected native preview name')
    result=subprocess.run(['adb','exec-out','run-as','dev.mich.quotile','cat','files/widget-previews/'+name],
                          capture_output=True,check=True,timeout=15)
    if not result.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
        raise RuntimeError('Native preview is not a PNG')
    (preview_dir/name).write_bytes(result.stdout)
print('Saved native Android widget renders for visual review.')
