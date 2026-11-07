package dev.sluide.languageservers

/** What a server asks the client to pull again (`workspace/…/refresh`): features re-request it for open files. */
enum class RefreshKind {
    /** `workspace/diagnostic/refresh`. */
    DIAGNOSTICS,

    /** `workspace/semanticTokens/refresh`. */
    SEMANTIC_TOKENS,

    /** `workspace/inlayHint/refresh`. */
    INLAY_HINTS,
}
