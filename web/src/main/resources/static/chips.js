// Fields that take a list of values (data-chips="int|range|any|label"): every value becomes a chip inside the box, so the whole
// list can be seen, a value can be removed with its x, and a long list can be pasted at once. The real field stays in the form
// (hidden) and holds the values separated by commas, which is what the server has always read, so without this script the
// field is the plain text box it was.
(function () {
    'use strict';

    var KINDS = {
        // whole numbers
        int: { split: /[\s,;]+/, valid: function (v) { return /^\d+$/.test(v); }, message: 'must be a whole number' },
        // 5000-9000, with or without spaces around the dash
        range: { split: /[,;\n]+/, valid: function (v) { return /^\d+-\d+$/.test(v); }, message: 'write it as 5000-9000',
            clean: function (v) { return v.replace(/\s*(-|\.\.|:)\s*/, '-'); },
            complete: function (v) { return /^\d+\s*(-|\.\.|:)\s*\d+$/.test(v); } },
        // organisation ids may be numbers or text
        any: { split: /[\s,;]+/, valid: function () { return true; } },
        // a label has no spaces or commas
        label: { split: /[\s,;]+/, valid: function (v) { return !/[`]/.test(v); }, message: 'has a character a label cannot have' }
    };

    function build(field) {
        var kind = KINDS[field.dataset.chips] || KINDS.any;
        var wrap = document.createElement('div');
        wrap.className = 'chips';
        var list = document.createElement('div');
        list.className = 'chips-list';
        var input = document.createElement('input');
        input.type = 'text';
        input.className = 'chips-input';
        input.autocomplete = 'off';
        input.spellcheck = false;
        input.setAttribute('aria-label', field.id ? (document.querySelector('label[for="' + field.id + '"]') || {}).textContent || '' : '');
        var note = document.createElement('p');
        note.className = 'hint chips-note';
        note.setAttribute('aria-live', 'polite');

        var values = [];

        var placeholder = field.getAttribute('placeholder') || '';

        function sync() {
            var focused = document.activeElement === input;
            field.value = values.join(', ');
            input.placeholder = values.length ? '' : placeholder;
            list.textContent = '';
            var bad = 0;
            values.forEach(function (value, index) {
                var ok = kind.valid(value);
                bad += ok ? 0 : 1;
                var chip = document.createElement('span');
                chip.className = 'tag' + (ok ? '' : ' bad');
                chip.title = ok ? 'Click to edit' : value + ' ' + kind.message;
                var text = document.createElement('span');
                text.className = 'tag-text';
                text.textContent = value;
                text.addEventListener('click', function () {
                    values.splice(index, 1);
                    sync();
                    input.value = value;
                    input.focus();
                });
                var remove = document.createElement('button');
                remove.type = 'button';
                remove.className = 'tag-x';
                remove.setAttribute('aria-label', 'Remove ' + value);
                remove.textContent = '×';
                remove.addEventListener('click', function () {
                    values.splice(index, 1);
                    sync();
                    input.focus();
                });
                chip.appendChild(text);
                chip.appendChild(remove);
                list.appendChild(chip);
            });
            list.appendChild(input);
            if (focused) { input.focus(); }
            wrap.classList.toggle('has-bad', bad > 0);
            note.textContent = '';
            if (bad > 0) {
                note.className = 'hint chips-note bad';
                note.textContent = bad + (bad === 1 ? ' value ' : ' values ') + 'in red ' + (bad === 1 ? 'is' : 'are') + ' not valid here: ' + kind.message + '.';
            } else if (values.length > 1) {
                note.className = 'hint chips-note';
                note.appendChild(document.createTextNode(values.length + ' values · '));
                var clear = document.createElement('a');
                clear.href = '#';
                clear.textContent = 'clear all';
                clear.addEventListener('click', function (e) {
                    e.preventDefault();
                    values = [];
                    sync();
                    input.focus();
                });
                note.appendChild(clear);
            }
        }

        function add(text) {
            text.split(kind.split).forEach(function (raw) {
                var value = raw.trim();
                if (!value) { return; }
                if (kind.clean) { value = kind.clean(value); }
                if (values.indexOf(value) < 0) { values.push(value); }
            });
        }

        function commit() {
            if (input.value.trim()) {
                add(input.value);
                input.value = '';
                sync();
            }
        }

        input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
                if (input.value.trim()) { e.preventDefault(); commit(); }
            } else if (e.key === ',' || e.key === ';') {
                e.preventDefault();
                commit();
            } else if (e.key === ' ' && input.value.trim() && (!kind.complete || kind.complete(input.value.trim()))) {
                e.preventDefault();
                commit();
            } else if (e.key === 'Backspace' && !input.value && values.length) {
                values.pop();
                sync();
            }
        });
        input.addEventListener('paste', function (e) {
            var data = (e.clipboardData || window.clipboardData);
            if (!data) { return; }
            e.preventDefault();
            // not through input.value: a text box drops the line breaks, which also separate values
            add(input.value + ',' + data.getData('text'));
            input.value = '';
            sync();
        });
        input.addEventListener('blur', commit);
        wrap.addEventListener('click', function (e) {
            if (e.target === wrap || e.target === list) { input.focus(); }
        });

        wrap.appendChild(list);
        field.parentNode.insertBefore(wrap, field);
        field.parentNode.insertBefore(note, wrap.nextSibling);
        field.type = 'hidden';
        if (field.id) {
            input.id = field.id + '-chips';
            var label = document.querySelector('label[for="' + field.id + '"]');
            if (label) { label.htmlFor = input.id; }
        }
        add(field.value);
        sync();

        var form = field.form;
        if (form) {
            form.addEventListener('submit', function (e) {
                commit();
                if (wrap.classList.contains('has-bad')) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    wrap.scrollIntoView({ block: 'center' });
                    input.focus();
                }
            }, true);
        }
    }

    function init() {
        Array.prototype.forEach.call(document.querySelectorAll('input[data-chips]'), build);
    }
    if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', init); } else { init(); }
})();
