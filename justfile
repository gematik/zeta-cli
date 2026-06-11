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

    ./gradlew distTar

    VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
    TARBALL="$(pwd)/build/distributions/zeta-${VERSION}.tar.gz"
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

# build + upload the current release to <owner>/homebrew-tap and bump Formula/zeta.rb
# usage: just publish-brew <owner>     (e.g. `just publish-brew spilikin`)
publish-brew owner:
    #!/usr/bin/env bash
    set -euo pipefail

    OWNER="{{owner}}"
    [ -n "$OWNER" ] || { echo "owner is required: just publish-brew <owner>" >&2; exit 2; }

    command -v gh >/dev/null 2>&1 || {
        echo "gh CLI is required: https://cli.github.com" >&2
        exit 1
    }

    VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
    TAG="v${VERSION}"
    ASSET_NAME="zeta-${VERSION}.tar.gz"
    TAP_REPO="${OWNER}/homebrew-tap"
    REPO_ROOT="$PWD"

    ./gradlew distTar
    TARBALL="${REPO_ROOT}/build/distributions/${ASSET_NAME}"
    [ -f "$TARBALL" ] || { echo "Tarball not found: $TARBALL" >&2; exit 1; }

    SHA=$(shasum -a 256 "$TARBALL" | awk '{print $1}')
    SHA_FILE="${TARBALL}.sha256"
    echo "$SHA  ${ASSET_NAME}" > "$SHA_FILE"

    if gh release view "$TAG" --repo "$TAP_REPO" >/dev/null 2>&1; then
        echo "Release $TAG already exists on $TAP_REPO — replacing assets."
        gh release upload "$TAG" --repo "$TAP_REPO" --clobber "$TARBALL" "$SHA_FILE"
    else
        gh release create "$TAG" --repo "$TAP_REPO" \
            --title "zeta ${VERSION}" --notes "zeta ${VERSION}" --target main \
            "$TARBALL" "$SHA_FILE"
    fi

    DOWNLOAD_URL="https://github.com/${TAP_REPO}/releases/download/${TAG}/${ASSET_NAME}"

    WORKDIR=$(mktemp -d)
    trap 'rm -rf "$WORKDIR"' EXIT

    gh repo clone "$TAP_REPO" "$WORKDIR/tap" -- --depth 1
    cd "$WORKDIR/tap"
    mkdir -p Formula
    sed -E \
        -e "s|^( *)url .*|\\1url \"${DOWNLOAD_URL}\"|" \
        -e "s|^( *)sha256 .*|\\1sha256 \"${SHA}\"|" \
        "${REPO_ROOT}/Formula/zeta.rb" > Formula/zeta.rb

    git add Formula/zeta.rb
    if git diff --cached --quiet; then
        echo "Formula already at $VERSION on $TAP_REPO."
    else
        git commit -m "zeta ${VERSION}"
        git push
    fi

    cd "$REPO_ROOT"
    if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
        echo "Local tag ${TAG} already exists."
    else
        git tag "$TAG"
        echo "Created local tag ${TAG}."
    fi

    echo
    echo "Published zeta ${VERSION} to ${TAP_REPO}"
    echo "Install: brew tap ${OWNER}/tap && brew install zeta"

generate-connector:
    #!/usr/bin/env bash
    java -jar ~/Development/gematik/wsdl2openapi/generator-kotlin/app/build/libs/wsdl2openapi2kotlin.jar \
        --file connector/Konnektor-opb6.json \
        --output connector/src/main/kotlin \
        --naming connector/naming-strategy.json

# render the animated CLI demo (demo.tape -> demo.gif)
demo:
    #!/usr/bin/env bash
    set -euo pipefail

    command -v vhs >/dev/null 2>&1 || {
        echo "vhs is required: brew install vhs" >&2
        exit 1
    }

    ./gradlew :cli:installDist
    vhs demo.tape

    echo
    echo "Generated: $(pwd)/demo.gif"

# build README.pdf from README.md via pandoc + typst, with gematik logo header
readme-pdf:
    #!/usr/bin/env bash
    set -euo pipefail

    command -v pandoc >/dev/null 2>&1 || { echo "pandoc is required: brew install pandoc" >&2; exit 1; }
    command -v typst  >/dev/null 2>&1 || { echo "typst is required: brew install typst"   >&2; exit 1; }

    [ -f images/gematik-logo.png ] || { echo "images/gematik-logo.png missing" >&2; exit 1; }

    SRC=$(mktemp ./zeta-readme.XXXXXX.md)
    TYP=$(mktemp ./zeta-readme.XXXXXX.typ)
    trap 'rm -f "$SRC" "$TYP" "$TYP.bak"' EXIT

    {
        printf '![](images/gematik-logo.png){width=4cm}\n\n'
        cat README.md
    } > "$SRC"

    pandoc "$SRC" -o "$TYP" \
        --from=markdown+gfm_auto_identifiers-implicit_figures \
        --to=typst \
        --lua-filter=tools/pdf-strip-gif.lua \
        --include-in-header=tools/pdf-style.typ \
        -V geometry=margin=2.5cm

    # Force every table to span the full text column. Pandoc emits each
    # table as `figure(align(center)[#table(columns: (50%, 50%), …)])`
    # — fr-flex columns won't expand because `align(center)` provides no
    # width constraint, and the percent columns just split the table's
    # natural content width. Two rewrites fix both:
    #   1. Swap `align(center)[#table(` → `block(width: 100%)[#table(`
    #      so the table's parent has a concrete width.
    #   2. Convert column specs to `1fr` units so the columns share that
    #      width equally (covers both `columns: (N%, N%)` and bare
    #      `columns: N` forms emitted by pandoc).
    sed -i.bak -E \
        -e 's/align\(center\)\[#table\(/block(width: 100%)[#table(/g' \
        -e '/columns: \(/ s/[0-9]+(\.[0-9]+)?%/1fr/g' \
        -e 's/columns: 2,/columns: (1fr, 1fr),/g' \
        -e 's/columns: 3,/columns: (1fr, 1fr, 1fr),/g' \
        -e 's/columns: 4,/columns: (1fr, 1fr, 1fr, 1fr),/g' \
        "$TYP"

    typst compile "$TYP" README.pdf

    echo
    echo "Generated: $(pwd)/README.pdf"
