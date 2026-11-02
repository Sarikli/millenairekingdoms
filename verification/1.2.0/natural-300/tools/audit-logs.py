"""Audit retained actual JVM logs; warning counts do not imply harmlessness."""
from pathlib import Path
from collections import Counter
import argparse
import json
import re

parser = argparse.ArgumentParser()
parser.add_argument('--captures', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
report = {}
for segment, phase in [('03', 'write'), ('04', 'resume'), ('05', 'resume'), ('05', 'reopen')]:
    path = args.captures / ('attempt-' + segment) / (phase + '.log')
    text = path.read_text(errors='replace')
    lines = text.splitlines()
    errors = [s.split('/ERROR]', 1)[1].strip() for s in lines if '/ERROR]' in s]
    warnings = [s.split('/WARN]', 1)[1].strip() for s in lines if '/WARN]' in s]
    def category(s):
        if 'YggdrasilServicesKeyInfo' in s: return 'Yggdrasil public-key fetch'
        if 'POI' in s or 'PoiManager' in s: return 'Native POI mismatch'
        return s
    normalized = Counter(re.sub(r'[-+]?\d+(?:\.\d+)?', 'N', s) for s in warnings)
    report['attempt-' + segment + '/' + phase] = {
        'error_headings': len(errors),
        'error_categories': dict(Counter(category(s) for s in errors)),
        'warning_headings': len(warnings),
        'native_no_building_location': sum('Growth: no location found' in s for s in warnings),
        'unloaded_treasury_deferrals': sum('RoyalConsolidationRuntime' in s and 'Treasury chunk is unloaded' in s for s in warnings),
        'native_goal_watchdogs': sum('GoalScheduler' in s and 'Watchdog:' in s for s in warnings),
        'native_raid_timeouts': sum('Raid timeout' in s for s in warnings),
        'native_pathfinding_budget_failures': sum('budget_exhausted' in s for s in warnings),
        'native_waypoint_rebuild_slow': sum('rebuild SLOW' in s for s in warnings),
        'most_frequent_normalized_warnings': [{'count': n, 'message': s} for s, n in normalized.most_common(20)],
        'build_successful': 'BUILD SUCCESSFUL' in text,
        'all_dimensions_saved': 'All dimensions are saved' in text,
        'matching_runtime_failure_lines': [s for s in lines if re.search(
            r'\[Kingdoms\].*failed|NATURAL_300_FAILED|OutOfMemoryError|Encountered an unexpected exception', s)],
    }
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: {x: v[x] for x in ['error_headings', 'warning_headings',
      'native_no_building_location', 'native_goal_watchdogs', 'native_raid_timeouts',
      'native_pathfinding_budget_failures']} for k, v in report.items()}))
