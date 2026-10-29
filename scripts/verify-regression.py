#!/usr/bin/env python3
"""Run paired suites sequentially; require explicit success in both JVMs. Four-JVM simulation is separate."""
import argparse
import datetime
import hashlib
import os
import signal
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

project = Path(__file__).resolve().parent.parent
run = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
output = project / 'build' / 'verification' / run
output.mkdir(parents=True)
parser = argparse.ArgumentParser(description=__doc__)
selection = parser.add_mutually_exclusive_group()
selection.add_argument('--suites', help='Comma-separated affected suite names')
selection.add_argument('--resume-from', choices=None)
parser.add_argument('--timeout-seconds', type=int, default=1800)
parser.add_argument('runner', nargs=argparse.REMAINDER)
args = parser.parse_args()
if not 60 <= args.timeout_seconds <= 14400:
    parser.error('--timeout-seconds must be between 60 and 14400')
runner = args.runner
if runner[:1] == ['--']:
    runner = runner[1:]
runner = runner or ['bash', str(project / 'gradlew')]
suites = {'verifyWars': 30, 'verifyConquest': 25, 'verifyStage5': 26, 'verifyCapitals': 5, 'verifyAutonomy': 22, 'verifyGui': 7, 'verifyFounding': 12, 'verifyRoyalEconomy': 46, 'verifyRoyalConstruction': 55, 'verifyRoyalRecovery': 16, 'verifyRoyalSites': 10, 'verifyRoyalLive': 3, 'verifyRoyalGui': 28, 'verifyRoyalGoods': 20, 'verifyRoyalReceipts': 12, 'verifyRoyalShipments': 49, 'verifyRoyalShipmentRuntime': 1, 'verifyRoyalShipmentLive': 1, 'verifyRoyalShipmentGui': 18, 'verifyPersonalities': 9, 'verifyRoyalPolicy': 7, 'verifyRoyalNpcBudget': 13, 'verifyRoyalNpcEconomy': 12, 'verifyRoyalNpcRuntime': 4, 'verifyRoyalNpcLive': 1, 'verifyRoyalNpcBuilders': 1, 'verifyRoyalIntegration': 9, 'verifyHistory': 38, 'verifyHistoryWorld': 1, 'verifyAtlas': 22, 'verifyGeography': 4, 'verifyLocalization': 8, 'verifyRoyalStrategy': 7, 'verifyStrategyRecovery': 3, 'verifyStrategicMilitary': 19, 'verifyStrategicRestart': 2, 'verifyNativeBattle': 1, 'verifyNativeWar': 1, 'verifyConsolidation': 22, 'verifyConsolidationLive': 1, 'verifyEconomicQueue': 11, 'verifyEconomicBackground': 4, 'verifyEconomicNative': 1, 'verifyMaintenance': 1, 'verifyIndependentNative': 1}
if args.suites:
    selected = args.suites.split(',')
    if len(set(selected)) != len(selected) or any(name not in suites for name in selected):
        parser.error('--suites requires distinct known suite names')
    suites = {name: suites[name] for name in selected}
if args.resume_from:
    if args.resume_from not in suites:
        parser.error('--resume-from requires a known suite name')
    first = list(suites).index(args.resume_from)
    suites = dict(list(suites.items())[first:])
results = []
print('Evidence directory:', output, flush=True)
for suite, expected in suites.items():
    print('START', suite, flush=True)
    logfile = output / (suite + '.log')
    timed_out = False
    with logfile.open('w') as log:
        process = subprocess.Popen([*runner, '--init-script', str(project / 'scripts/isolated-tests.gradle'),
                                    '-Dkingdoms.testSuite=' + suite, '-Dkingdoms.testRun=' + run, suite],
                                   cwd=project, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=(os.name == 'posix'))
        try:
            code = process.wait(timeout=args.timeout_seconds)
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
    text = logfile.read_text()
    counts = [int(n) for n in re.findall(r'All (\d+) required tests passed', text)]
    launches = re.findall(r'ISOLATED_TEST suite=\S+ task=(\S+) world=(.+)', text)
    version = re.search(r'^mod_version=(.+)$', (project / 'gradle.properties').read_text(), re.MULTILINE).group(1)
    jar = project / 'build/libs' / ('millenairekingdoms-' + version + '.jar')
    jar_sha256 = hashlib.sha256(jar.read_bytes()).hexdigest() if jar.is_file() else None
    staged_sha256 = []
    for _, world in launches:
        staged = Path(world) / 'mods/millenairekingdoms.jar'
        staged_sha256.append(hashlib.sha256(staged.read_bytes()).hexdigest() if staged.is_file() else None)
    image_matches = jar_sha256 is not None and staged_sha256 == [jar_sha256, jar_sha256]
    passed = not timed_out and code == 0 and counts == [expected, expected] and len(launches) == 2 and image_matches
    results.append(dict(suite=suite, exit_code=code, timed_out=timed_out, required_pass_counts=counts,
                        expected_per_pass=expected, launches=launches, jar_sha256=jar_sha256,
                        staged_sha256=staged_sha256, image_matches=image_matches, passed=passed))
    (output / 'results.json').write_text(json.dumps(results, indent=2))
    print(json.dumps(results[-1]), flush=True)
    if not passed:
        print('Required isolated write/reopen gate failed; inspect the retained startup/test failure before continuing.', flush=True)
        break
raise SystemExit(0 if all(result['passed'] for result in results) else 1)
