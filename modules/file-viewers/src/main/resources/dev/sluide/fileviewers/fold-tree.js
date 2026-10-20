// Fold state of the foldable previews (FoldState): the paths of the closed containers (and, on an incomplete
// page, inherited ones), so that the next render (after a save) can start them closed again.
(function () {
    var sluide = (window.sluide = window.sluide || {});
    sluide.folds = sluide.folds || {};
    sluide.folds.collapsed = function () {
        var paths = [];
        var closed = document.querySelectorAll('details[data-path]:not([open])');
        for (var i = 0; i < closed.length; i++) paths.push(closed[i].getAttribute('data-path'));
        // Folds the page was rendered with but has no container for (the text then was invalid, empty or too large).
        var present = {};
        var all = document.querySelectorAll('details[data-path]');
        for (var j = 0; j < all.length; j++) present[all[j].getAttribute('data-path')] = true;
        var state = document.getElementById('fold-state');
        if (state) {
            try {
                var inherited = JSON.parse(state.getAttribute('data-collapsed'));
                for (var k = 0; k < inherited.length; k++) {
                    if (!Object.prototype.hasOwnProperty.call(present, inherited[k])) paths.push(inherited[k]);
                }
            } catch (e) {}
        }
        return JSON.stringify(paths);
    };
})();
