// Sorting for the CSV table (CsvRenderer): a click on a header sorts by that column, again for descending. Numbers
// sort as numbers, everything else as text; empty cells last.
(function () {
    var table = document.querySelector('table.csv-table');
    if (!table) return;
    var headers = table.querySelectorAll('thead th');
    var body = table.tBodies[0];

    function value(row, column) {
        var cell = row.cells[column];
        return cell ? cell.textContent.trim() : '';
    }

    function compare(a, b) {
        if (a === b) return 0;
        if (a === '') return 1;
        if (b === '') return -1;
        var x = Number(a.replace(/,/g, ''));
        var y = Number(b.replace(/,/g, ''));
        if (!isNaN(x) && !isNaN(y)) return x - y;
        return a.localeCompare(b);
    }

    for (var i = 0; i < headers.length; i++) {
        (function (column) {
            headers[column].addEventListener('click', function () {
                var descending = headers[column].classList.contains('ascending');
                for (var h = 0; h < headers.length; h++) headers[h].classList.remove('ascending', 'descending');
                headers[column].classList.add(descending ? 'descending' : 'ascending');
                var rows = Array.prototype.slice.call(body.rows);
                rows.sort(function (r, s) {
                    var order = compare(value(r, column), value(s, column));
                    return descending ? -order : order;
                });
                for (var r = 0; r < rows.length; r++) body.appendChild(rows[r]);
            });
        })(i);
    }
})();
