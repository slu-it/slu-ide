package dev.sluide.ebooks.view

import dev.sluide.previews.TextMarks
import java.net.URI
import java.net.URISyntaxException

/**
 * What the page's script calls (`sluideBookBridge`); public members only, it is reached by reflection, and the
 * engine keeps it weakly, so the view holds it.
 */
internal class PageBridge(
    private val linkClicked: (String) -> Unit,
    private val anchorFollowed: () -> Unit,
    private val anchorMissing: (String) -> Unit,
    private val textClicked: (Int) -> Unit,
) {
    fun openLink(href: String) = linkClicked(href)

    /** A link within the page scrolled to its target. */
    fun anchorJumped() = anchorFollowed()

    /** A link to [id], which this page does not have (it is in another chapter of a large book). */
    fun anchorNotHere(id: String) = anchorMissing(id)

    fun textClicked(offset: Int) = textClicked.invoke(offset)

    companion object {
        const val MEMBER = "sluideBookBridge"

        /**
         * The page's one script: the text marks and the click handling. Links to other pages never navigate (the
         * bridge opens them in the browser), links within the page scroll (by the script) and are reported, a
         * single click on text that is not a selection reports its offset.
         */
        val SCRIPT: String by lazy { TextMarks.SCRIPT + "\n" + BOOK }

        private const val BOOK = """
(function () {
  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    var a = e.target.closest('a[href]');
    // Whatever the link is, the engine never follows it.
    if (a) e.preventDefault();
    var bridge = window.sluideBookBridge;
    if (!bridge) return;
    if (a) {
      var href = a.getAttribute('href');
      if (href.charAt(0) === '#') {
        var id = href.substring(1);
        var target = document.getElementById(id);
        if (target) {
          target.scrollIntoView();
          setTimeout(function () { bridge.anchorJumped(); }, 50);
        } else {
          bridge.anchorNotHere(id);
        }
      } else {
        bridge.openLink(href);
      }
      return;
    }
    if (e.button !== 0 || e.detail > 1) return;
    var selection = window.getSelection();
    if (selection && !selection.isCollapsed) return;
    var offset = window.sluide && sluide.marks ? sluide.marks.offsetAt(e.clientX, e.clientY) : null;
    if (offset !== null && offset !== undefined) bridge.textClicked(offset);
  }, true);
})();
"""
    }
}

/** Whether [location] is the loaded page itself (`loadContent` and its `#anchor` jumps), not a navigation away. */
internal fun isOwnPage(location: String?): Boolean =
    location.isNullOrEmpty() || location == "about:blank" || location.startsWith("about:blank#")

/** The URI of [href] if it is a link for the system browser (http, https, mailto), else null. */
internal fun externalLink(href: String): URI? =
    try {
        URI(href.trim()).takeIf { it.scheme?.lowercase() in setOf("http", "https", "mailto") }
    } catch (_: URISyntaxException) {
        null
    }
