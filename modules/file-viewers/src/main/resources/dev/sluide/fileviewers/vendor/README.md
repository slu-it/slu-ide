# Bundled libraries

Used by the previews (ADR 0017), loaded by `Vendor.kt`. Downloaded from the npm registry; the tarball's `integrity`
was checked before unpacking. Only the files listed are kept.

| Library | Version | Licence | Files | Tarball integrity |
|---------|---------|---------|-------|-------------------|
| [Mermaid](https://mermaid.js.org) | 11.17.2 | MIT (`mermaid/LICENSE`) | `dist/mermaid.min.js` | `sha512-V6K3C8EBdEsPFZXSKMJe6ppQOENxuHARr9GvHX4hh47lAbhMRD9qf4oEK7LoaRQxULMa80/qt5gHO73aCleBBg==` |
| [KaTeX](https://katex.org) | 0.18.9 | MIT (`katex/LICENSE`) | `dist/katex.min.js`, `dist/katex.min.css`, `dist/fonts/*.woff` (JavaFX's WebKit does not load woff2 from `data:` URIs) | `sha512-8ad9RyoKsb/g8/yLFE+KAlP+DhbCTRUNi/V9XGsxn0R+trJJltNwzcDNo0q/DEkOy5fQUQTAQyCXCYSE+OakTQ==` |

To update: download `https://registry.npmjs.org/<name>/-/<name>-<version>.tgz`, compare its sha512 (base64) with
`dist.integrity` from `https://registry.npmjs.org/<name>`, copy the files above, update this table.
