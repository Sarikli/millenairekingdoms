"""Standalone scientific figures from the recorded dataset, with explicit partial-run labels."""
from pathlib import Path
import argparse,json,csv
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

parser=argparse.ArgumentParser();parser.add_argument('--world',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);a=parser.parse_args()
root=a.world/'world/natural-observation';out=a.output;out.mkdir(parents=True,exist_ok=True)
if not(root/'baseline.json').exists():raise SystemExit('No observation baseline yet; no figure generated')
baseline=json.loads((root/'baseline.json').read_text());daily=[json.loads(s)for s in(root/'daily.jsonl').read_text().splitlines(keepends=True)if s.strip()and s.endswith('\n')];last=daily[-1]
complete=(root/'final.json').exists();suffix='300 actual days complete'if complete else f'Partial: {last["relativeDay"]} actual days'
ids=list(dict.fromkeys(k['id']for d in daily for k in d['kingdoms']));colors={k:plt.get_cmap('tab20')(n%20)for n,k in enumerate(ids)}
names={k['id']:k['name'].removeprefix('Kingdom of ')for d in daily for k in d['kingdoms']}
days=[d['relativeDay']for d in daily]
fig,axes=plt.subplots(2,2,figsize=(13,8),layout='constrained')
for identifier in ids:
 values=[next((len(k['members'])if not k['fallen']else 0 for k in d['kingdoms']if k['id']==identifier),0)for d in daily]
 axes[0,0].plot(days,values,label=names[identifier],color=colors[identifier],linewidth=1.8)
axes[0,0].set(title='Kingdom membership (capital included)',ylabel='Villages');axes[0,0].legend(fontsize=8)
axes[0,1].plot(days,[sum(v['owner']is None and v['parent']is None for v in d['villages'])for d in daily],label='Independent villages',color='#545b65')
axes[0,1].plot(days,[float('nan')if d['wars']is None else sum(w['status']=='ACTIVE'for w in d['wars'])for d in daily],label='Active wars (unrecorded = gap)',color='#c44536');axes[0,1].set(title='Independent villages and active wars',ylabel='Count');axes[0,1].legend(fontsize=8)
for identifier in ids:
 values=[next((k['spendableCoins']if k['spendableCoins']is not None else float('nan')for k in d['kingdoms']if k['id']==identifier),float('nan'))for d in daily]
 axes[1,0].plot(days,values,color=colors[identifier],label=names[identifier])
axes[1,0].set(title='Read-only physical treasury observations',ylabel='Deniers (unavailable storage = gap)');axes[1,0].legend(fontsize=8)
axes[1,1].plot(days,[sum(x['stage']=='COMPLETED'for x in d.get('construction',[]))for d in daily],label='Completed royal orders',color='#257a49')
axes[1,1].plot(days,[sum(x['stage']=='DELIVERED'for x in d.get('shipments',[]))for d in daily],label='Delivered shipments',color='#2865a8');axes[1,1].set(title='Recorded royal outcomes',ylabel='Count');axes[1,1].legend(fontsize=8)
for ax in axes.flat:ax.set_xlabel('Actual Minecraft days since baseline');ax.grid(alpha=.2);ax.set_xlim(left=0)
fig.suptitle('Natural-world observation — stable 1.2.0 | '+suffix,fontsize=14)
fig.savefig(out/'timeline.png',dpi=160);plt.close(fig)

fig,axes=plt.subplots(1,2,figsize=(13,6),layout='constrained')
for ax,d,title in zip(axes,[baseline,last],['Baseline',f'Day {last["relativeDay"]}']):
 capitals={k['capital']for k in d['kingdoms']if not k['fallen']}
 for v in d['villages']:
  color=colors.get(v['owner'],'#979da6');capital=v['id']in capitals
  ax.scatter(v['x'],v['z'],color=color,marker='*'if capital else 'o',s=170 if capital else 38,edgecolor='#222222',linewidth=.6,zorder=3 if capital else 2)
  if capital:ax.annotate(v['name'],(v['x'],v['z']),xytext=(6,6),textcoords='offset points',fontsize=8)
 ax.set(title=title,xlabel='World X (blocks)',ylabel='World Z (blocks)');ax.set_aspect('equal');ax.invert_yaxis();ax.grid(alpha=.2)
fig.suptitle('Recorded settlement ownership | star = capital; grey = independent\n'+suffix,fontsize=13)
fig.savefig(out/'settlement-ownership.png',dpi=160);plt.close(fig)
print('Written timeline.png and settlement-ownership.png; '+suffix)
