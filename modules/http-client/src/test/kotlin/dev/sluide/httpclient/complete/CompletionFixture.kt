package dev.sluide.httpclient.complete

import dev.sluide.httpclient.HttpCompletionItem
import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.env.Environments
import java.nio.file.Path
import kotlin.io.path.writeText

/** What the engine answered for a text with `|` marking the caret. */
internal class Answer(
    val text: String,
    val caret: Int,
    val anchor: Int,
    val items: List<HttpCompletionItem>,
) {
    /** The text the items replace. */
    val replaced get() = text.substring(anchor, caret)
    val labels get() = items.map { it.label }

    fun item(label: String) = items.single { it.label == label }
}

internal val NO_ENVIRONMENTS = Environments(emptyList(), emptyList())

/** The environments of [public] and [private] JSON written into [folder]. */
internal fun environments(
    folder: Path,
    public: String? = null,
    private: String? = null,
): Environments {
    val publicFile = public?.let { folder.resolve(EnvironmentFiles.PUBLIC).also { file -> file.writeText(it) } }
    val privateFile = private?.let { folder.resolve(EnvironmentFiles.PRIVATE).also { file -> file.writeText(it) } }
    return Environments.load(EnvironmentFiles(publicFile, privateFile))
}

internal fun answer(
    marked: String,
    environments: Environments = NO_ENVIRONMENTS,
    osEnv: Set<String> = emptySet(),
): Answer {
    val caret = marked.indexOf('|')
    val text = marked.removeRange(caret, caret + 1)
    val result = CompletionEngine { osEnv }.complete(text, caret) { environments }
    return Answer(text, caret, result.anchor, result.items)
}
