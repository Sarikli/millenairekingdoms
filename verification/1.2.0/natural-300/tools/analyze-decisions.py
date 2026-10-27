"""Describe recorded decision opportunities without treating samples as random draws."""
from pathlib import Path
from collections import Counter, defaultdict
import argparse
import csv
import json

parser = argparse.ArgumentParser()
parser.add_argument('--world', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = args.world / 'world/natural-observation'
args.output.mkdir(parents=True, exist_ok=True)


def records(name):
    path = root / name
    if not path.exists():
        return []
    # The live writer may still be appending its last line.
    return [json.loads(line) for line in path.read_text().splitlines(keepends=True)
            if line.endswith('\n') and line.strip()]


def resource(value):
    if isinstance(value, dict):
        return value['namespace'] + ':' + value['path']
    return value


daily = records('daily.jsonl')
assert daily and [d['relativeDay'] for d in daily] == list(range(len(daily)))
baseline, last = daily[0], daily[-1]
complete = (root / 'final.json').exists()
if complete:
    assert len(daily) == 301 and last['observedTicks'] == 7200000
rounds = [r for r in records('political-before-day.jsonl')
          if baseline['gameTime'] < r['gameTime'] <= last['gameTime']]
assert len({r['worldDay'] for r in rounds}) == len(rounds)
village_stats = defaultdict(lambda: {'eligible_pre_round_samples': 0,
                                    'native_military_busy_samples': 0,
                                    'chances': [], 'eligible_world_days': []})
for r in rounds:
    for c in r['independentCandidates']:
        s = village_stats[c['village']]
        s['name'] = c['name']
        s['native_military_busy_samples'] += int(c['nativeMilitaryBusy'])
        if r['politicalRoundDue'] and c['eligibleTarget'] is not None:
            s['eligible_pre_round_samples'] += 1
            s['chances'].append(c['actualJoinChance'])
            s['eligible_world_days'].append(r['worldDay'])
for s in village_stats.values():
    chances = s.pop('chances')
    s['sampled_chance_min'] = min(chances) if chances else None
    s['sampled_chance_max'] = max(chances) if chances else None

strategies, economics = [], {}
per_kingdom = defaultdict(lambda: {'strategy_status_samples': Counter(),
                                 'capital_loaded_samples': 0,
                                 'target_loaded_samples': 0,
                                 'distinct_selections': set()})
for d in daily:
    villages = {v['id']: v for v in d['villages']}
    for k in d['kingdoms']:
        plan = k['strategy']
        if plan is None:
            continue
        capital, target = villages.get(k['capital']), villages.get(plan['target'])
        capital_loaded = bool(capital and capital['loaded'] and capital['active'])
        target_loaded = bool(target and target['loaded'] and target['active'])
        selection = resource(plan['selection'])
        strategies.append({'relative_day': d['relativeDay'], 'world_day': d['bookedDay'],
                           'kingdom': k['id'], 'name': k['name'], 'ethos': k['ethos'],
                           'status': plan['status'], 'logistics': plan['logistics'],
                           'target': plan['target'], 'selection': selection,
                           'capital_loaded_and_active': capital_loaded,
                           'target_loaded_and_active': target_loaded,
                           'reason': plan['reason']})
        s = per_kingdom[k['id']]
        s['name'] = k['name']
        s['strategy_status_samples'][plan['status']] += 1
        s['capital_loaded_samples'] += int(capital_loaded)
        s['target_loaded_samples'] += int(target_loaded)
        if selection:
            s['distinct_selections'].add(selection)
    for decision in d.get('economicDecisions', []):
        economics[decision['id']] = decision
for s in per_kingdom.values():
    s['distinct_selections'] = sorted(s['distinct_selections'])
if strategies:
    with (args.output / 'strategy-samples.csv').open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(strategies[0]))
        writer.writeheader()
        writer.writerows(strategies)

events = [e for e in records('history.jsonl')
          if baseline['gameTime'] < e['gameTime'] <= last['gameTime']]
summary = {
    'observation_complete': complete,
    'completed_actual_days': last['relativeDay'],
    'pre_round_coverage_first_world_day': rounds[0]['worldDay'] if rounds else None,
    'pre_round_coverage_last_world_day': rounds[-1]['worldDay'] if rounds else None,
    'recorded_pre_rounds': len(rounds),
    'due_pre_rounds': sum(r['politicalRoundDue'] for r in rounds),
    'pre_round_scope': 'Read-only native blockers and service eligibility BEFORE daily relation drift/border and actual decisions. These are opportunities, not proof of RNG draws. At most one successful join per kingdom per round; later candidates may not be drawn.',
    'independent_village_samples': dict(village_stats),
    'strategy_scope': 'Daily persistent conditional intentions. Metadata reasons do not prove actual shortages, cargo, fees, paid orders or finished buildings. Physical execution requires current loaded native facts.',
    'kingdom_strategy_samples': dict(per_kingdom),
    'recorded_economic_authorizations': len(economics),
    'economic_outcomes': dict(Counter(d['outcome'] for d in economics.values())),
    'strategic_battles': [e for e in events if e['kind'] == 'STRATEGIC_BATTLE'],
    'ownership_transfers_scope': 'Count only actual VILLAGE_CONQUEST/CAPITAL_CONQUEST events and membership changes; a strategic attacker victory alone need not complete the required campaign.',
    'village_conquest_events': sum(e['kind'] == 'VILLAGE_CONQUEST' for e in events),
    'capital_conquest_events': sum(e['kind'] == 'CAPITAL_CONQUEST' for e in events),
}
(args.output / 'decisions-summary.json').write_text(
    json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'days': last['relativeDay'], 'pre_rounds': len(rounds),
                  'strategy_samples': len(strategies),
                  'economic_authorizations': len(economics),
                  'strategic_battles': len(summary['strategic_battles'])}))
