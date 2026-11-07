import sys,os,json,time,hashlib,re
from pathlib import Path
ROOT=Path.cwd();base=ROOT/'data/private/food-classifier';base.mkdir(exist_ok=True)
sys.path.insert(0,str(ROOT/'data/private/mobile-sam/python'));sys.path.insert(0,str(base/'python'))
os.environ['HF_HUB_DISABLE_XET']='1'
os.environ['TOKENIZERS_PARALLELISM']='false'
import torch
from huggingface_hub import HfApi,snapshot_download
from transformers import AutoImageProcessor,AutoModelForImageClassification
from PIL import Image,ImageOps
torch.set_num_threads(4)
photos=ROOT/'data/private/eval50';inputs=json.loads((photos/'manifest.json').read_text())
def canon(name):return re.sub(r'[^a-z0-9]','',name.lower())
for repo in ['nateraw/food','prithivMLmods/Food-101-93M']:
 name=repo.replace('/','--');folder=base/name;folder.mkdir(exist_ok=True)
 info=HfApi().model_info(repo);files=[f.rfilename for f in info.siblings]
 weights='model.safetensors' if 'model.safetensors' in files else 'pytorch_model.bin'
 print('DOWNLOAD',repo,info.sha,weights,flush=True)
 snapshot_download(repo,revision=info.sha,local_dir=folder,allow_patterns=['config.json','preprocessor_config.json','README.md',weights],max_workers=2)
 provenance={'repo':repo,'revision':info.sha,'torch':torch.__version__,'device':'PC CPU','threads':4,'weights':weights,'sha256':hashlib.file_digest((folder/weights).open('rb'),'sha256').hexdigest()}
 (folder/'provenance.json').write_text(json.dumps(provenance,indent=2))
 started=time.perf_counter();processor=AutoImageProcessor.from_pretrained(folder,use_fast=False,local_files_only=True);model=AutoModelForImageClassification.from_pretrained(folder,local_files_only=True).eval();load=time.perf_counter()-started
 labels=model.config.id2label
 assert all(canon(row['label']) in {canon(v) for v in labels.values()} for row in inputs),'Missing true labels'
 results=[]
 with torch.inference_mode():
  # Warm up separately from evaluated observations.
  warm=processor(images=Image.new('RGB',(512,512)),return_tensors='pt');model(**warm)
  for row in inputs:
   image=ImageOps.exif_transpose(Image.open(photos/row['file'])).convert('RGB')
   tick=time.perf_counter();processed=processor(images=image,return_tensors='pt');logits=model(**processed).logits[0];probs=torch.softmax(logits,dim=-1);scores,indices=probs.topk(3);elapsed=time.perf_counter()-tick
   top=[{'label':labels[int(i)],'score':float(s)} for i,s in zip(indices,scores)]
   record={**row,'top3':top,'top1_correct':canon(top[0]['label'])==canon(row['label']),'top3_correct':any(canon(x['label'])==canon(row['label']) for x in top),'elapsed_seconds':elapsed};results.append(record)
   output={'provenance':provenance,'load_seconds':load,'completed':len(results),'results':results}
   (folder/'eval50-results.json').write_text(json.dumps(output,indent=2),encoding='utf-8')
   print(repo,len(results),row['label'],'=>',top[0]['label'],record['top1_correct'],round(elapsed,3),flush=True)
 del model,processor
 print('DONE',repo,'TOP1',sum(r['top1_correct'] for r in results),'TOP3',sum(r['top3_correct'] for r in results),flush=True)

