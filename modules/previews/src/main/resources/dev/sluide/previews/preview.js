// Behaviour of the preview pages (MarkdownRenderer, CsvRenderer, NotebookRenderer, ImagePage) in PreviewFileView and
// ImageFileView. Kotlin calls window.sluide; link clicks and zoom changes go to window.sluideBridge, which the view
// sets once the page has loaded.
(function () {
    var diagramsDrawn = Promise.resolve();

    function top(element) {
        return element.getBoundingClientRect().top + window.scrollY;
    }

    function isMermaidFile() {
        return document.body.classList.contains('mermaid-file');
    }

    // Zoom of a zoomable page (body class "zoomable": ImagePage, MermaidPreview). The image or diagram gets an explicit
    // size in px: its natural size times the scale. zoom is null while it fits the view, else the fixed scale; scale is
    // the one last applied. Every change is reported to sluideBridge.zoomed, so that the view's toolbar follows.
    var MIN_SCALE = 0.1;
    var MAX_SCALE = 16;
    var MARGIN = 16;
    var zoom = null;
    var scale = 1;

    function isZoomable() {
        return document.body.classList.contains('zoomable');
    }

    // What the zoom sizes: a Mermaid file's drawing, else the image.
    function zoomTarget() {
        if (!isZoomable()) return null;
        return document.querySelector(isMermaidFile() ? 'pre.mermaid svg' : 'img');
    }

    // The target's size at 100%, remembered in data-width and data-height before the zoom first sizes it.
    function naturalSize(target) {
        if (!target.hasAttribute('data-width')) {
            var width;
            var height;
            if (target.tagName.toLowerCase() === 'img') {
                width = target.naturalWidth;
                height = target.naturalHeight;
                if (!width || !height) {
                    // An SVG without a size of its own: as large as it is laid out without ours.
                    target.style.width = '';
                    target.style.height = '';
                    var laidOut = target.getBoundingClientRect();
                    width = laidOut.width;
                    height = laidOut.height;
                }
            } else {
                // Mermaid's drawing is as wide as the max-width Mermaid sets; its height follows its view box.
                var box = target.viewBox && target.viewBox.baseVal;
                var drawn = target.getBoundingClientRect();
                width = parseFloat(target.style.maxWidth) || (box && box.width) || drawn.width;
                height = box && box.width ? width * box.height / box.width : drawn.height;
            }
            if (!width || !height) return null;
            target.setAttribute('data-width', width);
            target.setAttribute('data-height', height);
        }
        return {
            width: parseFloat(target.getAttribute('data-width')),
            height: parseFloat(target.getAttribute('data-height'))
        };
    }

    // The scale at which the target is visible as a whole, never above 1; below MIN_SCALE for huge content.
    function fitScale(target, size) {
        var width;
        var height;
        if (isMermaidFile()) {
            // Measured without the drawing: the room the page leaves it, below the note above it.
            target.style.width = '0px';
            target.style.height = '0px';
            var pre = target.parentNode;
            var style = getComputedStyle(pre);
            width = pre.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
            height = window.innerHeight - document.body.getBoundingClientRect().height;
        } else {
            width = window.innerWidth - 2 * MARGIN;
            height = window.innerHeight - 2 * MARGIN;
        }
        // Whole pixels, so that no fraction overflows and shows a scrollbar; at least one (a tiny view).
        width = Math.max(1, Math.floor(width));
        height = Math.max(1, Math.floor(height));
        return Math.min(1, width / size.width, height / size.height);
    }

    function applyZoom() {
        // A view not laid out yet (e.g. a preview opened beside the source alone) has no room to fit into: the target
        // stays as it is until the view gets a size (resize).
        if (zoom === null && (window.innerWidth <= 0 || window.innerHeight <= 0)) return;
        var target = zoomTarget();
        var size = target && naturalSize(target);
        if (!size) return;
        scale = zoom === null ? fitScale(target, size) : zoom;
        target.style.maxWidth = 'none';
        target.style.width = size.width * scale + 'px';
        target.style.height = size.height * scale + 'px';
        document.body.classList.toggle('fitted', zoom === null);
        if (window.sluideBridge && window.sluideBridge.zoomed) window.sluideBridge.zoomed(scale, zoom === null);
    }

    // Sets the zoom to value (null: fit), keeping the point at (x, y) in the viewport where it is, if given. A fixed
    // value is trusted (the view clamps it, ZoomSteps.clamp), only kept from exceeding MAX_SCALE; one that is no size
    // is ignored.
    function setZoom(value, x, y) {
        if (value !== null && value !== undefined && !(value > 0)) return;
        var target = zoomTarget();
        var anchored = target && typeof x === 'number' && typeof y === 'number';
        var before = anchored ? target.getBoundingClientRect() : null;
        zoom = value === null || value === undefined ? null : Math.min(MAX_SCALE, value);
        applyZoom();
        if (!before || !before.width || !before.height) return;
        var after = target.getBoundingClientRect();
        var newX = after.left + (x - before.left) * after.width / before.width;
        var newY = after.top + (y - before.top) * after.height / before.height;
        window.scrollBy(newX - x, newY - y);
    }

    // Lays out the outermost of the given <details> anew, keeping the scroll position. JavaFX's WebKit keeps the open
    // height of a <details> closed while inside a closed one (as setAllOpen(false) closes them), which shows as a gap
    // below its line once its parent is opened. Closing innermost first does not avoid it (layout waits for the end
    // of the script); laying the tree out again from scratch drops that height.
    function relayout(details) {
        var outermost = [];
        for (var i = 0; i < details.length; i++) {
            if (!details[i].parentElement.closest('details')) outermost.push(details[i]);
        }
        var x = window.scrollX;
        var y = window.scrollY;
        for (var j = 0; j < outermost.length; j++) outermost[j].style.display = 'none';
        void document.body.offsetHeight;
        for (var k = 0; k < outermost.length; k++) outermost[k].style.display = '';
        window.scrollTo(x, y);
    }

    window.sluide = {
        // Scrolls so that source line `line` (0-based) is at the top, interpolating between the blocks around it.
        scrollToLine: function (line) {
            // The nearest blocks before and after the line, wherever they are on the page (a sorted table reorders them).
            var blocks = document.querySelectorAll('[data-line]');
            var before = null;
            var after = null;
            for (var i = 0; i < blocks.length; i++) {
                // Not rendered (e.g. in a closed <details>): its position is meaningless.
                if (blocks[i].getClientRects().length === 0) continue;
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
        // The source line (0-based) at the top of the viewport, the inverse of scrollToLine: that of the last block
        // that starts at or above it, or 0 above the first.
        topLine: function () {
            var blocks = document.querySelectorAll('[data-line]');
            var best = null;
            var limit = window.scrollY + 1;
            for (var i = 0; i < blocks.length; i++) {
                if (blocks[i].getClientRects().length === 0) continue;
                var blockLine = Number(blocks[i].getAttribute('data-line'));
                var y = top(blocks[i]);
                if (y > limit) continue;
                if (!best || y > best.y || (y === best.y && blockLine > best.line)) best = { y: y, line: blockLine };
            }
            return best ? best.line : 0;
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
                }).then(applyZoom);
            });
            return diagramsDrawn;
        },
        // Opens (true) or closes (false) every <details> of the page.
        setAllOpen: function (open) {
            var all = document.querySelectorAll('details');
            for (var i = 0; i < all.length; i++) all[i].open = !!open;
            if (!open) relayout(all);
        },
        // Zooms a zoomable page's image or diagram to scale (null: fit to the view); keeps the point at the viewport
        // coordinates (x, y) in place, if given.
        setZoom: setZoom,
        // Multiplies the scale in effect by factor, keeping the point at the viewport coordinates (x, y) in place;
        // within MIN_SCALE and MAX_SCALE, but a scale below MIN_SCALE (a fit of huge content) is never raised to it.
        zoomBy: function (factor, x, y) {
            var lowest = Math.min(MIN_SCALE, scale);
            setZoom(Math.min(MAX_SCALE, Math.max(lowest, scale * factor)), x, y);
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
        if (!link) {
            // A click on the image or a Mermaid file's diagram: from fitting the view to 100% at the pointer, and back.
            var onTarget = !isMermaidFile() || (event.target.closest && event.target.closest('pre.mermaid'));
            if (onTarget && zoomTarget()) {
                if (zoom === null) setZoom(1, event.clientX, event.clientY); else setZoom(null);
            }
            return;
        }
        var href = link.getAttribute('href');
        // No link navigates the preview: anchors scroll within the page, every other link is opened by Thoreau.
        event.preventDefault();
        if (href.charAt(0) === '#') {
            var target = document.getElementById(decodeURIComponent(href.substring(1)));
            if (target) target.scrollIntoView();
        } else if (window.sluideBridge) {
            window.sluideBridge.open(href);
        }
    });

    // A fitted image or diagram follows the size of the view; an image fits as soon as it is there.
    window.addEventListener('resize', function () {
        if (zoom === null) applyZoom();
    });
    window.addEventListener('load', applyZoom);
})();
