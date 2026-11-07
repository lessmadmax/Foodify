"""Offline analysis of same-session raw depth registered into a saved reference camera."""
import json
from pathlib import Path
import numpy as np
from ar_volume import read_frame, points_from_depth, polygon_mask, timing_reasons

def rotation(q):
    x,y,z,w=np.asarray(q,dtype=float)/np.linalg.norm(q)
    return np.array([[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)], [2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)], [2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]])

def fuse(reference, folders):
    meta,z,c=read_frame(reference);h,w=z.shape
    _,uv=points_from_depth(meta,z)
    # Pixel mapping in these app captures is an affine crop, with no skew.
    s=np.asarray(meta['depthNormalizedCorners']).reshape(4,2);t=np.asarray(meta['correspondingCpuImageNormalizedCorners']).reshape(4,2)
    affine=np.linalg.lstsq(np.c_[s,np.ones(4)],t,rcond=None)[0]
    inv=np.linalg.inv(np.vstack([affine.T,[0,0,1]]))
    intr=meta['cpuIntrinsics'];f=np.asarray(intr['focalLength']);p=np.asarray(intr['principalPoint']);dims=np.asarray(intr['imageDimensions'])
    rr=rotation(meta['rotationQuaternionXyzw']);tr=np.asarray(meta['translationMetres'])*1000
    observations=[[]for _ in range(w*h)];accepted=[]
    seen=set()
    for folder in folders:
        m,d,conf=read_frame(folder)
        key=m['depthTimestampNs']
        if key in seen:continue
        seen.add(key)
        lag=(int(m['frameTimestampNs'])-int(m['depthTimestampNs']))/1e6
        if timing_reasons(m):continue
        xyz,_=points_from_depth(m,d);good=(d>0)&(conf>=128)
        cv=xyz[good];gl=cv*np.array([1,-1,-1])
        world=gl@rotation(m['rotationQuaternionXyzw']).T+np.asarray(m['translationMetres'])*1000
        ref=((world-tr)@rr)*np.array([1,-1,-1]);goodz=ref[:,2]>0;ref=ref[goodz]
        cpu=(ref[:,:2]/ref[:,2,None]*f+p)/dims
        depth_uv=np.c_[cpu,np.ones(len(cpu))]@inv.T
        ij=np.floor(depth_uv[:,:2]*[w,h]).astype(int)
        inside=(ij[:,0]>=0)&(ij[:,0]<w)&(ij[:,1]>=0)&(ij[:,1]<h)
        per={}
        for (x,y),point in zip(ij[inside],ref[inside]):
            key=y*w+x;v=float(point[2]);per[key]=min(v,per.get(key,float('inf')))
        for key,v in per.items():observations[key].append(v)
        accepted.append(str(folder.name))
    fused=np.array([np.median(v) if v else 0 for v in observations]).reshape(h,w)
    spread=np.array([max(v)-min(v) if v else 0 for v in observations]).reshape(h,w)
    confidence=np.where((fused>0)&(spread<=10),255,0).astype(np.uint8)
    return meta,fused,confidence,uv,accepted

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('data/private/ar/depth-improvement-20261104'))
    root=parser.parse_args().root
    output=[]
    for r in json.loads((root/'records.json').read_text(encoding='utf8')):
        ref=root/'sessions'/r['frameRelativePath'];m,d,c,uv,accepted=fuse(ref,sorted(ref.parent.glob('frame-*')))
        food=polygon_mask(uv,r['foodRegion']['foodPolygons']);base=polygon_mask(uv,r['baseRegion']['foodPolygons'])&~food
        item={'group':r['portionGroup'],'frames':len(accepted),'foodPixels':int(food.sum()),'fusedValidFoodPixels':int(((c>0)&food).sum()),'fusedValidBasePixels':int(((c>0)&base).sum())}
        output.append(item);print(json.dumps(item,ensure_ascii=False))
    (root/'fusion-analysis.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf8')
