#!/bin/bash
SP="$1"; cd <workspace>/zeta-cli
P=~/.config/telematik/test-identities/ICCSN-SMCB-C_SMCB_AUT_E256_X509/ICCSN-SMCB-C_SMCB_AUT_E256_X509.p12
IMG=<workspace>/card-images/EGKG2_1_ICCSN-EGK-IMAGE_RU_mobil.xml
export KP_LOG="$SP/bench/card.ndjson"; rm -f "$KP_LOG"
python3 "$SP/bench/run.py" "$SP/bench" card 10 zeta popp kartos --env ref --image "$IMG" --trace --auth-method p12 --auth-p12-file "$P" --profile perfref --kartos-bin "$SP/bench/kartos-proxy" | tail -1
python3 "$SP/bench/sum.py" "$SP/bench/card.json" | grep -E 'ok |wall|popp\.|connect-minus'
python3 - "$KP_LOG" "$SP/bench/card.json" <<'P2'
import json,sys,statistics as st
L=[json.loads(x) for x in open(sys.argv[1])]; R=json.load(open(sys.argv[2]))
print('runs',len(L),'apdus',[len(x) for x in L][:3])
tot=[sum(a['ms'] for a in x) for x in L]
print('card net total ms  min %.1f med %.1f max %.1f'%(min(tot),st.median(tot),max(tot)))
print('first-apdu at (kartos startup) med %.0f'%st.median([x[0]['at'] for x in L]))
print('max single apdu med %.1f'%st.median([max(a['ms'] for a in x) for x in L]))
print('per apdu of run 0:',[(a['ins'],round(a['ms'],2),a['sw']) for a in L[0]])
def g(r,n): return sum(s[1] for s in r['spans'] if s[0]==n)
rest=[]
for r,x in zip(R,L):
    rest.append(g(r,'popp.connect')-g(r,'popp.ws.recv')-g(r,'popp.ws.send')-sum(a['ms'] for a in x))
print('connect - ws - card (login+wsopen+loop+kartos boot) med %.0f min %.0f max %.0f'%(st.median(rest),min(rest),max(rest)))
P2
