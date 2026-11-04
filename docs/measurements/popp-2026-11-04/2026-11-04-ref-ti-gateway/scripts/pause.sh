O=<scratchpad>/bench/ti-gateway
for p in 60 120 300 60 120 300; do
  perl -e "select(undef,undef,undef,$p)"
  python3 <scratchpad>/bench/run.py $O pause${p}_$SECONDS 1 bash <scratchpad>/bench/inf.sh >/dev/null
done
python3 - <<'PY'
import json,glob,os
for f in sorted(glob.glob('<scratchpad>/bench/ti-gateway/pause*.json'),key=os.path.getmtime):
    r=json.load(open(f))[0]; s=r['spans']
    print(os.path.basename(f),'rc',r['rc'],'wall %.0f'%r['wall'],'recv',[round(x[1]) for x in s if x[0]=='popp.ws.recv'],'ssa',[round(x[1]) for x in s if x[0]=='connector.secureSendApdu'])
PY
