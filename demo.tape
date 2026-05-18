# VHS tape — animated demo of the zeta CLI.
# Render with: just demo   (or: vhs demo.tape)
# Requires: brew install vhs  +  ./gradlew :cli:installDist

Output demo.gif

Set Shell           "bash"
Set FontSize        16
Set Width           1200
Set Height          760
Set Padding         20
Set Theme           "Catppuccin Mocha"
Set TypingSpeed     40ms
Set PlaybackSpeed   1.0

# Put the freshly-built distribution on PATH without recording the setup.
Hide
Type "export PATH=$PWD/cli/build/install/zeta/bin:$PATH" Enter
Type "clear" Enter
Show

# 1) Lay of the land.
Type "zeta --help"
Sleep 500ms
Enter
Sleep 5s

Type "clear" Enter
Sleep 300ms

# 2) Version — quickest proof it's wired up.
Type "zeta version"
Sleep 400ms
Enter
Sleep 1500ms

Type "clear" Enter
Sleep 300ms

# 3) Discover a Zeta-Guard-protected resource (RFC 9728). No auth needed.
Type "zeta discover https://popp.dev.poppservice.de"
Sleep 500ms
Enter
Sleep 6s

Type "clear" Enter
Sleep 300ms

# 4) Status — show what got cached locally.
Type "zeta status"
Sleep 400ms
Enter
Sleep 3500ms

Type "clear" Enter
Sleep 300ms

# 5) Authentication options — Connector method or PKCS#12 fallback.
Type "zeta login --help"
Sleep 500ms
Enter
Sleep 6s

Type "clear" Enter
Sleep 300ms

# 6) PoPP via the Konnektor — the eGK scenario.
Type "zeta popp connector --help"
Sleep 500ms
Enter
Sleep 5s

Sleep 1s
