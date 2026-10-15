// Behaviour of the preview pages (MarkdownRenderer, CsvRenderer, NotebookRenderer, ImagePage) in PreviewFileView.
// Kotlin calls window.sluide; link clicks go to window.sluideBridge, which PreviewFileView sets once the page has
// loaded.
(function () {
    var diagramsDrawn = Promise.resolve();

    function top(element) {
        return element.getBoundingClientRect().top + window.scrollY;
    }

    window.sluide = {
        // Scrolls so that source line `line` (0-based) is at the top, interpolating between the blocks around it.
        scrollToLine: function (line) {
            // The nearest blocks before and after the line, wherever they are on the page (a sorted table reorders them).
            var blocks = document.querySelectorAll('[data-line]');
            var before = null;
            var after = null;
            for (var i = 0; i < blocks.length; i++) {
                var blockLine = Number(blocks[i].getAttribute('data-line'));
                if (blockLine <= line) {
                    if (!before || blockLine > before.line) before = { element: blocks[i], line: blockLine };
                } else if (!after || blockLine < after.line) {
                    after = { element: blocks[i], line: blockLine };
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
            var changed = document.body.classList.contains('dark') !== dark;
            document.body.classList.toggle('dark', dark);
            if (changed && window.mermaid) window.sluide.renderDiagrams();
        },
        // Draws the diagrams (```mermaid fences, Mermaid files) with Mermaid, which the page includes when it has
        // diagrams, in the page's colour scheme; again after a change of scheme, from the source in data-source.
        // A diagram marked data-errors that does not parse shows Mermaid's message, as text, instead.
        renderDiagrams: function () {
            // One drawing at a time: a theme change while drawing waits for the drawing to end.
            diagramsDrawn = diagramsDrawn.then(function () {
                var dark = document.body.classList.contains('dark');
                mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'default' });
                var diagrams = Array.prototype.slice.call(document.querySelectorAll('pre.mermaid'));
                var checks = diagrams.map(function (diagram) {
                    var source = diagram.getAttribute('data-source');
                    diagram.removeAttribute('data-processed');
                    diagram.classList.remove('error');
                    diagram.textContent = source;
                    if (!diagram.hasAttribute('data-errors')) return Promise.resolve(true);
                    // An empty file is no error yet.
                    if (!source.trim()) return Promise.resolve(false);
                    return mermaid.parse(source).then(function () {
                        return true;
                    }, function (error) {
                        diagram.classList.add('error');
                        diagram.textContent = String((error && error.message) || error);
                        return false;
                    });
                });
                return Promise.all(checks).then(function (parsed) {
                    var nodes = diagrams.filter(function (diagram, i) { return parsed[i]; });
                    return nodes.length ? mermaid.run({ nodes: nodes, suppressErrors: true }) : undefined;
                });
            });
            return diagramsDrawn;
        },
        // Typesets the math (MarkdownMath) with KaTeX, which the page includes when it has math.
        renderMath: function () {
            var elements = document.querySelectorAll('.math');
            for (var i = 0; i < elements.length; i++) {
                var element = elements[i];
                katex.render(element.textContent, element, {
                    displayMode: element.classList.contains('display'),
                    throwOnError: false,
                    trust: false
                });
            }
        }
    };

    document.addEventListener('click', function (event) {
        var link = event.target.closest ? event.target.closest('a[href]') : null;
        if (!link) return;
        var href = link.getAttribute('href');
        // No link navigates the preview: anchors scroll within the page, every other link is opened by SluIde.
        event.preventDefault();
        if (href.charAt(0) === '#') {
            var target = document.getElementById(decodeURIComponent(href.substring(1)));
            if (target) target.scrollIntoView();
        } else if (window.sluideBridge) {
            window.sluideBridge.open(href);
        }
    });
})();
