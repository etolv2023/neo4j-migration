/*
 * Live updates. Every page has the resource bar; a migration page also shows its running job, the steps of its stages
 * and the new lines of its log. The server pushes a snapshot every few seconds (Server-Sent Events); when the connection
 * cannot carry a stream the page asks for the same snapshot now and then. The page is not reloaded to update.
 */
(function () {
    'use strict';
    var body = document.body;
    var base = body.getAttribute('data-live') || '/live';
    var migration = body.getAttribute('data-migration') || '';
    var pageBusy = body.getAttribute('data-busy') === 'true';
    var logBox = document.getElementById('job-log');
    var card = document.getElementById('job-progress');
    var lastId = logBox ? parseInt(logBox.getAttribute('data-after') || '0', 10) || 0 : 0;
    var interval = 2;
    var receivedAt = Date.now();
    var last = null;
    var reloading = false;

    // ---------- small helpers
    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) { node.className = cls; }
        if (text != null) { node.textContent = text; }
        return node;
    }
    function num(n) { return Number(n).toLocaleString('en-US'); }
    function bytes(n) {
        if (n == null) { return ''; }
        var gb = n / 1073741824;
        if (gb >= 10) { return gb.toFixed(0) + ' GB'; }
        if (gb >= 1) { return gb.toFixed(1) + ' GB'; }
        return Math.round(n / 1048576) + ' MB';
    }
    function dur(s) {
        s = Math.max(0, Math.round(s));
        if (s < 60) { return s + ' s'; }
        var m = Math.floor(s / 60);
        if (m < 60) { return m + ' min' + (s % 60 && m < 10 ? ' ' + (s % 60) + ' s' : ''); }
        return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
    }
    function store(key, value) {
        try { if (value === undefined) { return window.localStorage.getItem(key); } window.localStorage.setItem(key, value); } catch (e) { return null; }
        return null;
    }

    // ---------- resource bar
    var res = document.getElementById('res');
    var resItems = {};
    function resItem(key, label) {
        if (!resItems[key]) {
            var box = el('div', 'res-i');
            box.appendChild(el('span', 'l', label));
            var value = el('b');
            var bar = el('span', 'mini');
            bar.appendChild(el('i'));
            box.appendChild(value);
            box.appendChild(bar);
            res.appendChild(box);
            resItems[key] = { box: box, value: value, fill: bar.firstChild, bar: bar };
        }
        return resItems[key];
    }
    function setRes(key, label, text, percent, level) {
        var item = resItem(key, label);
        item.value.textContent = text;
        item.box.className = 'res-i' + (level ? ' ' + level : '');
        item.bar.style.display = percent == null ? 'none' : '';
        if (percent != null) { item.fill.style.width = Math.max(0, Math.min(100, percent)) + '%'; }
    }
    function showResources(r) {
        if (!res || !r) { return; }
        res.hidden = false;
        if (r.ram_total) {
            var p = r.ram_used * 100 / r.ram_total;
            setRes('ram', 'RAM', bytes(r.ram_used) + ' of ' + bytes(r.ram_total), p, p > 95 ? 'bad' : p > 85 ? 'warn' : '');
        }
        if (r.swap_total) {
            var sp = r.swap_used * 100 / r.swap_total;
            setRes('swap', 'Swap', bytes(r.swap_used) + ' of ' + bytes(r.swap_total), sp, sp > 25 ? 'bad' : sp > 5 ? 'warn' : '');
        }
        if (r.disk_total) {
            var free = r.disk_free * 100 / r.disk_total;
            setRes('disk', 'Disk free', bytes(r.disk_free) + ' of ' + bytes(r.disk_total), 100 - free, free < 5 ? 'bad' : free < 10 ? 'warn' : '');
        }
        if (r.cpu_percent != null) { setRes('cpu', 'CPU', r.cpu_percent + '%', r.cpu_percent, ''); }
        if (r.iowait_percent != null) {
            // time the processor spends waiting for a disk: high while the disk is what slows a job down
            var io = r.iowait_percent;
            var rates = r.disk_read_per_second != null ? ' · R ' + bytes(r.disk_read_per_second) + '/s · W ' + bytes(r.disk_write_per_second) + '/s' : '';
            setRes('io', 'Disk wait', io + '%' + rates, io, io >= 60 ? 'bad' : io >= 30 ? 'warn' : '');
        }
        if (r.job_rss != null) { setRes('job', 'Job processes', bytes(r.job_rss), null, ''); }
    }

    // ---------- the job
    var STATE_CHIP = { DONE: '✓', RUNNING: '', WAITING: '', FAILED: '✕' };

    function ensureCard() {
        if (!card || card.getAttribute('data-ready')) { return; }
        card.setAttribute('data-ready', '1');
        card.textContent = '';
        var head = el('div', 'progress-head');
        head.appendChild(el('span', 'spinner'));
        head.appendChild(el('b')).setAttribute('data-r', 'label');
        head.appendChild(el('span', 'muted')).setAttribute('data-r', 'elapsed');
        card.appendChild(head);
        card.appendChild(el('div', 'phase')).setAttribute('data-r', 'phase');
        var bar = el('div', 'bar indeterminate');
        bar.appendChild(el('i'));
        bar.setAttribute('data-r', 'bar');
        card.appendChild(bar);
        card.appendChild(el('div', 'numbers')).setAttribute('data-r', 'numbers');
        card.appendChild(el('div', 'numbers muted')).setAttribute('data-r', 'stage');
        card.appendChild(el('p', 'hint', 'Live: this page updates by itself. You can close it; the job keeps running.'));
    }
    function part(name) { return card.querySelector('[data-r=' + name + ']'); }

    function showCard(job) {
        if (!card) { return; }
        card.hidden = !job.busy;
        if (!job.busy) { return; }
        ensureCard();
        part('label').textContent = job.label;
        part('phase').textContent = (job.step_count ? 'Step ' + job.step_number + ' of ' + job.step_count + ' · ' : '') + (job.phase || 'Starting');
        var bar = part('bar');
        var known = job.percent >= 0;
        bar.className = 'bar' + (known ? '' : ' indeterminate');
        bar.firstChild.style.width = known ? job.percent + '%' : '';
        var parts = [];
        if (known) { parts.push(job.percent + '%'); }
        if (!job.stage && job.total >= 0) { parts.push(num(job.done) + ' of ' + num(job.total)); }
        if (!job.stage && job.per_second >= 0) { parts.push(num(job.per_second) + ' per second'); }
        if (job.eta_seconds >= 0) { parts.push('about ' + dur(job.eta_seconds) + ' left in this step'); }
        part('numbers').textContent = parts.join(' · ');
        var stageText = '';
        if (job.stage) {
            stageText = job.stage.number === 0
                ? 'neo4j-admin import · getting ready for ' + dur(job.stage.elapsed_seconds) + ' (it opens every layout of the export before it reads a row)'
                : 'neo4j-admin import · part ' + job.stage.number + ' of ' + job.stage.of + ': ' + job.stage.name + ' · ' + job.stage.percent + '%';
            var done = [];
            if (job.stage.prepared_seconds >= 0) { done.push('getting ready ' + dur(job.stage.prepared_seconds)); }
            (job.stage.finished || []).forEach(function (f) { done.push(f.name + ' ' + dur(f.seconds)); });
            if (done.length) { stageText += ' · done: ' + done.join(', '); }
        } else if (job.total < 0 && job.phase_seconds > 0) {
            stageText = 'this step has taken ' + dur(job.phase_seconds) + ' so far';
        }
        part('stage').textContent = stageText;
    }

    function syncList(list, steps) {
        while (list.children.length > steps.length) { list.removeChild(list.lastChild); }
        var done = 0;
        steps.forEach(function (step, i) {
            var li = list.children[i];
            if (!li) {
                li = el('li');
                li.appendChild(el('span', 'chip'));
                var text = el('div', 't');
                text.appendChild(el('div', 'title'));
                text.appendChild(el('div', 'd'));
                var bar = el('div', 'bar mini');
                bar.appendChild(el('i'));
                text.appendChild(bar);
                li.appendChild(text);
                li.appendChild(el('span', 'time'));
                list.appendChild(li);
            }
            var state = step.state.toLowerCase();
            if (step.state === 'DONE') { done++; }
            li.className = 'st ' + state;
            li.firstChild.textContent = STATE_CHIP[step.state];
            var text = li.children[1];
            text.children[0].textContent = step.title;
            text.children[1].textContent = step.detail || '';
            text.children[1].style.display = step.detail ? '' : 'none';
            var bar = text.children[2];
            var showBar = step.state === 'RUNNING' && step.percent >= 0;
            bar.style.display = showBar ? '' : 'none';
            if (showBar) { bar.firstChild.style.width = step.percent + '%'; }
            var time = li.children[2];
            time.textContent = step.seconds >= 0 ? dur(step.seconds) : '';
            time.setAttribute('data-base', step.state === 'RUNNING' ? step.seconds : '');
        });
        return done;
    }
    function showSteps(job) {
        if (!job.steps) { return; }
        Object.keys(job.steps).forEach(function (key) {
            var list = document.querySelector('ol.tl[data-steps=' + key + ']');
            if (!list) { return; }
            var steps = job.steps[key];
            var done = syncList(list, steps);
            var count = document.querySelector('[data-count=' + key + ']');
            if (count) { count.textContent = steps.length ? done + ' of ' + steps.length + ' done' : ''; }
        });
    }

    function showLog(job) {
        if (!logBox) { return; }
        if (String(job.log_from) !== logBox.getAttribute('data-from')) {
            logBox.textContent = '';
            logBox.setAttribute('data-from', String(job.log_from));
            lastId = 0;
        }
        if (!job.log || !job.log.length) { return; }
        var atBottom = logBox.scrollTop + logBox.clientHeight >= logBox.scrollHeight - 24;
        job.log.forEach(function (line) {
            if (line.id <= lastId) { return; }
            var row = el('div', line.cls + (line.important ? ' imp' : ''));
            row.setAttribute('data-id', line.id);
            row.appendChild(el('span', 't', line.time));
            row.appendChild(el('span', null, line.text));
            logBox.appendChild(row);
            lastId = line.id;
        });
        while (logBox.children.length > 1500) { logBox.removeChild(logBox.firstChild); }
        logBox.style.display = '';
        var empty = document.getElementById('job-log-empty');
        if (empty) { empty.hidden = true; }
        if (atBottom) { logBox.scrollTop = logBox.scrollHeight; }
    }

    function showJob(job) {
        if (!job) { return; }
        if (job.busy !== pageBusy && !reloading) {
            // a job began or ended: the buttons and the results are drawn by the server, so load the page once
            reloading = true;
            setTimeout(function () { window.location.reload(); }, 600);
        }
        showCard(job);
        showSteps(job);
        showLog(job);
    }

    // ---------- the feed
    function receive(data) {
        last = data;
        receivedAt = Date.now();
        if (data.interval_seconds) { interval = data.interval_seconds; }
        showResources(data.resources);
        showJob(data.job);
    }

    // between two snapshots the clocks keep going
    setInterval(function () {
        if (!last || !last.job || !last.job.busy) { return; }
        var gone = (Date.now() - receivedAt) / 1000;
        var elapsed = card && card.querySelector('[data-r=elapsed]');
        if (elapsed) { elapsed.textContent = 'running for ' + dur(last.job.elapsed_seconds + gone); }
        document.querySelectorAll('li.st.running .time').forEach(function (time) {
            var baseSeconds = parseFloat(time.getAttribute('data-base'));
            if (!isNaN(baseSeconds)) { time.textContent = dur(baseSeconds + gone); }
        });
    }, 1000);

    var source = null;
    var timer = null;
    var errors = 0;

    function query() {
        return (migration ? 'migration=' + encodeURIComponent(migration) + '&' : '') + 'after=' + lastId;
    }
    function poll() {
        timer = null;
        if (document.hidden) { return; }
        fetch(base + '.json?' + query(), { credentials: 'same-origin', cache: 'no-store' }).then(function (r) {
            var type = r.headers.get('Content-Type') || '';
            if (r.redirected || type.indexOf('json') < 0) {
                // signed out: show the sign-in page, but never in a loop
                var then = parseInt(store('live-reload') || '0', 10);
                if (Date.now() - then > 30000) { store('live-reload', String(Date.now())); window.location.reload(); }
                throw new Error('signed out');
            }
            return r.json();
        }).then(receive).catch(function () { /* try again next time */ }).then(function () {
            if (!timer && !source) { timer = setTimeout(poll, Math.max(2, interval) * 1000); }
        });
    }
    function stream() {
        if (!window.EventSource) { poll(); return; }
        source = new EventSource(base + '/stream?' + query());
        source.addEventListener('live', function (event) {
            errors = 0;
            try { receive(JSON.parse(event.data)); } catch (e) { /* a broken message is skipped */ }
        });
        source.onerror = function () {
            errors++;
            // a stream the connection cannot carry (a proxy that holds it back, a sign-in that ended): ask instead
            if (errors >= 3 || source.readyState === 2) {
                source.close();
                source = null;
                poll();
            }
        };
    }
    function start() {
        if (source || timer) { return; }
        errors = 0;
        stream();
    }
    function stop() {
        if (source) { source.close(); source = null; }
        if (timer) { clearTimeout(timer); timer = null; }
    }
    document.addEventListener('visibilitychange', function () { if (document.hidden) { stop(); } else { start(); } });
    window.addEventListener('pagehide', stop);

    // ---------- the log's "Important only" switch
    var only = document.getElementById('log-important');
    if (only && logBox) {
        only.checked = store('log-important') === '1';
        function apply() { logBox.classList.toggle('only-important', only.checked); }
        only.addEventListener('change', function () { store('log-important', only.checked ? '1' : '0'); apply(); });
        apply();
    }

    start();
})();
