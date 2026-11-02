"""Preserve an exact completed-day prefix while the original server keeps running."""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import subprocess
import tarfile
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--world', type=Path, required=True)
parser.add_argument('--day', type=int, required=True)
parser.add_argument('--log', type=Path, required=True)
parser.add_argument('--native-samples', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert 0 < args.day < 300, 'Final results require the actual completion/reopen lifecycle'
root = args.world / 'world/natural-observation'
out = args.output
out.mkdir(exist_ok=False, parents=True)
tools = Path(__file__).resolve().parent
sha = lambda raw: hashlib.sha256(raw).hexdigest()
daily = [json.loads(s) for s in (root / 'daily.jsonl').read_bytes().splitlines(keepends=True)
         if s.endswith(b'\n') and json.loads(s)['relativeDay'] <= args.day]
assert [d['relativeDay'] for d in daily] == list(range(args.day + 1))
last = daily[-1]
baseline = json.loads((root / 'baseline.json').read_bytes())
assert last['observedTicks'] == last['gameTime'] - baseline['gameTime'] == last['dayTime'] - baseline['dayTime'] == args.day * 24000
assert all(d['observerExplorationTickets'] == 0 for d in daily)

with tempfile.TemporaryDirectory(prefix='natural300-readonly-', dir='/tmp') as temp:
    filtered = Path(temp) / 'world/natural-observation'
    filtered.mkdir(parents=True)
    for name in ['daily.jsonl', 'history.jsonl', 'political-before-day.jsonl', 'generation.jsonl']:
        raw = b''.join(s for s in (root / name).read_bytes().splitlines(keepends=True)
                       if s.endswith(b'\n') and json.loads(s)['gameTime'] <= last['gameTime'])
        destination = ('daily-through-day-' + f'{args.day:03}' if name == 'daily.jsonl' else name[:-6]) + '.jsonl.gz'
        (out / destination).write_bytes(gzip.compress(raw, mtime=0))
        (filtered / name).write_bytes(raw)
    raw = (root / 'baseline.json').read_bytes()
    (filtered / 'baseline.json').write_bytes(raw)
    (out / 'baseline.json').write_bytes(raw)
    (out / 'latest.json').write_text(json.dumps(last, ensure_ascii=False, indent=2) + '\n')
    for script in ['analyze-observation.py', 'analyze-decisions.py']:
        result = subprocess.run(['python', str(tools / script), '--world', temp,
                                 '--output', str(out / 'analysis')], check=True, capture_output=True)
        (out / (script[:-3] + '.log')).write_bytes(result.stdout + result.stderr)

prefix = []
for line in args.log.read_bytes().splitlines(keepends=True):
    prefix.append(line)
    if b'NATURAL_300_DAY ' in line and json.loads(line.split(b'NATURAL_300_DAY ', 1)[1]).get('relativeDay') == args.day:
        break
else:
    raise ValueError('Actual day marker missing from original stdout')
(out / f'resume-log-through-day-{args.day:03}.txt.gz').write_bytes(gzip.compress(b''.join(prefix), mtime=0))
sample = args.native_samples / f'day-{args.day:03}'
receipt = json.loads((sample / 'receipt.json').read_bytes())
assert receipt['day'] == args.day and receipt['observer_game_time'] == last['gameTime']
for name, info in receipt['files'].items():
    raw = (sample / name).read_bytes()
    assert sha(raw) == info['sha256'] and len(raw) == info['bytes']
with tarfile.open(out / f'native-state-day-{args.day:03}.tar.gz', 'w:gz') as tar:
    tar.add(sample, arcname=sample.name)
# Derived CSV is portable text; original compressed JSONL/NBT/stdout stays verbatim.
for file in (out / 'analysis').glob('*.csv'):
    file.write_bytes(file.read_bytes().replace(b'\r\n', b'\n'))
provenance = {'partial': True, 'completed_days': args.day, 'elapsed_actual_ticks': args.day * 24000,
              'game_time_cutoff': last['gameTime'], 'day_time_cutoff': last['dayTime'],
              'daily_rows': len(daily), 'source_world': str(args.world),
              'world_read_only': True, 'native_sample_atomic_across_stores': False,
              'tools': {name: sha((tools / name).read_bytes()) for name in
                        ['preserve-checkpoint.py', 'analyze-observation.py', 'analyze-decisions.py']}}
(out / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
manifest = {str(file.relative_to(out)): {'bytes': file.stat().st_size, 'sha256': sha(file.read_bytes())}
            for file in sorted(out.rglob('*')) if file.is_file()}
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(provenance))
