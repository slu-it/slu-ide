package dev.sluide.languageservers.connection

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CodeActionCapabilities
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionKindCapabilities
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities
import org.eclipse.lsp4j.CodeActionResolveSupportCapabilities
import org.eclipse.lsp4j.CompletionCapabilities
import org.eclipse.lsp4j.CompletionItemCapabilities
import org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities
import org.eclipse.lsp4j.DeclarationCapabilities
import org.eclipse.lsp4j.DefinitionCapabilities
import org.eclipse.lsp4j.DiagnosticCapabilities
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.DiagnosticWorkspaceCapabilities
import org.eclipse.lsp4j.DiagnosticsTagSupport
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities
import org.eclipse.lsp4j.DocumentHighlightCapabilities
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.HoverCapabilities
import org.eclipse.lsp4j.InlayHintWorkspaceCapabilities
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.ParameterInformationCapabilities
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities
import org.eclipse.lsp4j.ReferencesCapabilities
import org.eclipse.lsp4j.RenameCapabilities
import org.eclipse.lsp4j.SemanticTokensCapabilities
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests
import org.eclipse.lsp4j.SemanticTokensWorkspaceCapabilities
import org.eclipse.lsp4j.SignatureHelpCapabilities
import org.eclipse.lsp4j.SignatureInformationCapabilities
import org.eclipse.lsp4j.SymbolCapabilities
import org.eclipse.lsp4j.SynchronizationCapabilities
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TypeDefinitionCapabilities
import org.eclipse.lsp4j.WindowClientCapabilities
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceEditCapabilities

/** The standard semantic token types of LSP 3.17, all of which Thoreau accepts (P8 colours a subset). */
internal val SEMANTIC_TOKEN_TYPES =
    listOf(
        "namespace",
        "type",
        "class",
        "enum",
        "interface",
        "struct",
        "typeParameter",
        "parameter",
        "variable",
        "property",
        "enumMember",
        "event",
        "function",
        "method",
        "macro",
        "keyword",
        "modifier",
        "comment",
        "string",
        "number",
        "regexp",
        "operator",
        "decorator",
    )

/** The standard semantic token modifiers of LSP 3.17. */
internal val SEMANTIC_TOKEN_MODIFIERS =
    listOf(
        "declaration",
        "definition",
        "readonly",
        "static",
        "deprecated",
        "abstract",
        "async",
        "modification",
        "documentation",
        "defaultLibrary",
    )

private val DOC_FORMATS = listOf(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT)

/**
 * What Thoreau declares at `initialize`: everything the round's features use (ADR 0142). Positions are UTF-16, as the
 * editor's offsets; snippets are off (completion inserts plain text); file watchers and pull diagnostics may be
 * registered dynamically.
 */
internal fun clientCapabilities(): ClientCapabilities =
    ClientCapabilities().apply {
        general = GeneralClientCapabilities().apply { positionEncodings = listOf("utf-16") }
        window = WindowClientCapabilities().apply { workDoneProgress = true }
        workspace = workspaceCapabilities()
        textDocument = textDocumentCapabilities()
    }

private fun workspaceCapabilities() =
    WorkspaceClientCapabilities().apply {
        configuration = true
        workspaceFolders = true
        applyEdit = false
        workspaceEdit = WorkspaceEditCapabilities().apply { documentChanges = true }
        didChangeWatchedFiles = DidChangeWatchedFilesCapabilities(true).apply { relativePatternSupport = true }
        symbol = SymbolCapabilities(false)
        diagnostics = DiagnosticWorkspaceCapabilities(true)
        semanticTokens = SemanticTokensWorkspaceCapabilities(true)
        inlayHint = InlayHintWorkspaceCapabilities(true)
    }

private fun textDocumentCapabilities() =
    TextDocumentClientCapabilities().apply {
        synchronization = SynchronizationCapabilities(false, false, true, false)
        definition = DefinitionCapabilities(false, true)
        declaration = DeclarationCapabilities(false, true)
        typeDefinition = TypeDefinitionCapabilities(false, true)
        references = ReferencesCapabilities(false)
        documentHighlight = DocumentHighlightCapabilities(false)
        hover = HoverCapabilities(DOC_FORMATS, false)
        completion = completionCapabilities()
        signatureHelp =
            SignatureHelpCapabilities(
                SignatureInformationCapabilities(DOC_FORMATS).apply {
                    parameterInformation = ParameterInformationCapabilities(true)
                    activeParameterSupport = true
                },
                false,
            )
        publishDiagnostics =
            PublishDiagnosticsCapabilities(true, diagnosticTags(), true).apply {
                codeDescriptionSupport = true
                dataSupport = true
            }
        diagnostic = DiagnosticCapabilities(true, false)
        codeAction = codeActionCapabilities()
        rename = RenameCapabilities(true, false)
        documentSymbol = DocumentSymbolCapabilities().apply { hierarchicalDocumentSymbolSupport = true }
        semanticTokens = semanticTokensCapabilities()
    }

private fun completionCapabilities() =
    CompletionCapabilities(
        CompletionItemCapabilities(false).apply {
            documentationFormat = DOC_FORMATS
            deprecatedSupport = true
            labelDetailsSupport = true
            resolveSupport =
                CompletionItemResolveSupportCapabilities(listOf("documentation", "detail", "additionalTextEdits"))
        },
    ).apply { contextSupport = true }

private fun codeActionCapabilities() =
    CodeActionCapabilities(
        CodeActionLiteralSupportCapabilities(
            CodeActionKindCapabilities(
                listOf(
                    CodeActionKind.Empty,
                    CodeActionKind.QuickFix,
                    CodeActionKind.Refactor,
                    CodeActionKind.RefactorExtract,
                    CodeActionKind.RefactorInline,
                    CodeActionKind.RefactorRewrite,
                    CodeActionKind.Source,
                    CodeActionKind.SourceOrganizeImports,
                ),
            ),
        ),
        false,
    ).apply {
        isPreferredSupport = true
        dataSupport = true
        resolveSupport = CodeActionResolveSupportCapabilities(listOf("edit"))
    }

private fun semanticTokensCapabilities() =
    SemanticTokensCapabilities(
        false,
        SemanticTokensClientCapabilitiesRequests().apply {
            setFull(true)
            setRange(false)
        },
        SEMANTIC_TOKEN_TYPES,
        SEMANTIC_TOKEN_MODIFIERS,
        listOf("relative"),
    ).apply {
        overlappingTokenSupport = false
        multilineTokenSupport = false
    }

private fun diagnosticTags() = DiagnosticsTagSupport(listOf(DiagnosticTag.Unnecessary, DiagnosticTag.Deprecated))
