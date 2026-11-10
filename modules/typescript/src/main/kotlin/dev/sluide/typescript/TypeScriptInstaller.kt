package dev.sluide.typescript

import dev.sluide.localmodels.ToolInstaller
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ServerToolInstaller
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * The TypeScript 7 language server for TypeScript and JavaScript code intelligence (ADR 0159): Thoreau's download
 * under `~/.sluide/tools/typescript` (the pinned version on install, a newer one on update, the previous one kept for
 * rollback), or the executable the user set in [TypeScriptSettings], which wins while it is set; its command is
 * `tsc --lsp --stdio`.
 */
@Component
class TypeScriptInstaller internal constructor(
    settings: TypeScriptSettings,
    tool: ServerTool,
    tools: ToolInstaller = ToolInstaller(tool.root, tool.smokeTest()::check, tool::taskName),
) : ServerToolInstaller(tool, settings, tools) {
    @Autowired
    constructor(settings: TypeScriptSettings) : this(settings, TypeScriptTool.tool())

    /** The install banner reads "Code intelligence for TypeScript needs the TypeScript language server (…)". */
    override val toolName: String get() = "the TypeScript language server"
}
