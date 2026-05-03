# VHS tape — animated demo of the zeta CLI.
# Render with: just demo  (or: vhs demo.tape)
# Requires: brew install vhs  +  ./gradlew :cli:installDist

Output demo.gif

Set Shell           "bash"
Set FontSize        16
Set Width           1200
Set Height          720
Set Padding         20
Set Theme           "Catppuccin Mocha"
Set TypingSpeed     45ms
Set PlaybackSpeed   1.0

# Put the freshly-built distribution on PATH without recording the setup.
Hide
Type "export PATH=$PWD/cli/build/install/zeta/bin:$PATH" Enter
Type "clear" Enter
Show

# 1) Top-level help — the lay of the land.
Type "zeta --help"
Sleep 600ms
Enter
Sleep 4s

Type "clear"
Enter
Sleep 300ms

# 2) Version — quickest proof that the CLI is wired up.
Type "zeta version"
Sleep 400ms
Enter
Sleep 2s

Type "clear"
Enter
Sleep 300ms

# 3) Inspect demo
Type "zeta inspect https://popp.dev.poppservice.de"
Sleep 600ms
Enter
Sleep 4s

Type "clear"
Enter
Sleep 300ms

# 4) Register help — second showcase command.
Type "zeta register --help"
Sleep 600ms
Enter
Sleep 4s

Sleep 1s
