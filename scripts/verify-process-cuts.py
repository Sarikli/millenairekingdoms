#!/usr/bin/env python3
"""Exercise independent military or physical consolidation recovery in three actual JVMs per cut."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('domain', choices=['independent', 'consolidation'])
parser.add_argument('runner', nargs=argparse.REMAINDER)
args = parser.parse_args()
project = Path(__file__).resolve().parent.parent
runner = args.runner[1:] if args.runner[:1] == ['--'] else args.runner
runner = runner or ['bash', str(project / 'gradlew')]
run = 'cuts-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
output = project / 'build/verification' / run
output.mkdir(parents=True)
military = args.domain == 'independent'
cuts = ['after-roll', 'after-begin', 'after-victory', 'after-political-ack'] if military else ['before-write', 'changed-chunk-only', 'after-write', 'after-ack']
kind = 'strategic' if military else 'consolidation'
task = 'runStrategicCrashGameTestServer' if military else 'runConsolidationCrashGameTestServer'
suite = 'verifyStrategicCrash' if military else 'verifyConsolidationCrash'
rows = []
print('Evidence directory:', output, flush=True)
for cut in cuts:
    case = dict(domain=args.domain, cut=cut, stages=[])
    rows.append(case)
    for phase in ['prepare', 'cut', 'read']:
        log = output / (cut + '-' + phase + '.log')
        cmd = [*runner, '--init-script', str(project / 'scripts/isolated-tests.gradle'),
               '-Dkingdoms.testSuite=' + suite, '-Dkingdoms.testRun=' + run + '-' + cut,
               '-Dkingdoms.' + kind + ('CrashPhase=' if military else 'Phase=') + phase,
               '-Dkingdoms.' + kind + 'Cut=' + cut]
        if military:
            cmd.append('-Dkingdoms.strategicIndependent=true')
        cmd.append(task)
        print('START', args.domain, cut, phase, flush=True)
        timed_out = False
        with log.open('w') as stream:
            process = subprocess.Popen(cmd, cwd=project, stdout=stream, stderr=subprocess.STDOUT,
                                       start_new_session=(os.name == 'posix'))
            try:
                code = process.wait(timeout=600)
            except subprocess.TimeoutExpired:
                timed_out = True
                if os.name == 'posix':
                    os.killpg(process.pid, signal.SIGTERM)
                else:
                    process.terminate()
                try:
                    code = process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    if os.name == 'posix':
                        os.killpg(process.pid, signal.SIGKILL)
                    else:
                        process.kill()
                    code = process.wait()
        text = log.read_text(errors='replace')
        counts = [int(n) for n in re.findall(r'All (\d+) required tests passed', text)]
        expected_halt = 'EXPECTED_' + kind.upper() + '_PROCESS_HALT cut=' + cut in text and 'exit value 70' in text
        passed = not timed_out and ((code != 0 and not counts and expected_halt) if phase == 'cut'
                                   else code == 0 and counts == [1] and 'BUILD SUCCESSFUL' in text)
        jar = project / 'build/libs/millenairekingdoms-1.2.1.jar'
        row = dict(phase=phase, exit_code=code, timed_out=timed_out, required_pass_counts=counts,
                   expected_halt=expected_halt, gate_passed=passed,
                   jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                   log_sha256=hashlib.sha256(log.read_bytes()).hexdigest())
        case['stages'].append(row)
        (output / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')
        print(json.dumps(dict(cut=cut, **row)), flush=True)
        if not passed:
            raise SystemExit('Process-cut gate failed; retained evidence, dependent phases not run')
