"""Describe recorded outcomes; incomplete worlds never become completed observations."""
from pathlib import Path
from collections import Counter
import argparse, json, math, csv

parser=argparse.ArgumentParser()
parser.add_argument('--world',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args();root=args.world/'world/natural-observation';out=args.output;out.mkdir(parents=True,exist_ok=True)
def lines(path):
 if not path.exists():return []
 raw=path.read_text();rows=raw.splitlines(keepends=True)
 if rows and not rows[-1].endswith('\n'):
  if(root/'final.json').exists():raise ValueError('Incomplete line in completed observation: '+str(path))
  rows.pop()
 return [json.loads(s)for s in rows if s.strip()]
generated=lines(root/'generation.jsonl')
if not(root/'baseline.json').exists():
 print(json.dumps({'stage':'natural exploration before baseline','generated_villages':len(generated),'natural_capitals_observed':sum(x['owner']is not None for x in generated),'completed_days':0}));raise SystemExit(0)
baseline=json.loads((root/'baseline.json').read_text());daily=lines(root/'daily.jsonl');latest=daily[-1];complete=(root/'final.json').exists();reopened=(root/'reopen.json').exists()
final=json.loads((root/'final.json').read_text())if complete else latest
if complete:
 assert final['consecutiveTicks']==final['gameTime']-baseline['gameTime']==final['dayTime']-baseline['dayTime']==7200000
 assert [d['relativeDay']for d in daily]==list(range(301))
 assert all(d['observedTicks']==d['relativeDay']*24000 for d in daily)
 assert all(d['observerExplorationTickets']==0 for d in daily)
events=lines(root/'history.jsonl');assert len(events)==len({e['id']for e in events})
observed=[e for e in events if baseline['gameTime']<e['gameTime']<=final['gameTime']]
counts=Counter(e['kind']for e in observed)
bkingdoms={k['id']:k for k in baseline['kingdoms']};fkingdoms={k['id']:k for k in final['kingdoms']}
def independent(row):return sum(v['owner']is None and v['parent']is None for v in row['villages'])
def stage_counts(row,key):return dict(Counter(v['stage']for v in row.get(key,[])))
def hcounts(identifier):return dict(Counter(e['kind']for e in observed if any(r['id']==identifier for r in e['realms'])))
perkingdom=[]
for identifier,k in fkingdoms.items():
 initial=bkingdoms.get(identifier);village=next((v for v in final['villages']if v['id']==k['capital']),None)
 perkingdom.append({'id':identifier,'name':k['name'],'ethos':k['ethos'],'capital':None if village is None else village['name'],'initial_members_including_capital':None if initial is None else len(initial['members']),'final_members_including_capital':len(k['members']),'fallen':k['fallen'],'initial_physical_coins':None if initial is None else initial['spendableCoins'],'final_physical_coins':k['spendableCoins'],'final_coin_unavailability':k.get('coinReason'),'initial_owed':None if initial is None else initial['owed'],'final_owed':k['owed'],'events_involving_realm':hcounts(identifier)})

# Sampled relationship eligibility is descriptive: it does not assert that every daily
# snapshot coincides with the exact earlier political evaluation tick or ignores raid guards.
relationship_samples=[]
for d in daily:
 villages={v['id']:v for v in d['villages']};ks={k['id']:k for k in d['kingdoms']if not k['fallen']and k['active']}
 for v in villages.values():
  if v['owner']is not None or v['parent']is not None:continue
  for k in ks.values():
   capital=villages.get(k['capital'])
   if not capital:continue
   distance=math.sqrt((v['x']-capital['x'])**2+(v['y']-capital['y'])**2+(v['z']-capital['z'])**2)
   relation=capital['relations'].get(v['id']);within=distance<=2000
   proximity=1-min(1,distance/2000);chance=.1*(.25+.75*proximity)/(1+len(k['members'])/5)
   relationship_samples.append({'day':d['relativeDay'],'village':v['id'],'village_name':v['name'],'kingdom':k['id'],'kingdom_name':k['name'],'distance':round(distance,3),'relation':relation,'within_default_relation_radius':within,'relationship_and_distance_qualified':within and relation is not None and relation>=50,'calculated_chance_if_other_guards_allow':chance})
if relationship_samples:
 with(out/'relationship-samples.csv').open('w',newline='')as f:
  w=csv.DictWriter(f,fieldnames=list(relationship_samples[0]));w.writeheader();w.writerows(relationship_samples)

bbuildings={b['id']:b for v in baseline['villages']for b in v['buildings']}
fbuildings={b['id']:b for v in final['villages']for b in v['buildings']}
upgraded=[{'id':key,'before':bbuildings[key]['level'],'after':b['level'],'plan':b['plan']}for key,b in fbuildings.items()if key in bbuildings and b['level']>bbuildings[key]['level']]
new_complete=[b for key,b in fbuildings.items()if key not in bbuildings and b['status']=='COMPLETE']
summary={'observation_complete':complete,'separate_jvm_reopen_verified':reopened,'completed_actual_days':final['relativeDay'],'actual_observed_ticks':final['observedTicks'],'baseline_world_day_time':baseline['dayTime'],'final_world_day_time':final['dayTime'],'initial_kingdoms':len(baseline['kingdoms']),'final_living_kingdoms':sum(not k['fallen']for k in final['kingdoms']),'final_fallen_kingdoms':sum(k['fallen']for k in final['kingdoms']),'initial_villages':len(baseline['villages']),'final_villages':len(final['villages']),'initial_independent_non_hamlets':independent(baseline),'final_independent_non_hamlets':independent(final),'event_counts_with_causal_times_inside_observation':dict(counts),'final_construction_stages':stage_counts(final,'construction'),'final_shipment_stages':stage_counts(final,'shipments'),'native_building_records_upgraded':len(upgraded),'new_native_complete_building_records':len(new_complete),'resident_records_initial':sum(v['residentRecords']for v in baseline['villages']),'resident_records_final':sum(v['residentRecords']for v in final['villages']),'initial_active_villages':sum(v['active']for v in baseline['villages']),'final_active_villages':sum(v['active']for v in final['villages']),'observer_exploration_tickets_during_measurement':sorted({d['observerExplorationTickets']for d in daily}),'daily_snapshots':len(daily),'kingdoms':perkingdom,'relationship_sample_count':len(relationship_samples),'relationship_and_distance_qualified_samples':sum(r['relationship_and_distance_qualified']for r in relationship_samples),'relationship_samples_scope':'Daily samples, not exact per-evaluation eligibility; native control/raid/order guards also apply.','resident_scope':'Persistent native resident records; not a count of simultaneously loaded living NPC entities.','native_building_scope':'Native building records include original wall/sub-building records; no template-block completion proof inferred here.','new_native_upgrades':upgraded,'events':observed}
if complete:
 summary['observation_wall_seconds']=final['wallSeconds'];summary['wall_time_scope']=final.get('wallTimeScope','original uninterrupted JVM')
(out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
with(out/'daily.csv').open('w',newline='')as f:
 fields=['day','actual_ticks','living_kingdoms','independent_villages','active_villages','resident_records','active_wars','history_events','royal_construction_completed','shipments_delivered','loaded_chunks','observer_tickets']
 writer=csv.DictWriter(f,fieldnames=fields);writer.writeheader()
 for d in daily:writer.writerow(dict(zip(fields,[d['relativeDay'],d['observedTicks'],sum(not k['fallen']for k in d['kingdoms']),independent(d),sum(v['active']for v in d['villages']),sum(v['residentRecords']for v in d['villages']),None if d['wars']is None else sum(w['status']=='ACTIVE'for w in d['wars']),d['historySize'],sum(x['stage']=='COMPLETED'for x in d.get('construction',[])),sum(x['stage']=='DELIVERED'for x in d.get('shipments',[])),d['loadedChunks'],d['observerExplorationTickets']])))
print(json.dumps({key:value for key,value in summary.items()if key not in ['events','new_native_upgrades']},ensure_ascii=False,indent=2))
