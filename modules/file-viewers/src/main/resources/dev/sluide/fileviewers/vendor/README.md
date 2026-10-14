# Bundled libraries

Used by the previews (ADR 0017), loaded by `Vendor.kt`. Downloaded from the npm registry; the tarball's `integrity`
was checked before unpacking. Only the files listed are kept.

| Library | Version | Licence | Files | Tarball integrity |
|---------|---------|---------|-------|-------------------|
| [KaTeX](https://katex.org) | 0.18.9 | MIT (`katex/LICENSE`) | `dist/katex.min.js`, `dist/katex.min.css`, `dist/fonts/*.woff` (JavaFX's WebKit does not load woff2 from `data:` URIs) | `sha512-8ad9RyoKsb/g8/yLFE+KAlP+DhbCTRUNi/V9XGsxn0R+trJJltNwzcDNo0q/DEkOy5fQUQTAQyCXCYSE+OakTQ==` |

To update: download `https://registry.npmjs.org/<name>/-/<name>-<version>.tgz`, compare its sha512 (base64) with
`dist.integrity` from `https://registry.npmjs.org/<name>`, copy the files above, update this table.
