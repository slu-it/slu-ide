package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.ui.CaretPopup
import dev.sluide.codeintelligence.ui.DocBlock
import dev.sluide.codeintelligence.ui.PopupCell
import dev.sluide.codeintelligence.ui.docBlocks
import dev.sluide.codeintelligence.ui.fxDelay
import dev.sluide.codeintelligence.ui.scrollingMarkup
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import org.eclipse.lsp4j.CompletionItem
import org.kordamp.ikonli.javafx.FontIcon
import java.util.IdentityHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Completion in a bound editor (ADR 0147): [CompletionModel] fed by the editor's keys, text changes and caret, shown in
 * a [CaretPopup] with an icon per kind and the selected item's detail and documentation beside the list. Ctrl+Space
 * (a literal Ctrl on every platform) asks at the caret. FX thread.
 *
 * @param debounce how long typing in a word waits before asking.
 */
internal class CompletionFeature(
    private val debounce: Duration = 100.milliseconds,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable = ::fxDelay,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val editor = binding.editor
        lateinit var model: CompletionModel
        val view = CompletionPopupView(editor, { model })
        model = CompletionModel(binding, view, debounce, schedule)
        val caret = editor.caretOffsetProperty.subscribe { _, _ -> model.caretMoved() }
        val handles =
            listOf(
                view,
                AutoCloseable { model.close() },
                model.keys.attach(editor),
                editor.onTextChange(model::textChanged),
                AutoCloseable { caret.unsubscribe() },
                editor.addKeyBinding(KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN)) { model.invoke() },
                editor.addKeyBinding(KeyCodeCombination(KeyCode.ESCAPE)) {
                    model.escape()
                    false
                },
            )
        return AutoCloseable { handles.asReversed().forEach { it.close() } }
    }
}

/** The completion popup: a [CaretPopup] of completion items; the focus stays in the editor. */
private class CompletionPopupView(
    private val editor: ExtensibleCodeEditor,
    model: () -> CompletionModel,
) : CompletionView,
    AutoCloseable {
    private val details = IdentityHashMap<CompletionItem, Node?>()

    // Created on first use: the binding may live in a test without a toolkit.
    private val popup by lazy(LazyThreadSafetyMode.NONE) {
        CaretPopup<CompletionItem>(
            editor = editor,
            cell = ::completionCell,
            onChoose = { item -> model().accept(item, replace = false) },
            detail = { item -> detailNode(model().detailOf(item)) },
            onTab = { item -> model().accept(item, replace = true) },
        ).apply {
            placeholder = "Searching…"
            onHidden = { model().viewHidden() }
        }
    }
    private var created = false

    override fun show(
        wordStart: Int,
        items: List<CompletionItem>,
    ): Boolean {
        if (editor.popupOwner?.isFocused != true) return false
        created = true
        popup.setItems(items)
        return popup.isShowing || popup.show(wordStart)
    }

    override fun hide() {
        if (created) popup.hide()
        details.clear()
    }

    override fun refreshDetail() {
        if (created) popup.setItems(popup.model.items)
    }

    override val isShowing: Boolean get() = created && popup.isShowing

    override fun close() {
        if (created) popup.close()
    }

    /** The detail beside the list: the item's detail as code, then its documentation; `null` for neither. */
    private fun detailNode(item: CompletionItem): Node? =
        details.getOrPut(item) {
            val blocks =
                listOfNotNull(item.detail?.takeIf { it.isNotBlank() }?.let { DocBlock.Code(it) }) +
                    docBlocks(item.documentation)
            if (blocks.isEmpty()) null else scrollingMarkup(blocks, maxHeight = DETAIL_HEIGHT, widthCap = DETAIL_WIDTH)
        }

    private companion object {
        const val DETAIL_HEIGHT = 300.0
        const val DETAIL_WIDTH = 344.0
    }
}

/**
 * How an item shows in the list: label with its label details, the description or detail (shortened) or kind at the
 * right, a kind icon.
 */
internal fun completionCell(item: CompletionItem): PopupCell {
    val look = kindLook(item.kind)
    return PopupCell(
        label = item.label.orEmpty() + item.labelDetails?.detail.orEmpty(),
        // ty repeats the detail (a whole signature) as the description.
        secondary = secondaryText(item.labelDetails?.description?.takeIf { it.isNotBlank() } ?: item.detail, item.kind),
        icon = look?.let { FontIcon(it.icon).apply { styleClass += it.group.styleClass } },
    )
}
