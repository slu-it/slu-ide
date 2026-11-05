package dev.sluide.translation.opusmt

import dev.sluide.localmodels.ModelFile
import dev.sluide.localmodels.ModelSpecs
import dev.sluide.translation.DownloadSizes
import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.Route
import dev.sluide.translation.engine.TranslationEngine
import dev.sluide.translation.engine.TranslationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val IDLE_UNLOAD = 5.minutes
private const val SHUTTING_DOWN = "The translation engine is shutting down."

/** One loaded pair model: counts the input tokens of a text (with `</s>`) and translates it. Not thread-safe. */
internal interface PairTranslator : AutoCloseable {
    fun tokens(text: String): Int

    fun translate(
        text: String,
        cancelled: () -> Boolean,
    ): String
}

/** What loading one pair model needs: its files, its name for messages, the target-language token it needs, if any. */
internal data class PairSetup(
    val files: MarianFiles,
    val label: String,
    val targetToken: String?,
)

/**
 * Opus-MT on ONNX Runtime (ADR 0126): the languages and routes of the pinned catalog, the [ModelSpecs] of its pair
 * models in `<root>/<pair>/` (by default `~/.sluide/models/opus-mt`), what downloading them still costs, and the
 * translation itself.
 *
 * The models are shared app-wide by all windows ([PairSessions]): loaded on first use, at most two at once (the least
 * recently used one is unloaded for a third), unloaded five minutes after their last use and in [close]. A sentence
 * longer than a model takes is translated in parts ([LongSentences]).
 *
 * Thread-safe. [translate] blocks and calls native code: call it on a platform thread, never the FX thread or a virtual
 * thread (which the native calls would pin).
 */
class OpusMtEngine internal constructor(
    val root: Path,
    private val catalog: OpusMtCatalog,
    idle: Duration,
    private val open: (PairSetup) -> PairTranslator,
) : TranslationEngine,
    ModelSpecs<PairModel>,
    DownloadSizes,
    AutoCloseable {
    constructor(root: Path) : this(root, OpusMtCatalog.load(), IDLE_UNLOAD, ::MarianTranslator)

    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "models", "opus-mt"))

    private val sessions =
        PairSessions(idle) { model ->
            open(PairSetup(marianFiles(model), label(model), OpusMtLanguages.targetToken(model.pair)))
        }

    @Volatile private var closed = false

    override val sources: List<Language> = OpusMtLanguages.sources(catalog)

    override val targets: List<Language> = OpusMtLanguages.targets(catalog)

    override fun route(
        source: Language,
        target: Language,
    ): Route? {
        if (source.code == target.code) return null
        val direct = "${source.code}-${target.code}"
        if (direct in catalog) return Route.Direct(direct)
        if (source.code == OpusMtLanguages.ENGLISH || target.code == OpusMtLanguages.ENGLISH) return null
        val first = "${source.code}-${OpusMtLanguages.ENGLISH}"
        val second = "${OpusMtLanguages.ENGLISH}-${target.code}"
        return if (first in catalog && second in catalog) Route.ViaEnglish(first, second) else null
    }

    override fun missing(
        route: Route,
        quality: Quality,
    ): List<PairModel> = route.pairs.map { model(it, quality) }.filterNot(::installed)

    override fun translate(
        sentence: String,
        route: Route,
        quality: Quality,
        cancelled: () -> Boolean,
    ): String {
        if (sentence.isBlank()) return sentence
        if (closed) throw TranslationException(SHUTTING_DOWN)
        missing(route, quality).firstOrNull()?.let {
            throw TranslationException("The translation model ${label(it)} is not installed.")
        }
        var text = sentence
        for (pair in route.pairs) text = translate(text, model(pair, quality), cancelled)
        return text
    }

    private fun translate(
        text: String,
        model: PairModel,
        cancelled: () -> Boolean,
    ): String {
        if (cancelled()) throw CancellationException()
        // Shutdown stops the decoder too, so that close() waits for one decoder step at most.
        val stopped = { closed || cancelled() }
        try {
            return sessions.use(model) { translator ->
                val parts = LongSentences.parts(text, translator::tokens)
                val translated =
                    parts.map { part ->
                        if (stopped()) throw CancellationException()
                        translator.translate(part, stopped)
                    }
                LongSentences.join(translated, model.target)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // close() closed the sessions between the check in translate and this use.
            if (closed) throw TranslationException(SHUTTING_DOWN, e)
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Interrupted").apply { initCause(e) }
        } catch (e: LinkageError) {
            throw TranslationException("ONNX Runtime cannot be loaded on this system: ${e.message}", e)
        }
    }

    /** The shared files and the quality's encoder and decoder. */
    override fun files(key: PairModel): List<ModelFile> {
        val files = catalog.pairs[key.pair] ?: throw IllegalArgumentException("No Opus-MT model ${key.pair}")
        return files.shared + files.of(key.quality)
    }

    override fun folder(key: PairModel): Path = root.resolve(key.pair)

    /** Whether every file of [key] is there with its size; the checksum was verified when it was downloaded. */
    override fun installed(key: PairModel): Boolean = key.pair in catalog && files(key).all { present(key, it) }

    override fun taskName(
        key: PairModel,
        percent: Int,
    ) = "Downloading translation model ${label(key)} $percent %"

    /** The bytes still to fetch for [models]; the shared files of a pair count once, also when both qualities are listed. */
    override fun bytes(models: List<PairModel>): Long {
        val counted = HashSet<Path>()
        var total = 0L
        for (model in models) {
            if (model.pair !in catalog) continue
            for (file in files(model)) {
                if (counted.add(folder(model).resolve(file.path)) && !present(model, file)) total += file.bytes
            }
        }
        return total
    }

    /**
     * What a delete of [model] keeps (`ModelInstaller.delete(key, keep)`): the shared files while the pair's other
     * quality is installed, or while a download runs, which may rely on them.
     */
    fun keptOnDelete(
        model: PairModel,
        downloadsRunning: Boolean,
    ): Set<String> {
        val other = model.copy(quality = if (model.quality == Quality.MEDIUM) Quality.HIGH else Quality.MEDIUM)
        val shared = catalog.pairs[model.pair]?.shared ?: return emptySet()
        return if (downloadsRunning || installed(other)) shared.map { it.path }.toSet() else emptySet()
    }

    /** Unloads every model (after the translations that run) and refuses later translations. */
    override fun close() {
        closed = true
        sessions.close()
    }

    private fun present(
        model: PairModel,
        file: ModelFile,
    ): Boolean {
        val path = folder(model).resolve(file.path)
        return Files.isRegularFile(path) && Files.size(path) == file.bytes
    }

    private fun model(
        pair: String,
        quality: Quality,
    ): PairModel {
        val (source, target) = pair.split('-', limit = 2)
        return PairModel(source, target, quality)
    }

    private fun marianFiles(model: PairModel): MarianFiles {
        val folder = folder(model)
        val files = files(model)

        fun named(name: String): Path {
            val file =
                files.firstOrNull { it.path == name || it.path.endsWith("/$name") }
                    ?: throw TranslationException("The catalog has no $name for ${label(model)}")
            return folder.resolve(file.path)
        }
        return MarianFiles(
            tokenizer = named("tokenizer.json"),
            config = named("config.json"),
            generationConfig = named("generation_config.json"),
            encoder = named("encoder.onnx"),
            decoder = named("decoder.onnx"),
        )
    }

    private fun label(model: PairModel): String {
        val quality =
            model.quality.name
                .lowercase()
                .replaceFirstChar(Char::uppercase)
        return "${OpusMtLanguages.name(model.source)} → ${OpusMtLanguages.name(model.target)} ($quality)"
    }
}

/**
 * The encoder input of a text for one model: the target-language token first when the model needs one (looked up as
 * one piece of the vocabulary, not tokenized as text), then the text's ids with `</s>`.
 */
internal class SourceEncoder(
    private val tokenizer: UnigramTokenizer,
    targetToken: String?,
    label: String,
) {
    private val prefix: Int? =
        targetToken?.let {
            tokenizer.pieceId(it)
                ?: throw TranslationException("The translation model $label has no target-language token $it.")
        }

    fun encode(text: String): IntArray {
        val ids = tokenizer.encode(text)
        return prefix?.let { intArrayOf(it) + ids } ?: ids
    }
}

/** A [MarianSession] as a [PairTranslator]: text in, text out. */
internal class MarianTranslator(
    setup: PairSetup,
) : PairTranslator {
    private val session = MarianSession(setup.files, setup.label)
    private val encoder =
        try {
            SourceEncoder(session.tokenizer, setup.targetToken, setup.label)
        } catch (e: Throwable) {
            session.close()
            throw e
        }

    override fun tokens(text: String): Int = encoder.encode(text).size

    override fun translate(
        text: String,
        cancelled: () -> Boolean,
    ): String = session.tokenizer.decode(session.translate(encoder.encode(text), cancelled))

    override fun close() = session.close()
}
