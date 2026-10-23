# Bundled libraries

Used by the previews (ADR 0017), loaded by `Vendor.kt`. Downloaded from the npm registry; the tarball's `integrity`
was checked before unpacking. Only the files listed are kept.

| Library | Version | Licence | Files | Tarball integrity |
|---------|---------|---------|-------|-------------------|
| [Mermaid](https://mermaid.js.org) | 11.17.2 | MIT (`mermaid/LICENSE`) | `dist/mermaid.min.js` | `sha512-V6K3C8EBdEsPFZXSKMJe6ppQOENxuHARr9GvHX4hh47lAbhMRD9qf4oEK7LoaRQxULMa80/qt5gHO73aCleBBg==` |
| [KaTeX](https://katex.org) | 0.18.9 | MIT (`katex/LICENSE`) | `dist/katex.min.js`, `dist/katex.min.css`, `dist/fonts/*.woff` (JavaFX's WebKit does not load woff2 from `data:` URIs) | `sha512-8ad9RyoKsb/g8/yLFE+KAlP+DhbCTRUNi/V9XGsxn0R+trJJltNwzcDNo0q/DEkOy5fQUQTAQyCXCYSE+OakTQ==` |

To update: download `https://registry.npmjs.org/<name>/-/<name>-<version>.tgz`, compare its sha512 (base64) with
`dist.integrity` from `https://registry.npmjs.org/<name>`, copy the files above, update this table.

## Workflow parser (built, not copied)

`workflow-parser/workflow-parser.min.js` is built, not copied: GitHub's Actions workflow parser and its dependencies
bundled by esbuild 0.28.2 with our entry, which returns our own JSON. Sources, build (`./build.sh`), version updates
and the JSON contract: `src/main/js/workflow-parser/README.md` in this module. `npm ci` checks each tarball against
the `integrity` hashes in that folder's `package-lock.json`.

Bundle: 278,041 bytes, sha256 `856267e8edeee6092358edbfea3e9ecf3b9a5bb8993a0fc261abb57c5404cf0f`.

| Library | Version | Licence |
|---------|---------|---------|
| [@actions/workflow-parser](https://github.com/actions/languageservices) | 0.3.61 | MIT (`workflow-parser/LICENSE-actions-workflow-parser`) |
| [@actions/expressions](https://github.com/actions/languageservices) | 0.3.61 | MIT (`workflow-parser/LICENSE-actions-expressions`) |
| [yaml](https://eemeli.org/yaml/) | 2.9.1 | ISC (`workflow-parser/LICENSE-yaml`) |
| [cronstrue](https://github.com/bradymholt/cRonstrue) | 2.59.0 | MIT (`workflow-parser/LICENSE-cronstrue`) |
