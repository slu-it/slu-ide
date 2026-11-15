package dev.sluide.languageservers

/** Which folders a spec's server gets as workspace folders ([LanguageServerSpec.workspaceFolderMode]). */
enum class WorkspaceFolderMode {
    /**
     * The window folder first, in `initialize`, then each folder [LanguageServerSpec.workspaceFolder] names (ADR 0151).
     * A file without a folder of its own is served by the window folder.
     */
    WINDOW_FIRST,

    /**
     * Only the folders [LanguageServerSpec.workspaceFolder] names (ADR 0179): `initialize` gets none and no root URI,
     * and each folder reaches the server in a `workspace/didChangeWorkspaceFolders` of its own, in the order they
     * became known, after every start again. A file without a folder is opened without one (loose). For servers that
     * import only the first folder of `initialize` (jdtls).
     */
    ROOTS_ONLY,
}
