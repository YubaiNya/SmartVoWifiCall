"""Real control.sh lifecycle writer with fixture files and fake process signals."""
import json
import pathlib
import re
import subprocess
import tempfile

ROOT=pathlib.Path(__file__).resolve().parents[2]
BASH=r'C:\Program Files\Git\bin\bash.exe'
source=(ROOT/'module/control.sh').read_text(encoding='utf-8')
def function(name):
    m=re.search(r'^'+name+r'\(\) \{\n.*?^\}',source,re.M|re.S)
    assert m,name
    return m.group()

with tempfile.TemporaryDirectory(prefix='control-lifecycle-') as temp:
    base=pathlib.Path(temp)
    (base/'run').mkdir();(base/'logs').mkdir();(base/'config').mkdir()
    identity='8900000000000000019'
    script=base/'control.sh'
    script.write_text(source.replace('BASE=/data/adb/smartvowificall_ims',"BASE='"+base.as_posix()+"'",1),encoding='utf-8',newline='\n')
    def stop(reason,slot):
        return subprocess.run([BASH,script.as_posix(),'stop',identity,reason,str(slot)],capture_output=True,check=True)
    journal=base/'logs'/identity/'control-lifecycle.jsonl'
    stop('RADIO_NOT_CONFIRMED_OFF',1)
    row=json.loads(journal.read_text().splitlines()[-1])
    assert row['event']=='STOP_REQUEST' and row['reason']=='RADIO_NOT_CONFIRMED_OFF' and row['slot']==1
    assert identity not in journal.read_text()
    stop('PRIVATE_TOKEN_1234567890',99)
    row=json.loads(journal.read_text().splitlines()[-1])
    assert row['reason']=='UNSPECIFIED' and row['slot']==-1 and 'PRIVATE_TOKEN' not in journal.read_text()
    journal.write_bytes(b' '*65536)
    stop('TEST_STOP',1)
    assert journal.with_suffix('.jsonl.1').stat().st_size==65536 and journal.stat().st_size<512
    # Logging failure must not block an otherwise successful cleanup.
    journal.unlink();journal.mkdir()
    stop('TEST_STOP',1)
    journal.rmdir()
    harness=base/'signal-harness.sh'
    harness.write_text('''set -eu
BASE=$1
iccid=8900000000000000019
lifecycle_reason=RADIO_NOT_CONFIRMED_OFF
lifecycle_slot=1
pidfile="$BASE/run/fake.pid"
ownerfile="$BASE/run/fake.owner"
socketfile="$BASE/run/fake.socket"
uidfile="$BASE/run/fake.uid"
live=1
owned_pid() { pid=101;ownerstart=202;[ "$live" = 1 ]; }
kill() { [ "$1" = -TERM ];live=0;printf '%s\\n' TERM > "$BASE/signal"; }
cleanup_recorded_sockets() { return 0; }
'''+'\n'+function('trace_control')+'\n'+function('stop_owned')+'\nstop_owned\n',encoding='utf-8',newline='\n')
    subprocess.run([BASH,harness.as_posix(),base.as_posix()],check=True)
    rows=[json.loads(line) for line in journal.read_text().splitlines()]
    assert [r['event'] for r in rows]==['STOP_TERM','STOPPED']
    assert all(r['pid']==101 and r['startTicks']==202 for r in rows)
    assert (base/'signal').read_text().strip()=='TERM'
print('PASS control lifecycle: stop attribution, redaction, bounded rotation, logging-failure isolation, owned signal correlation')
