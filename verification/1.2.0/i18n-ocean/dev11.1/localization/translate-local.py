import os
os.environ['HF_HUB_OFFLINE']='1';os.environ['TRANSFORMERS_OFFLINE']='1';os.environ['TOKENIZERS_PARALLELISM']='false'
from pathlib import Path
import json,re,hashlib,sys,time,collections
import ctranslate2
from transformers import AutoTokenizer
p=Path('/workspace/millenairekingdoms');s=Path('/workspace/royal-decree/i18n-ocean');modeldir=s/'model';tokenizer=AutoTokenizer.from_pretrained(str(modeldir),src_lang='eng_Latn',local_files_only=True);translator=ctranslate2.Translator(str(modeldir),device='cpu',compute_type='int8',inter_threads=1,intra_threads=int(os.environ.get("KINGDOMS_MT_THREADS","2")))
base=json.loads((p/'src/main/resources/assets/millenairekingdoms/lang/en_us.json').read_text());langs={'tr_tr':'tur_Latn','ar_sa':'arb_Arab','cs_cz':'ces_Latn','da_dk':'dan_Latn','de_de':'deu_Latn','es_es':'spa_Latn','et_ee':'est_Latn','fr_fr':'fra_Latn','hi_in':'hin_Deva','hu_hu':'hun_Latn','it_it':'ita_Latn','ja_jp':'jpn_Jpan','ko_kr':'kor_Hang','nb_no':'nob_Latn','nl_nl':'nld_Latn','pl_pl':'pol_Latn','pt_br':'por_Latn','pt_pt':'por_Latn','ru_ru':'rus_Cyrl','sl_si':'slv_Latn','sv_se':'swe_Latn','th_th':'tha_Thai','uk_ua':'ukr_Cyrl','zh_cn':'zho_Hans','zh_tw':'zho_Hant'}
slot=re.compile(r'%(?:\d+\$)?[sdf]|%%');contexts={'Treasury':'Royal treasury','Construction':'Building works','Shipping':'Goods shipments','Capital':'Capital city','Ruler':'Realm ruler','History':'Chronicle','Order':'Royal order','Orders':'Royal orders'}
POLICY='domain-index-v3/'
def indexed(text):
 implicit=0
 def rewrite(m):
  nonlocal implicit
  value=m[0]
  if value=='%%' or '$' in value:return value
  implicit+=1;return '%'+str(implicit)+'$'+value[-1]
 return slot.sub(rewrite,text)
def signatures(text):return collections.Counter(slot.findall(indexed(text)))
def translate(texts,target,beam=2):
 if not texts:return []
 sources=[tokenizer.convert_ids_to_tokens(tokenizer.encode(t)) for t in texts];results=translator.translate_batch(sources,target_prefix=[[target]]*len(sources),beam_size=beam,max_batch_size=16,batch_type='examples',max_decoding_length=max(48,min(384,max(len(source) for source in sources)*3+32)))
 return [tokenizer.decode(tokenizer.convert_tokens_to_ids(row.hypotheses[0]),skip_special_tokens=True).strip() for row in results]
def prepare(text):
 tokens={}
 def protect(m):
  key='ZX'+str(len(tokens)).zfill(3)+'ZX';tokens[key]=m[0];return key
 return slot.sub(protect,text),tokens
def restore(text,tokens):
 for token,value in tokens.items():
  digits=token[2:-2];pattern=r'Z\s*X\s*'+r'\s*'.join(digits)+r'\s*Z\s*X';text=re.sub(pattern,lambda _:value,text,flags=re.I)
 return text
protected_tr=json.loads((s/'protected-tr-459.json').read_text())
def translation_input(key,source):
 text=indexed(contexts.get(source,source))
 if key.startswith('millenairekingdoms.chronicle.'):
  text=text.replace("village's builders","village's construction workers").replace('village’s builders','village’s construction workers').replace('political fortunes','political destiny').replace('earlier fortunes','earlier past').replace('treasury resources','royal funds').replace('Treasury resources','Royal funds')
  text=text.replace('The entry concerns government of the realm','This chronicle entry describes government of the realm')
  if key.endswith('.body.ruler_release'):text='The rule of %6$s over %1$s came to an end. From then on, other hands would govern the realm.'
  if key.endswith('.body.construction_complete'):text="The village's construction workers finished the ordered building work for %5$s at %3$s. The chronicle records this completed work separately from the earlier promise of royal support."
  if key.endswith('.body.investment'):text='The government of %1$s entrusted royal funds to a justified project at %3$s. The results of this work still depended on the labour of the village.'
 if '.construction' in key or '.body.construction' in key:text=text.replace('a commission for','a building project for').replace('commissioned work','ordered building work').replace('A royal commission','A royal building project')
 return text
def source_hash(key,source):return hashlib.sha256((POLICY+source+'\0'+translation_input(key,source)).encode()).hexdigest()
all_stats={}
for language in sys.argv[1:] or langs:
 target=langs[language];assert tokenizer.convert_tokens_to_ids(target)!=tokenizer.unk_token_id,target;cachefile=s/(language+'-local-cache.json');cache=json.loads(cachefile.read_text()) if cachefile.exists() else {};draftfile=s/(language+'-local-draft.json');meta=s/(language+'-local-source-hashes.json');values=json.loads(draftfile.read_text()) if draftfile.exists() else {};hashes=json.loads(meta.read_text()) if meta.exists() else {};jobs={};recipes={};fallbacks=[];started=time.monotonic();repairfile=s/(language+'-local-repairs.json');repairs=json.loads(repairfile.read_text()) if repairfile.exists() else {}
 for key,source in base.items():
  if language=='tr_tr' and key in protected_tr:values[key]=protected_tr[key];hashes[key]=source_hash(key,source);continue
  if hashes.get(key)==source_hash(key,source):continue
  text=translation_input(key,source)
  parts=re.split(r'(?<=[.!?])\s+(?=[A-Z%])',text.strip());recipes[key]=[]
  for part in parts:
   protected,tokens=prepare(part);recipes[key].append((protected,tokens,part));jobs[protected]=None
 pending=sorted([text for text in jobs if text not in cache and re.search('[A-Za-z]',slot.sub('',text))],key=len)
 for start in range(0,len(pending),16):
  texts=pending[start:start+16];cache.update(zip(texts,translate(texts,target)));cachefile.write_text(json.dumps(cache,ensure_ascii=False,indent=2)+'\n');print(language,'segments',start+len(texts),'/',len(pending),flush=True)
 for key,recipe in recipes.items():
  outputs=[]
  for protected,tokens,original in recipe:
   translated=repairs.get(original,restore(cache.get(protected,protected),tokens))
   if signatures(translated)!=signatures(original):
    retry=restore(translate([protected],target,beam=1)[0],tokens)
    if signatures(retry)==signatures(original):translated=retry
    else:
     # Last-resort protected-argument segmentation: no English fallback or fabricated/missing variable.
     pieces=slot.split(original);matches=list(slot.finditer(original));out=[]
     for n,piece in enumerate(pieces):
      translated_piece=translate([piece.strip()],target,beam=1)[0] if re.search('[A-Za-z]',piece) else piece.strip();out.append(translated_piece)
      if n<len(matches):out.append(matches[n][0])
     translated=' '.join(t for t in out if t);fallbacks.append(key)
   if signatures(restore(cache.get(protected,protected),tokens))!=signatures(original):repairs[original]=translated;repairfile.write_text(json.dumps(repairs,ensure_ascii=False,indent=2)+'\n');fallbacks.append(key)
   assert signatures(translated)==signatures(original),(language,key,translated)
   assert not re.search(r'ZX\s*\d+\s*ZX',translated,re.I),(language,key,translated)
   outputs.append(translated)
  value=' '.join(outputs)
  if base[key].endswith(' '):value+=' '
  values[key]=value;hashes[key]=source_hash(key,base[key])
 if language=='pt_pt':
  for key,value in values.items():
   for a,b in [('telas','ecrãs'),('tela','ecrã'),('Tela','Ecrã'),('arquivos','ficheiros'),('arquivo','ficheiro'),('Arquivo','Ficheiro'),('salvar','guardar'),('Salvar','Guardar'),('gerenciar','gerir')]:value=re.sub(r'\b'+a+r'\b',b,value)
   values[key]=value
 assert set(base)<=values.keys() and all(signatures(v)==signatures(values[k]) for k,v in base.items())
 draftfile.write_text(json.dumps(values,ensure_ascii=False,indent=2)+'\n');meta.write_text(json.dumps(hashes,indent=2)+'\n');all_stats[language]={'keys':len(base),'protected_segment_keys':sorted(set(fallbacks)),'elapsed_seconds':round(time.monotonic()-started,2),'native_speaker_review':False,'quality':'Offline machine-assisted draft; automated coverage/format validation is not linguistic approval.'};(s/(language+'-local-stats-v3.json')).write_text(json.dumps(all_stats[language],indent=2)+'\n');print('COMPLETE',language,len(base),'fragment_fallbacks',len(set(fallbacks)),flush=True)
