#!/usr/bin/env python3
"""Real resident: prepare partial structure, halt after complete metadata save, inspect physical recovery.

A failed physical recovery is a failed GameTest, never accepted as a passing probe.
Use a new isolated world; never run against a player's save.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tarfile
import uuid


def main():
    repo = Path(__file__).resolve().parents[1]
    command = sys.argv[1:]
    guard = command[:1] == ['--guard-fixture']
    if guard:
        command = command[1:]
    if command[:1] == ['--']:
        command = command[1:]
    command = command or ['bash', 'gradlew', '--offline']
    run = 'building-crash-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8]
    output = repo / 'build' / 'verification' / run
    output.mkdir(parents=True, exist_ok=False)
    if guard:
        fixture = repo / 'src/gametest/resources/fixtures/royal-missing-building-world.tar.gz'
        manifest = json.loads(fixture.with_suffix('.json').read_text())
        if hashlib.sha256(fixture.read_bytes()).hexdigest() != manifest['sha256']:
            raise ValueError('Authentic damaged-world fixture checksum differs; do not repair or replace it')
        target = repo / 'run/regression' / run / 'verifyRoyalBuildingCrash'
        target.mkdir(parents=True, exist_ok=False)
        with tarfile.open(fixture, 'r:gz') as archive:
            for member in archive.getmembers():
                parts = Path(member.name).parts
                if not member.isfile() or not parts or parts[0] != 'world' or '..' in parts or Path(member.name).is_absolute():
                    raise ValueError('Unexpected fixture member')
                path = target / member.name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.extractfile(member).read())
    summary = dict(run=run, kind='authentic-damaged-save-quarantine' if guard else 'actual-resident-physical-building-abrupt-save', stages=[], complete=False)
    print('EVIDENCE', output, flush=True)
    for stage in (['guard'] if guard else ['prepare', 'interrupt', 'recover']):
        log = output / (stage + '.log')
        invocation = command + ['--init-script', 'scripts/isolated-tests.gradle', '--init-script', 'scripts/building-crash-tests.gradle',
                                '-Dkingdoms.testSuite=verifyRoyalBuildingCrash', '-Dkingdoms.testRun=' + run,
                                '-Dkingdoms.crashStage=' + stage, 'runRoyalConstructionGameTestServer']
        print('RUN', stage, flush=True)
        with log.open('wb') as stream:
            process = subprocess.Popen(invocation, cwd=repo, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=900)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=30)
                raise
        content = log.read_text(errors='replace')
        passed = re.findall(r'All (\d+) required tests passed', content)
        if stage == 'interrupt':
            verified = (code != 0 and not passed and 'ROYAL_BUILD_CRASH_BOUNDARY_REACHED' in content
                        and re.search(r'non-zero exit value 70\b', content) is not None and 'Game test server shutting down' not in content)
        else:
            marker = 'ROYAL_BUILD_MISSING_GUARD_PASS' if stage == 'guard' else 'ROYAL_REAL_BUILD_RESTART_WRITE_PASS' if stage == 'prepare' else 'ROYAL_BUILD_CRASH_RECOVERED'
            verified = code == 0 and passed == ['1'] and marker in content
        row = dict(stage=stage, exit_code=code, required_test_pass_lines=passed, verified=verified, log=log.name)
        if stage == 'interrupt':
            row['expected_jvm_exit_code'] = 70
        if stage == 'recover':
            row['physical_disk_observations'] = re.findall(r'ROYAL_BUILD_CRASH_DISK_STATE .+', content)
        summary['stages'].append(row)
        (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(('VERIFIED' if verified else 'FAILED'), stage, 'exit=' + str(code), 'pass_lines=' + str(passed), flush=True)
        if not verified:
            print('Inspect', log, flush=True)
            return 1
    summary['complete'] = True
    (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('Authentic damaged-save quarantine passed.' if guard else 'Physical recovery passed; the halted JVM is an expected failure, not a passing GameTest.', output, flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
