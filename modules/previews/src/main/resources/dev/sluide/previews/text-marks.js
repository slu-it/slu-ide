// Text marks for pages whose text runs are <span data-o="N">: N is the offset of the run's first char in the page's
// flat text. Marks use the CSS Custom Highlight API, so a page styles ::highlight(mark-<name>). Kotlin side: TextMarks.
(function () {
  var sluide = (window.sluide = window.sluide || {});
  var spans = null; // { el, o, len, nodes }, in document order, built on first use

  // Any change of the DOM may replace spans: the index is built again on next use. A page that runs this script in
  // its head would otherwise record every node of its body parse, so the observer starts when parsing is done.
  function observe() {
    spans = null;
    new MutationObserver(function () { spans = null; }).observe(document, { childList: true, subtree: true, characterData: true });
  }
  if (document.readyState !== 'loading') observe();
  else document.addEventListener('DOMContentLoaded', observe);

  function index() {
    if (spans) return spans;
    spans = [];
    var els = document.querySelectorAll('[data-o]');
    for (var i = 0; i < els.length; i++) {
      var nodes = [];
      var walker = document.createTreeWalker(els[i], NodeFilter.SHOW_TEXT);
      var len = 0;
      while (walker.nextNode()) {
        nodes.push(walker.currentNode);
        len += walker.currentNode.data.length;
      }
      if (len > 0) spans.push({ el: els[i], o: parseInt(els[i].getAttribute('data-o'), 10), len: len, nodes: nodes });
    }
    return spans;
  }

  // The text node and offset of the k-th char of a span, k clamped to the span.
  function position(span, k) {
    k = Math.max(0, Math.min(k, span.len));
    for (var i = 0; i < span.nodes.length; i++) {
      var n = span.nodes[i].data.length;
      if (k <= n || i === span.nodes.length - 1) return [span.nodes[i], Math.min(k, n)];
      k -= n;
    }
    return null;
  }

  // The first span index satisfying test, which must turn true once and stay true.
  function firstWhere(list, test) {
    var lo = 0, hi = list.length;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      if (test(list[mid])) hi = mid; else lo = mid + 1;
    }
    return lo;
  }

  // A Range over the chars [start, end) of the flat text, or null if no span covers any of them.
  function rangeFor(start, end) {
    var list = index();
    if (!(end > start)) return null;
    var first = firstWhere(list, function (s) { return s.o + s.len > start; });
    var last = firstWhere(list, function (s) { return s.o >= end; }) - 1;
    if (first > last) return null;
    var a = list[first], b = list[last];
    var from = position(a, start - a.o), to = position(b, end - b.o);
    if (!from || !to) return null;
    var range = document.createRange();
    range.setStart(from[0], from[1]);
    range.setEnd(to[0], to[1]);
    return range;
  }

  // The offset of a caret position (text node, offset), or null outside any data-o span.
  function offsetOf(node, offset) {
    var el = node.nodeType === 3 ? node.parentElement : node;
    var holder = el && el.closest('[data-o]');
    if (!holder) return null;
    var base = parseInt(holder.getAttribute('data-o'), 10);
    var walker = document.createTreeWalker(holder, NodeFilter.SHOW_TEXT);
    var total = 0, found = -1;
    while (walker.nextNode()) {
      if (walker.currentNode === node) found = total + offset;
      total += walker.currentNode.data.length;
    }
    if (found < 0) found = node.nodeType === 3 ? 0 : Math.min(offset, total);
    return base + Math.min(found, total);
  }

  function rects(range) {
    return Array.prototype.filter.call(range.getClientRects(), function (r) { return r.width > 0 || r.height > 0; });
  }

  // User scrolling: wheel, scroll keys, touch, scrollbar. Scrolls by reveal() and scrollToTop() are told apart by their
  // position: the scrollY values the page scrolled to itself (several, when it scrolled twice in one frame).
  var expected = [];
  // Counts what scrolled the page other than scrollToTop (the user, a reveal): it cancels a pending second scroll.
  var interrupts = 0;
  function userScrolled() {
    var bridge = window.sluideMarksBridge;
    if (bridge) bridge.userScrolled();
  }
  window.addEventListener('wheel', function (e) { if (!e.ctrlKey && !e.metaKey) { expected = []; interrupts++; userScrolled(); } }, { passive: true });
  window.addEventListener('touchmove', function () { expected = []; interrupts++; userScrolled(); }, { passive: true });
  window.addEventListener('keydown', function (e) {
    if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].indexOf(e.key) >= 0) { expected = []; interrupts++; userScrolled(); }
  });
  // A zoom or a resize changes the viewport size, and may scroll the page: not the user's doing.
  var viewW = window.innerWidth, viewH = window.innerHeight;
  function viewChanged() {
    var changed = window.innerWidth !== viewW || window.innerHeight !== viewH;
    viewW = window.innerWidth;
    viewH = window.innerHeight;
    return changed;
  }
  // The resize event may come before or after the scroll events it causes: both are told apart for a few frames.
  var resizing = 0;
  window.addEventListener('resize', function () {
    viewChanged();
    resizing++;
    requestAnimationFrame(function () { requestAnimationFrame(function () { resizing--; }); });
  });
  window.addEventListener('scroll', function () {
    if (viewChanged() || resizing > 0) { expected = []; return; }
    var own = expected.some(function (y) { return Math.abs(window.scrollY - y) < 1; });
    expected = [];
    if (!own) { interrupts++; userScrolled(); }
  });

  // The WebKit of the WebView does not invalidate the paint of a highlight that was replaced or removed: the old one
  // stays painted (a ghost) until the area scrolls out of view and back. The elements the old and the new range touch
  // get opacity 0.999 (a repaint but no layout; outline-color does not repaint), taken back a frame later.
  var touched = {}; // mark name -> the span elements its current range touches
  var pending = [];
  // The spans covering [start, end), found by the same binary search as rangeFor: a mark costs its own length, not the
  // page's (a whole book is one page, and Read Aloud marks every spoken word).
  function spansOf(start, end) {
    var list = index();
    var first = firstWhere(list, function (s) { return s.o + s.len > start; });
    var last = firstWhere(list, function (s) { return s.o >= end; }) - 1;
    return list.slice(first, Math.max(first, last + 1)).map(function (s) { return s.el; });
  }
  function repaint(els) {
    if (els.length === 0) return;
    var fresh = els.filter(function (el) { return pending.indexOf(el) < 0; });
    fresh.forEach(function (el) { el.style.opacity = '0.999'; });
    var again = pending.length === 0;
    pending = pending.concat(fresh);
    if (!again) return;
    requestAnimationFrame(function () {
      var done = pending;
      pending = [];
      done.forEach(function (el) { el.style.opacity = ''; });
    });
  }

  sluide.marks = {
    mark: function (name, start, end) {
      var range = rangeFor(start, end);
      var previous = touched[name];
      if (range) {
        CSS.highlights.set('mark-' + name, new Highlight(range));
        touched[name] = spansOf(start, end);
      } else {
        CSS.highlights.delete('mark-' + name);
        delete touched[name];
      }
      repaint((previous || []).concat(touched[name] || []));
    },
    clear: function (name) {
      CSS.highlights.delete('mark-' + name);
      var previous = touched[name];
      delete touched[name];
      repaint(previous || []);
    },
    reveal: function (start) {
      // A gap between spans reveals the next span
      var list = index();
      var next = list[firstWhere(list, function (s) { return s.o + s.len > start; })];
      var at = next ? Math.max(start, next.o) : 0;
      var range = next && rangeFor(at, at + 1);
      var r = range && rects(range)[0];
      if (!r) return;
      var h = window.innerHeight;
      if (r.top >= h * 0.15 && r.bottom <= h * 0.85) return;
      var before = window.scrollY;
      window.scrollTo(window.scrollX, Math.max(0, before + r.top - (h - r.height) / 2));
      if (window.scrollY !== before) { expected.push(window.scrollY); interrupts++; }
    },
    // Scrolls so that the line of the char at start is at the top of the viewport. The layout may not be complete right
    // after a load: it is done again two frames later, unless the page was scrolled meanwhile.
    scrollToTop: function (start) {
      function once() {
        var list = index();
        var next = list[firstWhere(list, function (s) { return s.o + s.len > start; })];
        var at = next ? Math.max(start, next.o) : 0;
        var range = next && rangeFor(at, at + 1);
        var r = range && rects(range)[0];
        if (!r) return null;
        var before = window.scrollY;
        window.scrollTo(window.scrollX, Math.max(0, before + r.top));
        if (window.scrollY !== before) expected.push(window.scrollY);
        return window.scrollY;
      }
      // Two frames later only if nothing else (the user, a reveal) has moved the page since.
      var seen = interrupts;
      once();
      requestAnimationFrame(function () {
        requestAnimationFrame(function () { if (interrupts === seen) once(); });
      });
    },
    isVisible: function (start, end) {
      var range = rangeFor(start, end);
      if (!range) return false;
      return rects(range).some(function (r) { return r.bottom > 0 && r.top < window.innerHeight; });
    },
    topOffset: function () {
      var list = index();
      // Binary search over the spans below the top edge; a hidden span (no box) says nothing, the next one decides.
      var lo = 0, hi = list.length, r;
      while (lo < hi) {
        var mid = (lo + hi) >> 1, probe = mid;
        while (probe < hi && (r = list[probe].el.getBoundingClientRect(), r.width === 0 && r.height === 0)) probe++;
        if (probe === hi) hi = mid; else if (r.bottom > 0) hi = mid; else lo = mid + 1;
      }
      // The search may end on a hidden span: the first one with a box from there on
      while (lo < list.length && (r = list[lo].el.getBoundingClientRect(), r.width === 0 && r.height === 0)) lo++;
      var i = lo;
      if (i >= list.length) return null;
      var s = list[i];
      if (s.el.getBoundingClientRect().top >= 0) return s.o;
      // A span cut by the top edge: the first of its lines that reaches into view
      var range = document.createRange();
      range.selectNodeContents(s.el);
      var line = rects(range).filter(function (r) { return r.bottom > 0; })[0];
      if (!line) return s.o;
      var caret = document.caretRangeFromPoint(line.left + 1, (Math.max(line.top, 0) + line.bottom) / 2);
      var o = caret && offsetOf(caret.startContainer, caret.startOffset);
      return o === null || o === undefined ? s.o : o;
    },
    offsetAt: function (x, y) {
      var caret = document.caretRangeFromPoint(x, y);
      return caret ? offsetOf(caret.startContainer, caret.startOffset) : null;
    }
  };
})();
