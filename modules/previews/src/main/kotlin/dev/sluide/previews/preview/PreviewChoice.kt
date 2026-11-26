package dev.sluide.previews.preview

import javafx.beans.property.ObjectProperty

/**
 * A dropdown a [Preview] adds to its view's toolbar ([Preview.choices]), beside its [actions][PreviewAction] and only
 * while the preview shows: a button reading "[name]: the selected option" whose menu lists the [options] ([label] is
 * the text of each) as radio items, with [tooltip] on hover. Choosing an option sets [selected]; setting [selected]
 * from outside updates the button. The preview keeps [selected] for the life of its view. Everything on the FX thread.
 */
internal class PreviewChoice<T>(
    val name: String,
    val label: (T) -> String,
    val options: List<T>,
    val selected: ObjectProperty<T>,
    val tooltip: String,
)
