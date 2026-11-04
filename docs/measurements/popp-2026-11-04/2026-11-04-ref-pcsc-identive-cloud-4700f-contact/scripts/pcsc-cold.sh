P=~/.config/telematik/test-identities/ICCSN-SMCB-C_SMCB_AUT_E256_X509/ICCSN-SMCB-C_SMCB_AUT_E256_X509.p12
O=<scratchpad>/bench/pcsc
A=(popp standard --env ref --reader "Reader 01" --trace --auth-method p12 --auth-p12-file "$P" --profile perfref)
for i in 0 1 2; do
  rm -f ~/.config/telematik/zeta/perfref.storage.db
  python3 <scratchpad>/bench/run.py $O cold$i 1 sh -c 'exec "$@" >/dev/null' _ zeta "${A[@]}"
done
