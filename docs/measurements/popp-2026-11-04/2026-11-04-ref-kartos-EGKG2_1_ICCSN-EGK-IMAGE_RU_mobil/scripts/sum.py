import json,sys,statistics as st
runs=json.load(open(sys.argv[1])); ok=[r for r in runs if r['rc']==0]
print('ok',len(ok),'of',len(runs))
def row(name,vals):
    if vals:
        v=sorted(vals); print(f'{name:38s} n={len(v):3d} min={v[0]:7.0f} med={st.median(v):7.0f} max={v[-1]:7.0f}')
row('wall',[r['wall'] for r in ok])
names=[]
for r in ok:
    for s in r['spans']:
        k=s[0]+(('/'+s[2]) if s[2] else '')
        if k not in names: names.append(k)
for k in names:
    per=[]
    for r in ok:
        x=[s[1] for s in r['spans'] if (s[0]+(('/'+s[2]) if s[2] else ''))==k]
        if x: per.append((sum(x),len(x)))
    row(k+f' x{per[0][1]}',[p[0] for p in per])
def g(r,n,t=None): return sum(s[1] for s in r['spans'] if s[0]==n and (t is None or s[2]==t))
row('jvm+rest=wall-cli.run',[r['wall']-g(r,'cli.run') for r in ok])
row('ws.recv Scenario total',[g(r,'popp.ws.recv','StandardScenarioMessage')+g(r,'popp.ws.recv','ConnectorScenarioMessage') for r in ok])
row('connect-minus-ws(login+wsopen+card)',[g(r,'popp.connect')-g(r,'popp.ws.recv')-g(r,'popp.ws.send') for r in ok])
