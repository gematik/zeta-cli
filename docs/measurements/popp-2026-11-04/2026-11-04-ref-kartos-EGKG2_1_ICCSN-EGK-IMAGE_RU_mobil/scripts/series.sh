#!/bin/bash
SP="$1"; cd <workspace>/zeta-cli
P=~/.config/telematik/test-identities/ICCSN-SMCB-C_SMCB_AUT_E256_X509/ICCSN-SMCB-C_SMCB_AUT_E256_X509.p12
IMG=<workspace>/card-images/EGKG2_1_ICCSN-EGK-IMAGE_RU_mobil.xml
A=(popp kartos --env ref --image "$IMG" --trace --auth-method p12 --auth-p12-file "$P")
DB=~/.config/telematik/zeta/perfref.storage.db
echo COLD
for i in 0 1 2; do rm -f "$DB" "$DB-wal" "$DB-shm"; python3 "$SP/bench/run.py" "$SP/bench" cold$i 1 zeta "${A[@]}" --profile perfref; done
python3 - "$SP/bench" <<'P2'
import json,sys
d=sys.argv[1]; r=[]
for i in range(3): r+=json.load(open(f'{d}/cold{i}.json'))
json.dump(r,open(f'{d}/cold.json','w'))
P2
echo WARM; python3 "$SP/bench/run.py" "$SP/bench" warm 12 zeta "${A[@]}" --profile perfref
echo; echo "== cold"; python3 "$SP/bench/sum.py" "$SP/bench/cold.json"
echo "== warm"; python3 "$SP/bench/sum.py" "$SP/bench/warm.json"
echo; echo "== -vv sample"
zeta popp kartos --env ref --image "$IMG" --auth-method p12 --auth-p12-file "$P" --profile perfref -vv 2>&1 >/dev/null | sed -E 's/eyJ[A-Za-z0-9._-]+/<jwt>/g' | grep -E 'TIMING|kartos|card round|WS connected|token received' | cut -c1-140 | head -40
