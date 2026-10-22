#!/usr/bin/env bash
# Rebuilds the vendored workflow parser bundle from package-lock.json (README.md).
set -euo pipefail
cd "$(dirname "$0")"

out=../../resources/dev/sluide/previews/vendor/workflow-parser
meta=node_modules/.workflow-parser-meta.json

# npm ci installs exactly the lockfile and verifies every tarball's integrity hash; no install scripts run.
npm ci --ignore-scripts --no-audit --no-fund

mkdir -p "$out"
# --platform=browser: a Node built-in (fs, path, ...) fails the build instead of being bundled or left as require().
./node_modules/.bin/esbuild entry.js \
    --bundle --format=iife --platform=browser --target=es2020 --minify \
    --legal-comments=eof --charset=utf8 --log-level=warning \
    --metafile="$meta" --outfile="$out/workflow-parser.min.js"

# Every bundled package needs its licence next to the bundle.
bundled=$(node -e '
    const meta = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
    const names = Object.keys(meta.inputs).map(i => /node_modules\/((?:@[^/]+\/)?[^/]+)\//.exec(i));
    console.log([...new Set(names.filter(Boolean).map(m => m[1]))].sort().join(" "));' "$meta")
for package in $bundled; do
    licence=${package#@}
    cp "node_modules/$package/LICENSE" "$out/LICENSE-${licence//\//-}"
done

echo "Bundled packages: $bundled"
echo "Size: $(wc -c < "$out/workflow-parser.min.js") bytes"
sha256sum "$out/workflow-parser.min.js" 2>/dev/null || shasum -a 256 "$out/workflow-parser.min.js"
