P=~/.config/telematik/test-identities/ICCSN-SMCB-C_SMCB_AUT_E256_X509/ICCSN-SMCB-C_SMCB_AUT_E256_X509.p12
O=<scratchpad>/bench/pcsc
A=(popp standard --env ref --reader "Reader 01" --trace --auth-method p12 --auth-p12-file "$P" --profile perfref)
python3 <scratchpad>/bench/run.py $O warm 10 sh -c 'exec "$@" >/dev/null' _ zeta "${A[@]}"
python3 <scratchpad>/bench/sum.py $O/warm.json
python3 <scratchpad>/bench/timed.py $O timed 5 zeta "${A[@]}" -vv
