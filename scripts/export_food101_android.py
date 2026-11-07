import sys,json,hashlib,time
from pathlib import Path
root=Path.cwd();base=root/'data/private/food-classifier';sys.path.insert(0,str(root/'data/private/mobile-sam/python'));sys.path.insert(0,str(base/'python'))
import torch
from transformers import AutoModelForImageClassification,AutoImageProcessor
from PIL import Image
source=base/'prithivMLmods--Food-101-93M';output=base/'android';output.mkdir(exist_ok=True)
model=AutoModelForImageClassification.from_pretrained(source,local_files_only=True,attn_implementation='eager').eval();torch.set_num_threads(4)
class Wrapper(torch.nn.Module):
 def __init__(self,m):super().__init__();self.m=m
 def forward(self,pixel_values):return self.m(pixel_values=pixel_values).logits
path=output/'food101.onnx'
with torch.inference_mode():torch.onnx.export(Wrapper(model),torch.zeros(1,3,224,224),str(path),opset_version=17,input_names=['pixel_values'],output_names=['logits'],dynamo=False)
labels=[model.config.id2label[i] for i in range(101)];(output/'labels.json').write_text(json.dumps(labels),encoding='utf-8')
import onnxruntime as ort,numpy as np
options=ort.SessionOptions();options.intra_op_num_threads=4
session=ort.InferenceSession(str(path),sess_options=options,providers=['CPUExecutionProvider']);processor=AutoImageProcessor.from_pretrained(source,local_files_only=True,use_fast=False)
rows=json.loads((root/'data/private/eval50/manifest.json').read_text());same=0;maximum=0
for r in rows:
 image=Image.open(root/'data/private/eval50'/r['file']).convert('RGB');x=processor(images=image,return_tensors='pt')['pixel_values']
 with torch.inference_mode():ref=model(pixel_values=x).logits.numpy()
 got=session.run(None,{'pixel_values':x.numpy()})[0];same+=int(ref.argmax()==got.argmax());maximum=max(maximum,float(np.max(np.abs(ref-got))))
assert same==50
meta={'model':'prithivMLmods/Food-101-93M','revision':'addcbf20006b2ec0eb1bdff313ef732cd8634f11','sha256':hashlib.file_digest(path.open('rb'),'sha256').hexdigest(),'bytes':path.stat().st_size,'onnx_top1_matches':same,'max_logit_difference':maximum,'input':[1,3,224,224],'normalization':'(RGB/255-0.5)/0.5'}
(output/'manifest.json').write_text(json.dumps(meta,indent=2));print(json.dumps(meta),flush=True)
assets=root/'mobile/android/app/src/main/assets/food101';assets.mkdir(parents=True,exist_ok=True)
import shutil
for name in ['food101.onnx','labels.json','manifest.json']:shutil.copyfile(output/name,assets/name)
