package dev.sluide.ebooks.view

/** How building a page for the view ended: the [page], or a [notice] for the user and the page not built. */
internal sealed interface Rendered {
    class Page(
        val page: ShownPage,
    ) : Rendered

    class Failed(
        val notice: String,
    ) : Rendered
}

/** Runs [build] (off the FX thread) and turns an exception or running out of memory into [Rendered.Failed]. */
internal fun renderSafely(build: () -> ShownPage): Rendered =
    try {
        Rendered.Page(build())
    } catch (e: RuntimeException) {
        Rendered.Failed("Could not show this chapter: ${e.message ?: e.javaClass.simpleName}")
    } catch (_: StackOverflowError) {
        Rendered.Failed("This chapter is too deeply nested to show.")
    } catch (_: OutOfMemoryError) {
        Rendered.Failed("This chapter is too large to show.")
    }
