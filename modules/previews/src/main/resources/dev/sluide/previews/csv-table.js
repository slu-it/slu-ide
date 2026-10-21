// The CSV table (CsvRenderer): the "First row is header" checkbox, sorting by a clicked label (ascending, descending,
// file order) and a search field per column. Numbers sort as numbers, everything else as text; empty cells last.
// sluide.table.state() reports all of it, so that the next page (after a save) starts from it (TableState).
(function () {
    var table = document.querySelector('table.csv-table');
    if (!table) return;
    var labelRow = table.tHead.rows[0];
    var labels = labelRow.cells;
    var filterRow = table.tHead.rows[1];
    var inputs = filterRow.querySelectorAll('input.csv-filter');
    var toggle = document.querySelector('input.csv-header-toggle');
    var count = document.querySelector('.csv-count');
    var body = table.tBodies[0];
    var first = body.querySelector('tr.csv-first');
    var note = document.querySelector('.csv-limit');
    var decimalComma = table.getAttribute('data-decimal') === ',';
    var header = table.getAttribute('data-header') === 'true';
    var state = {};
    try {
        state = JSON.parse(table.getAttribute('data-state')) || {};
    } catch (e) {
        state = {};
    }
    // Whether the user chose the header (clicked the checkbox); otherwise the next page guesses again.
    var headerChosen = !!toggle && (state.header === true || state.header === false);
    var sortColumn = typeof state.sortColumn === 'number' && state.sortColumn < labels.length ? state.sortColumn : null;
    var descending = sortColumn !== null && state.descending === true;
    var filterTimer = null;

    function value(row, column) {
        var cell = row.cells[column];
        return cell ? cell.textContent.trim() : '';
    }

    function line(row) {
        return Number(row.getAttribute('data-line'));
    }

    // "1,234.5", or "1.234,5" with decimal commas; NaN for anything else, also for "" and dates like "01.02.2024".
    function number(text) {
        if (!/^[+-]?[\d.,]*\d[\d.,]*([eE][+-]?\d+)?$/.test(text) || /^\d{1,2}\.\d{1,2}\.\d{4}$/.test(text)) return NaN;
        return Number(decimalComma ? text.replace(/\./g, '').replace(',', '.') : text.replace(/,/g, ''));
    }

    function compare(a, b) {
        if (a === b) return 0;
        if (a === '') return 1;
        if (b === '') return -1;
        var x = number(a);
        var y = number(b);
        if (!isNaN(x) && !isNaN(y)) return x - y;
        return a.localeCompare(b);
    }

    // Spreadsheet column names: A to Z, then AA, AB and so on.
    function letters(column) {
        var name = '';
        for (var rest = column + 1; rest > 0; rest = Math.floor((rest - 1) / 26)) {
            name = String.fromCharCode(65 + (rest - 1) % 26) + name;
        }
        return name;
    }

    // The rows that are data: all but the first record while it is the header.
    function dataRows() {
        var rows = [];
        for (var r = 0; r < body.rows.length; r++) {
            if (!(header && body.rows[r] === first)) rows.push(body.rows[r]);
        }
        return rows;
    }

    function applyHeader() {
        if (header) table.classList.add('has-header'); else table.classList.remove('has-header');
        for (var c = 0; c < labels.length; c++) labels[c].textContent = header && first ? value(first, c) : letters(c);
        // The label row stands for the hidden first record in the scroll sync.
        if (header && first) labelRow.setAttribute('data-line', first.getAttribute('data-line'));
        else labelRow.removeAttribute('data-line');
        placeFilterRow();
        updateNote();
    }

    // Keeps the filter row stuck right below the label row.
    function placeFilterRow() {
        for (var c = 0; c < filterRow.cells.length; c++) filterRow.cells[c].style.top = labelRow.offsetHeight + 'px';
    }

    // Stripes every second shown data row (nth-child would count hidden rows).
    function stripe() {
        var odd = false;
        for (var r = 0; r < body.rows.length; r++) {
            var row = body.rows[r];
            if (row.hidden || (header && row === first)) continue;
            if (odd) row.classList.add('csv-odd'); else row.classList.remove('csv-odd');
            odd = !odd;
        }
    }

    // The note on cut-off files counts data rows, which depends on the header.
    function updateNote() {
        if (!note) return;
        var skip = header ? 1 : 0;
        note.textContent = 'Showing the first ' + grouped(Number(note.getAttribute('data-shown')) - skip) + ' of ' +
            grouped(Number(note.getAttribute('data-records')) - skip) + ' rows. Search covers only the rows shown.';
    }

    function grouped(n) {
        return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    }

    function applySort() {
        for (var h = 0; h < labels.length; h++) labels[h].classList.remove('ascending', 'descending');
        if (sortColumn !== null) labels[sortColumn].classList.add(descending ? 'descending' : 'ascending');
        var rows = dataRows();
        rows.sort(function (r, s) {
            if (sortColumn !== null) {
                var order = compare(value(r, sortColumn), value(s, sortColumn));
                if (order !== 0) return descending ? -order : order;
            }
            return line(r) - line(s);
        });
        if (header && first) body.appendChild(first);
        for (var r = 0; r < rows.length; r++) body.appendChild(rows[r]);
        stripe();
    }

    // A test for one search field: a comparison (">100", "<=5", "=3", "10..20") if its numbers are numbers, else
    // a case-insensitive substring. Null for an empty field.
    function matcher(text) {
        text = text.trim();
        if (text === '') return null;
        var comparison = /^(<=|>=|<|>|=)\s*(.+)$/.exec(text);
        if (comparison) {
            var operand = number(comparison[2].trim());
            if (!isNaN(operand)) {
                var operator = comparison[1];
                return numeric(function (x) {
                    if (operator === '<') return x < operand;
                    if (operator === '<=') return x <= operand;
                    if (operator === '>') return x > operand;
                    if (operator === '>=') return x >= operand;
                    return x === operand;
                });
            }
        }
        var range = /^(.+?)\s*\.\.\s*(.+)$/.exec(text);
        if (range) {
            var low = number(range[1].trim());
            var high = number(range[2].trim());
            if (!isNaN(low) && !isNaN(high)) return numeric(function (x) { return x >= low && x <= high; });
        }
        var needle = text.toLowerCase();
        return function (cell) { return cell.toLowerCase().indexOf(needle) >= 0; };
    }

    // Cells that are not numbers never match a comparison.
    function numeric(test) {
        return function (cell) {
            var x = number(cell);
            return !isNaN(x) && test(x);
        };
    }

    function applyFilters() {
        var tests = [];
        for (var i = 0; i < inputs.length; i++) {
            var test = matcher(inputs[i].value);
            if (test) tests.push({ column: i, test: test });
        }
        if (first) first.hidden = false;
        var rows = dataRows();
        var shown = 0;
        for (var r = 0; r < rows.length; r++) {
            var match = true;
            for (var t = 0; t < tests.length && match; t++) match = tests[t].test(value(rows[r], tests[t].column));
            rows[r].hidden = !match;
            if (match) shown++;
        }
        count.textContent = tests.length ? shown + ' of ' + rows.length + ' rows' : '';
        stripe();
    }

    for (var i = 0; i < labels.length; i++) {
        (function (column) {
            labels[column].addEventListener('click', function () {
                if (sortColumn !== column) {
                    sortColumn = column;
                    descending = false;
                } else if (!descending) {
                    descending = true;
                } else {
                    sortColumn = null;
                    descending = false;
                }
                applySort();
            });
        })(i);
    }

    for (var f = 0; f < inputs.length; f++) {
        inputs[f].addEventListener('input', function () {
            clearTimeout(filterTimer);
            filterTimer = setTimeout(applyFilters, 150);
        });
    }

    if (toggle) {
        toggle.addEventListener('change', function () {
            header = toggle.checked;
            headerChosen = true;
            applyHeader();
            applySort();
            applyFilters();
        });
    }

    window.sluide = window.sluide || {};
    window.sluide.table = {
        state: function () {
            var filters = [];
            for (var i = 0; i < inputs.length; i++) filters.push(inputs[i].value);
            return JSON.stringify({
                header: headerChosen ? header : null,
                sortColumn: sortColumn,
                descending: descending,
                filters: filters
            });
        }
    };

    applyHeader();
    if (sortColumn !== null) applySort();
    applyFilters();
    window.addEventListener('load', placeFilterRow);
})();
