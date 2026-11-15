package dev.sluide.codeintelligence

import dev.sluide.codeintelligence.binding.DocumentBinding

/** Where a jump that a window-wide popup makes later starts: the focused bound editor when captured (the jump starts from its caret at the time of the jump). */
class JumpStart internal constructor(
    internal val from: DocumentBinding?,
)
