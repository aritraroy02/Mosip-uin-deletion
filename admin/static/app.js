'use strict';

/* State for the currently open table view. */
const state = {
  db: null, schema: null, table: null,
  q: '', page: 0, size: 50, sort: '', dir: 'asc', deep: false, mode: 'auto',
  columns: [], overview: [], tables: {}, expanded: new Set(),
  buckets: [], bucketsError: null, bucket: null, oq: '', opage: 0, osize: 50,
};

const $ = (id) => document.getElementById(id);

const api = async (path, params) => {
  const url = new URL(path, location.origin);
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== '' && v !== null && v !== undefined) url.searchParams.set(k, v);
  });
  const res = await fetch(url);
  const body = await res.json();
  if (!res.ok) throw new Error(body.error || res.statusText);
  return body;
};

const fmt = (n) => (n === null || n === undefined) ? '—' : n.toLocaleString();

const bytes = (n) => {
  if (!n) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  return (n / Math.pow(1024, i)).toFixed(i ? 1 : 0) + ' ' + units[i];
};

const show = (id) => {
  ['welcome', 'table-view', 'search-view', 'bucket-view', 'error-view']
    .forEach((s) => { $(s).hidden = (s !== id); });
};

const fail = (err) => { $('error-text').textContent = String(err.message || err); show('error-view'); };

/* ---------------- sidebar ---------------- */

async function loadOverview(refresh) {
  const data = await api('/api/overview', refresh ? { refresh: 1 } : {});
  state.overview = data.databases;
  state.buckets = data.buckets || [];
  state.bucketsError = data.buckets_error || null;
  renderSidebar();
}

function renderSidebar() {
  const filter = $('table-filter').value.trim().toLowerCase();
  const list = $('db-list');
  list.replaceChildren();

  for (const db of state.overview) {
    const group = document.createElement('div');
    group.className = 'db-group';

    const head = document.createElement('button');
    head.className = 'db-head';
    head.innerHTML =
      `<span class="dot ${db.online ? 'online' : ''}"></span>` +
      `<span class="db-name">${db.database}</span>` +
      `<span class="db-count">${db.online ? db.tables + ' tables · ' + bytes(db.bytes) : 'offline'}</span>`;
    head.title = db.online ? `port ${db.port}` : (db.error || 'not reachable');
    head.onclick = () => toggleDb(db.database);
    group.appendChild(head);

    if (state.expanded.has(db.database)) {
      const tables = state.tables[db.database] || [];
      const matching = tables.filter((t) => !filter || t.name.toLowerCase().includes(filter));
      if (!matching.length) {
        const empty = document.createElement('p');
        empty.className = 'muted pad';
        empty.textContent = tables.length ? 'No table matches the filter.' : 'No tables yet.';
        group.appendChild(empty);
      }
      for (const t of matching) {
        const btn = document.createElement('button');
        btn.className = 'tbl' + (state.db === db.database && state.table === t.table ? ' active' : '');
        btn.innerHTML = `<span>${t.name}</span><span class="n">${fmt(t.estimated_rows)}</span>`;
        btn.title = `${t.name} — ~${fmt(t.estimated_rows)} rows, ${bytes(t.bytes)}`;
        btn.onclick = () => openTable(db.database, t.schema, t.table);
        group.appendChild(btn);
      }
    }
    list.appendChild(group);
  }

  renderBuckets(list, filter);
}

function renderBuckets(list, filter) {
  const group = document.createElement('div');
  group.className = 'db-group';

  const online = state.buckets.length > 0;
  const totalObjects = state.buckets.reduce((a, b) => a + b.objects, 0);
  const totalBytes = state.buckets.reduce((a, b) => a + b.bytes, 0);

  const head = document.createElement('button');
  head.className = 'db-head';
  head.innerHTML =
    `<span class="dot ${online ? 'online' : ''}"></span>` +
    `<span class="db-name">MinIO object store</span>` +
    `<span class="db-count">${online
      ? state.buckets.length + ' buckets \u00b7 ' + bytes(totalBytes)
      : 'offline'}</span>`;
  head.title = state.bucketsError || `${fmt(totalObjects)} objects`;
  head.onclick = () => {
    state.expanded.has('__minio')
      ? state.expanded.delete('__minio')
      : state.expanded.add('__minio');
    renderSidebar();
  };
  group.appendChild(head);

  if (state.expanded.has('__minio')) {
    const matching = state.buckets.filter(
      (b) => !filter || b.bucket.toLowerCase().includes(filter));
    for (const b of matching) {
      const btn = document.createElement('button');
      btn.className = 'tbl' + (state.bucket === b.bucket ? ' active' : '');
      btn.innerHTML = `<span>${b.bucket}</span><span class="n">${fmt(b.objects)}</span>`;
      btn.title = `${b.bucket} \u2014 ${fmt(b.objects)} objects, ${bytes(b.bytes)}`;
      btn.onclick = () => openBucket(b.bucket);
      group.appendChild(btn);
    }
    if (!matching.length) {
      const p = document.createElement('p');
      p.className = 'muted pad';
      p.textContent = state.bucketsError || 'No bucket matches the filter.';
      group.appendChild(p);
    }
  }
  list.appendChild(group);
}

/* ---------------- object store ---------------- */

const OBJECT_PREVIEW_LABEL = '8 KB';

async function openBucket(bucket) {
  Object.assign(state, { bucket, oq: '', opage: 0, db: null, table: null });
  $('object-search').value = '';
  renderSidebar();
  await loadObjects();
}

async function loadObjects() {
  show('bucket-view');
  $('bucket-title').textContent = state.bucket;
  $('bucket-meta').textContent = 'Loading\u2026';
  try {
    const data = await api('/api/objects', {
      bucket: state.bucket, q: state.oq, page: state.opage, size: state.osize,
    });
    renderObjects(data);
  } catch (err) {
    fail(err);
  }
}

function renderObjects(data) {
  $('bucket-meta').textContent =
    `${fmt(data.total)} objects` +
    (state.oq ? ` matching \u201c${state.oq}\u201d` : '') +
    ' \u00b7 click a key to inspect the object';

  const grid = $('object-grid');
  grid.replaceChildren();

  const thead = document.createElement('thead');
  thead.innerHTML = '<tr><th></th><th>key</th><th>size</th><th>last modified</th></tr>';
  grid.appendChild(thead);

  const tbody = document.createElement('tbody');
  data.objects.forEach((o, i) => {
    const tr = document.createElement('tr');

    const num = document.createElement('td');
    num.className = 'num';
    num.textContent = state.opage * state.osize + i + 1;
    tr.appendChild(num);

    const key = document.createElement('td');
    const link = document.createElement('span');
    link.className = 'more';
    link.textContent = o.key;
    link.onclick = () => openObject(o.key);
    key.appendChild(link);
    tr.appendChild(key);

    const size = document.createElement('td');
    size.textContent = bytes(o.size);
    size.style.textAlign = 'right';
    tr.appendChild(size);

    const mod = document.createElement('td');
    mod.textContent = o.modified ? o.modified.replace('T', ' ').slice(0, 19) : '\u2014';
    tr.appendChild(mod);

    tbody.appendChild(tr);
  });

  if (!data.objects.length) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = 4;
    td.className = 'null';
    td.style.padding = '20px';
    td.textContent = state.oq ? 'No object key matches.' : 'This bucket is empty.';
    tr.appendChild(td);
    tbody.appendChild(tr);
  }
  grid.appendChild(tbody);

  const from = data.objects.length ? state.opage * state.osize + 1 : 0;
  $('obj-page-info').textContent =
    `Objects ${fmt(from)}\u2013${fmt(state.opage * state.osize + data.objects.length)}` +
    ` of ${fmt(data.total)}`;
  $('obj-prev').disabled = state.opage === 0;
  $('obj-next').disabled = (state.opage + 1) * state.osize >= data.total;
}

async function openObject(key) {
  $('modal-title').textContent = `${state.bucket} \u00b7 ${key}`;
  $('modal-body').textContent = 'Loading\u2026';
  $('modal').hidden = false;
  try {
    const d = await api('/api/object', { bucket: state.bucket, key });
    const header = [
      `size          ${d.size} bytes`,
      `content-type  ${d.content_type || '\u2014'}`,
      `modified      ${d.modified || '\u2014'}`,
      `etag          ${d.etag || '\u2014'}`,
      '',
      d.kind === 'hex'
        ? `binary \u2014 first ${OBJECT_PREVIEW_LABEL} as hex${d.truncated ? ' (truncated)' : ''}:`
        : `content${d.truncated ? ' (truncated)' : ''}:`,
      '',
    ].join('\n');
    $('modal-body').textContent = header + d.preview;
  } catch (err) {
    $('modal-body').textContent = String(err.message || err);
  }
}

async function toggleDb(db) {
  if (state.expanded.has(db)) {
    state.expanded.delete(db);
  } else {
    state.expanded.add(db);
    if (!state.tables[db]) {
      try {
        const { tables } = await api('/api/tables', { db });
        state.tables[db] = tables;
      } catch (err) {
        state.tables[db] = [];
        fail(err);
      }
    }
  }
  renderSidebar();
}

/* ---------------- table view ---------------- */

async function openTable(db, schema, table) {
  Object.assign(state, { db, schema, table, q: '', page: 0, sort: '', dir: 'asc',
                       bucket: null });
  $('row-search').value = '';
  renderSidebar();
  await loadRows();
}

async function loadRows() {
  show('table-view');
  $('table-title').textContent = `${state.schema}.${state.table}`;
  $('table-meta').textContent = 'Loading…';
  try {
    const data = await api('/api/rows', {
      db: state.db, schema: state.schema, table: state.table,
      q: state.q, page: state.page, size: state.size,
      sort: state.sort, dir: state.dir, deep: state.deep ? 1 : '',
      mode: state.mode,
    });
    state.columns = data.columns;
    renderGrid(data);
  } catch (err) {
    fail(err);
  }
}

function renderGrid(data) {
  const count = data.exact ? fmt(data.total) : 'too many to count quickly';
  let meta = `${state.db} · ${data.columns.length} columns · ${count} rows` +
    (state.q ? ` matching “${state.q}”` : '');
  if (state.q) {
    meta += data.mode === 'exact'
      ? ' · exact match on whole column values (indexed)'
      : ' · substring scan';
    if (data.not_searched.length) {
      meta += ` · not searched: ${data.not_searched.join(', ')}`;
    }
  }
  $('table-meta').textContent = meta;

  const grid = $('grid');
  grid.replaceChildren();

  const thead = document.createElement('thead');
  const hrow = document.createElement('tr');
  hrow.appendChild(document.createElement('th'));
  for (const col of data.columns) {
    const th = document.createElement('th');
    const arrow = state.sort === col.name ? (state.dir === 'asc' ? ' ▲' : ' ▼') : '';
    th.innerHTML = `${col.name}${arrow}<span class="ty">${col.udt}</span>`;
    th.title = 'Sort by ' + col.name;
    th.onclick = () => {
      state.dir = (state.sort === col.name && state.dir === 'asc') ? 'desc' : 'asc';
      state.sort = col.name;
      state.page = 0;
      loadRows();
    };
    hrow.appendChild(th);
  }
  thead.appendChild(hrow);
  grid.appendChild(thead);

  const tbody = document.createElement('tbody');
  data.rows.forEach((row, i) => {
    const tr = document.createElement('tr');
    const num = document.createElement('td');
    num.className = 'num';
    num.textContent = state.page * state.size + i + 1;
    tr.appendChild(num);

    row.forEach((cell, c) => {
      const td = document.createElement('td');
      const col = data.columns[c];
      if (cell === null) {
        td.innerHTML = '<span class="null">NULL</span>';
      } else if (typeof cell === 'object') {
        td.textContent = cell.v;
        const more = document.createElement('span');
        more.className = 'more';
        more.textContent = ' …more';
        more.onclick = () => openCell(col.name, state.page * state.size + i);
        td.appendChild(more);
      } else if (col.udt === 'bytea') {
        td.innerHTML = `<span class="blob"></span>`;
        td.firstChild.textContent = cell;
        td.style.cursor = 'pointer';
        td.onclick = () => openCell(col.name, state.page * state.size + i);
      } else {
        td.textContent = cell;
      }
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });
  grid.appendChild(tbody);

  if (!data.rows.length) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = data.columns.length + 1;
    td.className = 'null';
    td.style.padding = '20px';
    td.textContent = state.q ? 'No rows match this search.' : 'This table is empty.';
    tr.appendChild(td);
    tbody.appendChild(tr);
  }

  const from = data.rows.length ? state.page * state.size + 1 : 0;
  $('page-info').textContent =
    `Rows ${fmt(from)}–${fmt(state.page * state.size + data.rows.length)}` +
    (data.exact ? ` of ${fmt(data.total)}` : '');
  $('prev').disabled = state.page === 0;
  $('next').disabled = data.rows.length < state.size;
}

async function openCell(column, offset) {
  $('modal-title').textContent = `${state.schema}.${state.table} · ${column} · row ${offset + 1}`;
  $('modal-body').textContent = 'Loading…';
  $('modal').hidden = false;
  try {
    const data = await api('/api/cell', {
      db: state.db, schema: state.schema, table: state.table, column,
      q: state.q, offset, sort: state.sort, dir: state.dir,
      deep: state.deep ? 1 : '', mode: state.mode,
    });
    $('modal-body').textContent = data.value === null ? 'NULL' : data.value;
  } catch (err) {
    $('modal-body').textContent = String(err.message || err);
  }
}

/* ---------------- global search ---------------- */

async function runSearch(term) {
  show('search-view');
  $('search-title').textContent = `Search: ${term}`;
  $('search-meta').textContent = 'Scanning every table in every database…';
  $('search-results').replaceChildren();
  try {
    const data = await api('/api/search', { q: term, deep: state.deep ? 1 : '', mode: state.mode });
    renderSearch(data);
  } catch (err) {
    fail(err);
  }
}

function renderSearch(data) {
  const box = $('search-results');
  box.replaceChildren();
  const hits = data.results.filter((r) => r.hits > 0);
  const total = hits.reduce((a, r) => a + r.hits, 0);

  const notes = [`${fmt(total)} matching rows across ${hits.length} tables`];
  notes.push(data.mode === 'exact'
    ? 'exact match on whole column values (indexed, fast)'
    : 'substring scan — switch to Exact if you are tracing an identifier');
  const slow = data.results.filter((r) => r.status !== 'ok');
  if (slow.length) notes.push(`${slow.length} large table(s) not searched — open one to search it directly`);
  if (data.skipped_operational) {
    notes.push(`${data.skipped_operational} Spring Batch job-history table(s) skipped — no identity data`);
  }
  if (data.offline.length) notes.push(`offline: ${data.offline.join(', ')}`);
  $('search-meta').textContent = notes.join(' · ');

  if (!hits.length) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = 'No matches found.';
    box.appendChild(p);
  }

  for (const r of data.results) {
    const btn = document.createElement('button');
    btn.className = 'hit';
    const label = r.status !== 'ok'
      ? `<span class="ct warn">${r.status}</span>`
      : `<span class="ct">${fmt(r.hits)}${r.capped ? '+' : ''} rows</span>`;
    btn.innerHTML = `<span class="db">${r.database}</span><span class="nm">${r.name}</span>${label}`;
    btn.onclick = async () => {
      if (r.kind === 'objects') {
        await openBucket(r.table);
        state.oq = data.term;
        $('object-search').value = data.term;
        await loadObjects();
        return;
      }
      await openTable(r.database, r.schema, r.table);
      state.q = data.term;
      $('row-search').value = data.term;
      await loadRows();
    };
    box.appendChild(btn);
  }
}

/* ---------------- wiring ---------------- */

const debounce = (fn, ms) => {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
};

$('mode').onchange = () => {
  state.mode = $('mode').value;
  if (state.q && state.table) { state.page = 0; loadRows(); }
};

$('deep').onchange = () => {
  state.deep = $('deep').checked;
  if (state.q && state.table) { state.page = 0; loadRows(); }
};

$('global-search-form').onsubmit = (e) => {
  e.preventDefault();
  const term = $('global-search').value.trim();
  if (term) runSearch(term);
};

$('table-filter').oninput = debounce(renderSidebar, 120);

$('row-search').oninput = debounce(() => {
  state.q = $('row-search').value.trim();
  state.page = 0;
  loadRows();
}, 350);

$('page-size').onchange = () => {
  state.size = Number($('page-size').value);
  state.page = 0;
  loadRows();
};

$('object-search').oninput = debounce(() => {
  state.oq = $('object-search').value.trim();
  state.opage = 0;
  loadObjects();
}, 250);

$('object-page-size').onchange = () => {
  state.osize = Number($('object-page-size').value);
  state.opage = 0;
  loadObjects();
};

$('obj-prev').onclick = () => { if (state.opage > 0) { state.opage--; loadObjects(); } };
$('obj-next').onclick = () => { state.opage++; loadObjects(); };

$('prev').onclick = () => { if (state.page > 0) { state.page--; loadRows(); } };
$('next').onclick = () => { state.page++; loadRows(); };

$('refresh').onclick = async () => {
  state.tables = {};
  await loadOverview(true);
  for (const db of [...state.expanded]) {
    state.expanded.delete(db);
    await toggleDb(db);
  }
};

$('modal-close').onclick = () => { $('modal').hidden = true; };
$('modal').onclick = (e) => { if (e.target === $('modal')) $('modal').hidden = true; };
document.onkeydown = (e) => { if (e.key === 'Escape') $('modal').hidden = true; };

loadOverview().catch(fail);
