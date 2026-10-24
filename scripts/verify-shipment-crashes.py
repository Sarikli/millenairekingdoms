#!/usr/bin/env python3
"""Prepare, abruptly halt, then recover actual paid shipments in isolated worlds/three JVMs per boundary.

Only preparation/recovery runs may pass. The middle run MUST halt(70), with no
GameTest success line or graceful shutdown. Never point this at a player's world.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--boundary', choices=['paid-before-domain', 'dispatch-before-commit', 'refund-before-terminal'], action='append')
    parser.add_argument('command', nargs=argparse.REMAINDER, help='Gradle command after --')
    args = parser.parse_args()
    command = args.command
    if command and command[0] == '--':
        command = command[1:]
    command = command or ['bash', 'gradlew', '--offline']
    repo = Path(__file__).resolve().parents[1]
    run = 'shipment-crash-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8]
    output = repo / 'build' / 'verification' / run
    output.mkdir(parents=True, exist_ok=False)
    summary = {'run': run, 'kind': 'abrupt-jvm-paid-shipment-domain-recovery', 'cases': [], 'complete': False}
    print(f'EVIDENCE {output}', flush=True)
    for boundary in args.boundary or ['paid-before-domain', 'dispatch-before-commit', 'refund-before-terminal']:
        case = {'boundary': boundary, 'world_run': run + '-' + boundary, 'stages': []}
        summary['cases'].append(case)
        for stage in ['prepare', 'interrupt', 'recover']:
            log = output / f'{boundary}-{stage}.log'
            invocation = command + [
                '--init-script', 'scripts/isolated-tests.gradle',
                '--init-script', 'scripts/shipment-crash-tests.gradle',
                '-Dkingdoms.testSuite=verifyRoyalShipmentCrash',
                '-Dkingdoms.testRun=' + case['world_run'],
                '-Dkingdoms.crashStage=' + stage,
                '-Dkingdoms.crashBoundary=' + boundary,
                'runRoyalShipmentGameTestServer',
            ]
            print(f'RUN {boundary} {stage}', flush=True)
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
                verified = (code != 0 and not passed
                            and f'ROYAL_SHIPMENT_CRASH_BOUNDARY_REACHED {boundary} ' in content
                            and re.search(r'non-zero exit value 70\b', content) is not None
                            and 'Game test server shutting down' not in content)
            else:
                marker = 'ROYAL_SHIPMENT_CRASH_PREPARED' if stage == 'prepare' else 'ROYAL_SHIPMENT_CRASH_RECOVERED'
                verified = code == 0 and passed == ['1'] and f'{marker} {boundary}' in content
            result = {'stage': stage, 'exit_code': code, 'required_test_pass_lines': passed, 'verified': verified, 'log': log.name}
            if stage == 'interrupt':
                result['expected_jvm_exit_code'] = 70
            case['stages'].append(result)
            (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
            print(('VERIFIED' if verified else 'FAILED') + f' {boundary} {stage} exit={code} pass_lines={passed}', flush=True)
            if not verified:
                print(f'Stopped; inspect {log}', file=sys.stderr)
                return 1
    summary['complete'] = True
    (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'All requested crash boundaries verified; killed JVMs are expected failures, not passing tests. {output}', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
