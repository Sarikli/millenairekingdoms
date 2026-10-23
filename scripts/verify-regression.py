#!/usr/bin/env python3
"""Run every suite sequentially and require explicit success in both JVMs."""
import datetime
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
runner = sys.argv[1:]
if runner[:1] == ['--']:
    runner = runner[1:]
runner = runner or ['bash', str(project / 'gradlew')]
suites = {'verifyWars': 30, 'verifyConquest': 25, 'verifyStage5': 24,
          'verifyCapitals': 5, 'verifyAutonomy': 22, 'verifyGui': 7, 'verifyFounding': 12}
results = []
print('Evidence directory:', output, flush=True)
for suite, expected in suites.items():
    print('START', suite, flush=True)
    logfile = output / (suite + '.log')
    with logfile.open('w') as log:
        code = subprocess.run([*runner, '--init-script', str(project / 'scripts/isolated-tests.gradle'),
                               '-Dkingdoms.testSuite=' + suite, '-Dkingdoms.testRun=' + run, suite],
                              cwd=project, stdout=log, stderr=subprocess.STDOUT).returncode
    text = logfile.read_text()
    counts = [int(n) for n in re.findall(r'All (\d+) required tests passed', text)]
    launches = re.findall(r'ISOLATED_TEST suite=\S+ task=(\S+) world=(.+)', text)
    passed = code == 0 and counts == [expected, expected] and len(launches) == 2
    results.append(dict(suite=suite, exit_code=code, required_pass_counts=counts,
                        expected_per_pass=expected, launches=launches, passed=passed))
    (output / 'results.json').write_text(json.dumps(results, indent=2))
    print(json.dumps(results[-1]), flush=True)
    if not counts:
        print('No tests completed; diagnose server startup before running more suites.', flush=True)
        break
raise SystemExit(0 if all(result['passed'] for result in results) else 1)
