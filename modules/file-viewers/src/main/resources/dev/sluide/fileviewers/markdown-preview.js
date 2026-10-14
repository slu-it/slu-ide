// Behaviour of the Markdown preview (MarkdownRenderer, MarkdownFileView). Kotlin calls window.sluide; link clicks go
// to window.sluideBridge, which MarkdownFileView sets once the page has loaded.
(function () {
    function top(element) {
        return element.getBoundingClientRect().top + window.scrollY;
    }

    window.sluide = {
        // Scrolls so that source line `line` (0-based) is at the top, interpolating between the blocks around it.
        scrollToLine: function (line) {
            var blocks = document.querySelectorAll('[data-line]');
            var before = null;
            var after = null;
            for (var i = 0; i < blocks.length; i++) {
                var blockLine = Number(blocks[i].getAttribute('data-line'));
                if (blockLine <= line) {
                    before = { element: blocks[i], line: blockLine };
                } else {
                    after = { element: blocks[i], line: blockLine };
                    break;
                }
            }
            var y = 0;
            if (before) {
                y = top(before.element);
                if (after) y += (top(after.element) - y) * (line - before.line) / (after.line - before.line);
            }
            window.scrollTo(0, line === 0 ? 0 : y);
        },
        setDark: function (dark) {
            document.body.classList.toggle('dark', dark);
        }
    };

    document.addEventListener('click', function (event) {
        var link = event.target.closest ? event.target.closest('a[href]') : null;
        if (!link) return;
        var href = link.getAttribute('href');
        // Anchors on this page scroll as usual; every other link is opened by SluIde, never inside the preview.
        if (href.charAt(0) === '#') return;
        event.preventDefault();
        if (window.sluideBridge) window.sluideBridge.open(href);
    });
})();
