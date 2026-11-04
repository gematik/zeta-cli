import subprocess,re,json,sys,time,statistics as st,os
out,label,n=sys.argv[1],sys.argv[2],int(sys.argv[3]); cmd=sys.argv[4:]
def dur(s):
    s=s.replace(',','.')
    m=re.match(r'([\d.]+)(ms|s)',s); return float(m[1])*(1 if m[2]=='ms' else 1000)
runs=[]
for i in range(n):
    t=time.time(); p=subprocess.run(cmd,capture_output=True,text=True); wall=(time.time()-t)*1000
    err=re.sub(r'eyJ[\w.-]+','<jwt>',p.stderr)
    open(f'{out}/{label}-{i}.log','w').write(err)
    spans=[]
    for l in err.splitlines():
        m=re.match(r'^[\s│├└─]*([a-zA-Z][\w.]*) \(([\d,.]+(?:ms|s))\)(.*)$',l)
        if m:
            t2=re.search(r'type=(\w+)',m[3]); spans.append((m[1],dur(m[2]),t2[1] if t2 else None,m[3][:60]))
    runs.append(dict(i=i,rc=p.returncode,wall=wall,spans=spans))
    print(i,'rc',p.returncode,'wall %.0f'%wall,flush=True)
json.dump(runs,open(f'{out}/{label}.json','w'))
