package dev.sluide.translation.settings

import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.ModelInstaller
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.opusmt.OpusMtEngine
import dev.sluide.translation.ui.formatMegabytes
import dev.sluide.translation.ui.qualityName
import java.nio.file.Path

/** The questions the page asks; tests replace them (ADR 0084). */
internal interface TranslationSettingsDialogs {
    /** Asks [question] with a Delete button and Cancel, which is the default; true for Delete. */
    fun confirmDelete(question: String): Boolean
}

/** One installed model on the page: its [label], and the [message] of a failed delete, if the last one failed. */
internal data class ModelRow(
    val model: PairModel,
    val label: String,
    val message: String?,
)

/** What the Translation page lists: the installed pair models, their total size, and the delete with its question. */
internal class TranslationSettingsPageModel(
    private val engine: OpusMtEngine,
    private val installer: ModelInstaller<PairModel>,
    private val dialogs: TranslationSettingsDialogs,
) {
    private val order =
        compareBy<PairModel>({ engine.languageName(it.source) }, { engine.languageName(it.target) }, { it.quality })

    /** The installed models; cheap (size checks), so a refresh reads it once and passes it on. */
    fun installedModels(): List<PairModel> = engine.models.filter(engine::installed)

    /**
     * The [installed] models, and those whose last delete failed (partly deleted, so no longer installed; their row
     * shows the message and Delete to retry), by source name, target name, then Medium before High.
     */
    fun rows(installed: List<PairModel> = installedModels()): List<ModelRow> =
        engine.models
            .filter { it in installed || failure(it) != null }
            .sortedWith(order)
            .map { ModelRow(it, rowLabel(it), failure(it)) }

    private fun failure(model: PairModel) = (installer.state(model).get() as? InstallState.Failed)?.message

    /** The bytes of the distinct files of the [installed] models on disk; a pair's shared files count once. */
    fun totalBytes(installed: List<PairModel> = installedModels()): Long {
        val counted = HashSet<Path>()
        var total = 0L
        for (model in installed) {
            for (file in engine.files(model)) {
                if (counted.add(engine.folder(model).resolve(file.path))) total += file.bytes
            }
        }
        return total
    }

    /** Deletes [model] after the question; nothing when it is answered with Cancel. */
    fun delete(model: PairModel) {
        if (!dialogs.confirmDelete(deleteQuestion(model))) return
        installer.delete(model) { downloading -> engine.keptOnDelete(model, downloading) }
    }

    private fun name(model: PairModel) = "${engine.languageName(model.source)} → ${engine.languageName(model.target)}"

    private fun bytes(model: PairModel) = engine.files(model).sumOf { it.bytes }

    fun rowLabel(model: PairModel) = "${name(model)} · ${qualityName(model.quality)} · ${formatMegabytes(bytes(model))}"

    fun deleteQuestion(model: PairModel) =
        "Delete the ${name(model)} (${qualityName(model.quality)}) translation model? It must be downloaded again " +
            "(${formatMegabytes(bytes(model))}) to translate with it."

    companion object {
        const val EMPTY =
            "No translation models downloaded. The Translate tab downloads them when a language pair needs them."
        const val ATTRIBUTION_MODELS =
            "Translation models: OPUS-MT, Language Technology Research Group, University of Helsinki (CC-BY 4.0), " +
                "converted to ONNX by Xenova."
        const val ATTRIBUTION_DETECTION = "Language detection: Lingua by Peter M. Stahl (Apache 2.0)."

        fun totalLabel(bytes: Long) = "Total: ${formatMegabytes(bytes)}"
    }
}
