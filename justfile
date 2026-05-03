# default: list available recipes
default:
    @just --list

# build the CLI and brew-install it locally from Formula/zeta.rb
install:
    #!/usr/bin/env bash
    set -euo pipefail

    command -v brew >/dev/null 2>&1 || {
        echo "Homebrew is required: https://brew.sh" >&2
        exit 1
    }

    ./gradlew :cli:distTar

    VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
    TARBALL="$(pwd)/cli/build/distributions/zeta-${VERSION}.tar.gz"
    [ -f "$TARBALL" ] || {
        echo "Tarball not found: $TARBALL" >&2
        exit 1
    }

    SHA=$(shasum -a 256 "$TARBALL" | awk '{print $1}')

    # Modern Homebrew rejects loose .rb files — formulas must live in a tap.
    # We materialise a local-only tap at $TAP_DIR on first run and reuse it.
    TAP="local/zeta"
    TAP_DIR="$(brew --prefix)/Library/Taps/local/homebrew-zeta"
    mkdir -p "$TAP_DIR/Formula"
    [ -d "$TAP_DIR/.git" ] || git -C "$TAP_DIR" init -q

    # Rewrite url + sha256 in our generated formula (preserve original indentation).
    sed -E \
        -e "s|^( *)url .*|\\1url \"file://${TARBALL}\"|" \
        -e "s|^( *)sha256 .*|\\1sha256 \"${SHA}\"|" \
        Formula/zeta.rb > "$TAP_DIR/Formula/zeta.rb"

    # Replace any prior install (from this recipe or a real tap).
    brew uninstall --ignore-dependencies zeta >/dev/null 2>&1 || true
    brew install --build-from-source "${TAP}/zeta"

    echo
    echo "Installed: $(brew --prefix)/bin/zeta"
    "$(brew --prefix)/bin/zeta" version

generate-connector:
    #!/usr/bin/env bash
    java -jar ~/Development/gematik/wsdl2openapi/generator-kotlin/app/build/libs/wsdl2openapi2kotlin.jar \
        --file connector/Konnektor-opb6.json \
        --output connector/src/main/kotlin \
        --naming connector/naming-strategy.json
