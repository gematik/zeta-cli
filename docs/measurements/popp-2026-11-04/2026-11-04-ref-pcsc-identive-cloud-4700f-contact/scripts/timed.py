import subprocess,re,json,sys,time
out,label,n=sys.argv[1],sys.argv[2],int(sys.argv[3]); cmd=sys.argv[4:]
keep=re.compile(r'using card reader|popp WS connected|card round|TIMING|PoPP token received|PACE')
runs=[]
for i in range(n):
    t0=time.perf_counter(); p=subprocess.Popen(cmd,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
    ev=[];ap=[];pend=None;round_=0
    for l in p.stderr:
        t=(time.perf_counter()-t0)*1000
        m=re.search(r'card -> ([0-9a-fA-F]+)',l)
        if m: pend=(t,m[1][:8].upper(),len(m[1])//2); continue
        m=re.search(r'card <- ([0-9a-fA-F]+)',l)
        if m and pend:
            ap.append(dict(round=round_,hdr=pend[1],cmd=pend[2],resp=len(m[1])//2,sw=m[1][-4:].upper(),ms=t-pend[0],at=pend[0])); pend=None; continue
        m=re.search(r'popp (send|recv): .*?"type"\s*:\s*"(\w+)"',l)
        if m: ev.append((t,m[1]+':'+m[2])); continue
        if 'card round' in l: round_+=1
        if keep.search(l): ev.append((t,re.sub(r'eyJ[\w.-]+','<jwt>',l.strip())[:170]))
    p.wait(); runs.append(dict(i=i,rc=p.returncode,wall=(time.perf_counter()-t0)*1000,ev=ev,ap=ap))
    print(i,'rc',p.returncode,'wall %.0f'%runs[-1]['wall'],'apdus',len(ap),'card %.0f'%sum(a['ms'] for a in ap),flush=True)
json.dump(runs,open(f'{out}/{label}.json','w'))
