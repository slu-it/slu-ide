package dev.sluide.python

import dev.sluide.localmodels.ToolInstaller
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ServerToolInstaller
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * ty for Python code intelligence (ADR 0144, ADR 0159): Thoreau's download under `~/.sluide/tools/ty` (the pinned
 * version on install, a newer one on update, the previous one kept for rollback), or the executable the user set in
 * [PythonSettings], which wins while it is set; its command is `ty server`.
 */
@Component
class TyInstaller internal constructor(
    settings: PythonSettings,
    tool: ServerTool,
    tools: ToolInstaller = ToolInstaller(tool.root, tool.smokeTest()::check, tool::taskName),
) : ServerToolInstaller(tool, settings, tools) {
    @Autowired
    constructor(settings: PythonSettings) : this(settings, TyTool.tool())
}
