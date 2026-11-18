from pathlib import Path
import html, json, re, urllib.request

root=Path(__file__).resolve().parents[1]
source=root/'docs/diagrams/foodify-sequence.mmd'
src=source.read_text(encoding='utf-8')
stack=[]
for line in src.splitlines():
    word=line.strip().split(' ',1)[0]
    if word in ['opt','alt','loop','par','critical','break']:stack.append(word)
    elif word=='end':
        if not stack:raise ValueError('Unmatched end')
        stack.pop()
if stack:raise ValueError('Unclosed sequence block')
tick=chr(96)*3
source.with_suffix('.md').write_text(
    '# 음식 사진 분석 및 AR 중량 산출 시퀀스\n\n'
    '일반 사진 분석 결과를 먼저 산출하고, 사용자가 선택한 음식에 한해 별도 AR 촬영으로 중량을 갱신한다. '
    '화살표에는 단계 사이에 전달되는 주요 입출력값을 표시했다.\n\n'
    +tick+'mermaid\n'+src+'\n'+tick+'\n',encoding='utf-8')
qa=root/'data/private/sequence-qa-2026-11-12'
qa.mkdir(parents=True,exist_ok=True)
js=qa/'mermaid.min.js'
if not js.exists():
    req=urllib.request.Request('https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js',headers={'User-Agent':'Mozilla/5.0'})
    with urllib.request.urlopen(req,timeout=25) as r:js.write_bytes(r.read())
page='''<!doctype html><html lang="ko"><meta charset="utf-8"><title>Foodify sequence diagram</title><style>
body{margin:20px;background:white;color:#17212b;font-family:"Malgun Gothic",sans-serif}#diagram svg{display:block;max-width:none!important}h1{font-size:24px;font-weight:600;margin:0 0 16px}
</style><h1>음식 사진 입력부터 결과 산출까지</h1><div id="diagram"></div><script src="mermaid.min.js"></script><script>
mermaid.initialize({startOnLoad:false,securityLevel:'loose',theme:'base',themeVariables:{fontFamily:'Malgun Gothic',fontSize:'18px',primaryColor:'#eef4f8',primaryTextColor:'#17212b',primaryBorderColor:'#8fa5b5',actorBkg:'#eef4f8',actorBorder:'#8fa5b5',actorTextColor:'#17212b',actorLineColor:'#a4b3bf',signalColor:'#374b5f',signalTextColor:'#17212b',noteBkgColor:'#fff9e9',noteBorderColor:'#c6b986',noteTextColor:'#17212b'},sequence:{useMaxWidth:false,diagramMarginX:25,diagramMarginY:16,actorMargin:80,messageMargin:28,noteMargin:14,wrap:true,mirrorActors:false}});
mermaid.render('foodifySequence','''+json.dumps(src,ensure_ascii=False)+''').then(r=>{document.querySelector('#diagram').innerHTML=r.svg;document.body.dataset.done='true';}).catch(e=>{document.body.dataset.error=e.message;});
</script></html>'''
(qa/'render.html').write_text(page,encoding='utf-8')
print('Mermaid source balanced; local renderer prepared:',qa/'render.html')
