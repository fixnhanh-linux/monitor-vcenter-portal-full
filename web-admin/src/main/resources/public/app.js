'use strict';
// VM Monitor Portal — dashboard. Dùng REST API v1 + Server-Sent Events.

const WARN = 80, CRIT = 90;
const PAGE_SIZE = { table: 100, cards: 48 };
const $ = (id) => document.getElementById(id);
const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();

const state = {
  me: null,
  vms: new Map(),            // id -> VM (dạng phẳng từ API) + spark
  alerts: { active: [], history: null, channels: [], rules: [] },
  f: { site: '', customer: '', status: '', q: '', sort: 'name', view: 'table', page: 0 },
  customers: [],
  alertTab: 'active',
  selected: null, detail: null, series: null,
};

// ---------- tiện ích ----------
function bytes(n, dec = 1) {
  if (!n || n < 0) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']; let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i ? dec : 0)} ${u[i]}`;
}
const rate = (n) => bytes(n) + '/s';
const pct = (n) => `${(n ?? 0).toFixed(1)}%`;
const levelColor = (p, base) => (p >= CRIT ? css('--crit') : p >= WARN ? css('--warn') : css(base));
function duration(s) {
  s = Math.floor(s || 0); const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`;
}
function ago(ts) {
  if (!ts) return '—';
  const s = Math.round((Date.now() - ts) / 1000);
  return s < 60 ? `${s}s trước` : s < 3600 ? `${Math.round(s / 60)} phút trước` : s < 86400 ? `${Math.round(s / 3600)} giờ trước` : `${Math.round(s / 86400)} ngày trước`;
}
function fmtTime(ts) {
  const d = new Date(ts), today = new Date().toDateString() === d.toDateString();
  return today ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : d.toLocaleString([], { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const vmPath = (id) => { const i = id.indexOf('/'); return `${encodeURIComponent(id.slice(0, i))}/${encodeURIComponent(id.slice(i + 1))}`; };
const collator = new Intl.Collator(undefined, { numeric: true });
function toast(html, ms = 5000) { const t = $('toast'); t.innerHTML = html; t.style.display = 'block'; clearTimeout(t._h); t._h = setTimeout(() => { t.style.display = 'none'; }, ms); }

async function api(path, opts = {}) {
  const res = await fetch('api/v1/' + path, { credentials: 'same-origin', ...opts, headers: { 'content-type': 'application/json', ...(opts.headers || {}) } });
  if (res.status === 401) { location.href = 'login?next=' + encodeURIComponent(location.pathname + location.hash); throw new Error('unauthorized'); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || `HTTP ${res.status}`), { status: res.status });
  return data;
}

// ---------- URL hash <-> bộ lọc ----------
function readHash() {
  const h = new URLSearchParams(location.hash.slice(1));
  state.f.site = h.get('site') || '';
  state.f.customer = h.get('customer') || '';
  state.f.status = h.get('status') || '';
  state.f.q = h.get('q') || '';
  state.f.sort = h.get('sort') || 'name';
  state.f.page = Math.max(0, Number(h.get('page') || 1) - 1);
  const v = h.get('view'); if (v) state.f.view = v;
  $('q').value = state.f.q; $('sort').value = state.f.sort;
}
function writeHash() {
  const h = new URLSearchParams();
  const { site, status, q, sort, page, view } = state.f;
  if (site) h.set('site', site); if (state.f.customer) h.set('customer', state.f.customer); if (status) h.set('status', status); if (q) h.set('q', q);
  if (sort !== 'name') h.set('sort', sort); if (page) h.set('page', page + 1); if (view !== defaultView()) h.set('view', view);
  const s = h.toString();
  history.replaceState(null, '', s ? '#' + s : location.pathname);
}
function defaultView() { return state.vms.size > 60 ? 'table' : 'cards'; }
function setFilter(patch, resetPage = true) {
  Object.assign(state.f, patch);
  if (resetPage) state.f.page = 0;
  writeHash(); render(); renderAlerts();
}

// ---------- dữ liệu ----------
function merge(v) {
  const prev = state.vms.get(v.id);
  const spark = prev?.spark || (v.spark || []).map(([ts, cpu, mem]) => ({ ts, cpu, mem }));
  if (prev && v.lastSeen !== prev.lastSeen && v.status !== 'off') { spark.push({ ts: v.lastSeen, cpu: v.cpu, mem: v.mem }); if (spark.length > 40) spark.shift(); }
  delete v.spark;
  state.vms.set(v.id, Object.assign(prev || {}, v, { spark }));
}
const alertsByVm = () => {
  if (!state._abv) { state._abv = new Map(); for (const a of state.alerts.active) { const l = state._abv.get(a.vmId) || []; l.push(a); state._abv.set(a.vmId, l); } }
  return state._abv;
};
const vmAlerts = (id) => alertsByVm().get(id) || [];
function setActiveAlerts(list) { state.alerts.active = list; state._abv = null; }

async function loadAll() {
  const [vms, al] = await Promise.all([api('vms?all=1&spark=1'), api('alerts')]);
  for (const v of vms.data) merge(v);
  setActiveAlerts(al.data);
  Object.assign(state.alerts, { channels: al.channels || [], rules: al.rules || [] });
  let saved = null; try { saved = localStorage.getItem('vm-view'); } catch {}
  if (!new URLSearchParams(location.hash.slice(1)).get('view')) state.f.view = saved || defaultView();
}

function connect() {
  const es = new EventSource('api/v1/stream');
  es.onopen = () => setLive(true);
  es.onerror = () => setLive(false);
  es.addEventListener('updates', (e) => {
    const list = JSON.parse(e.data);
    for (const v of list) {
      merge(v);
      if (state.selected === v.id && state.detail) onDetailUpdate(v);
    }
    scheduleRender();
  });
  es.addEventListener('status', (e) => {
    const { id, status } = JSON.parse(e.data); const v = state.vms.get(id);
    if (v) { v.status = status; scheduleRender(); }
    if (state.selected === id && state.detail) { state.detail.status = status; renderDetail(); }
  });
  es.addEventListener('removed', (e) => { state.vms.delete(JSON.parse(e.data).id); scheduleRender(); });
  es.addEventListener('alert', (e) => {
    const { event, active } = JSON.parse(e.data);
    setActiveAlerts(active);
    if (event && event.type !== 'repeat' && state.alerts.history) state.alerts.history.unshift(event);
    renderAlerts(); scheduleRender(); if (state.detail) renderDetail();
  });
  // có bản giao diện mới: tự tải lại trang, giữ nguyên bộ lọc và VM đang mở
  let uiVersion = null;
  es.addEventListener('hello', (e) => {
    const { version } = JSON.parse(e.data);
    if (uiVersion && version !== uiVersion) reloadKeepingState();
    uiVersion = version;
  });
  es.addEventListener('reload', () => reloadKeepingState());
  es.addEventListener('notify', (e) => {
    const failed = JSON.parse(e.data).results.filter((r) => !r.ok);
    if (failed.length) toast(`⚠ Gửi cảnh báo lỗi: ${failed.map((r) => `<b>${esc(r.channel)}</b>: ${esc(r.error)}`).join('; ')}`, 10000);
  });
}
function reloadKeepingState() {
  try { if (state.selected) sessionStorage.setItem('vm-open', state.selected); else sessionStorage.removeItem('vm-open'); } catch {}
  setTimeout(() => location.reload(), 300);
}
function setLive(on) { $('live').className = 'live' + (on ? ' on' : ''); $('live').querySelector('span').textContent = on ? 'Realtime' : 'Mất kết nối – đang thử lại…'; }

// vẽ lại tối đa 1 lần/giây
let renderTimer = 0, lastRender = 0;
function scheduleRender() {
  if (renderTimer) return;
  renderTimer = setTimeout(() => { renderTimer = 0; lastRender = Date.now(); requestAnimationFrame(render); }, Math.max(0, 1000 - (Date.now() - lastRender)));
}

// ---------- tổng quan ----------
// ---------- host ESXi / datastore (cho thẻ site + thẻ chỉ số) ----------
state.infra = { hosts: [], ds: [] };
const hostDown = (h) => h.status !== 'ok' && h.status !== 'maintenance';
async function loadInfra() {
  try {
    const [h, d] = await Promise.all([api('hosts'), api('datastores')]);
    state.infra = { hosts: h.data || [], ds: d.data || [] };
    scheduleRender();
  } catch {}
}
function infraSummary(site) {
  const hosts = state.infra.hosts.filter((h) => !site || h.site === site), ds = state.infra.ds.filter((d) => !site || d.site === site);
  const cap = ds.reduce((a, d) => a + d.capacity, 0), free = ds.reduce((a, d) => a + d.free, 0);
  return {
    hosts: hosts.length, hostsUp: hosts.filter((h) => !hostDown(h)).length, hostsDown: hosts.filter(hostDown).length,
    maint: hosts.filter((h) => h.status === 'maintenance').length,
    hostCpu: avgOf(hosts.filter((h) => h.status === 'ok'), 'cpu'), hostMem: avgOf(hosts.filter((h) => h.status === 'ok'), 'mem'),
    ds: ds.length, dsFull: ds.filter((d) => d.percent >= 85).length, dsDown: ds.filter((d) => !d.accessible).length,
    cap, free, dsPct: cap ? (1 - free / cap) * 100 : 0,
  };
}
const avgOf = (list, k) => (list.length ? list.reduce((a, x) => a + (x[k] || 0), 0) / list.length : 0);

// ---------- link Grafana (giữ site / khách hàng đang lọc) ----------
function grafanaBase() { return (state.me?.grafanaUrl || `${location.protocol}//${location.hostname}:3001`).replace(/\/+$/, ''); }
function updateGrafanaLink() {
  const a = $('grafana-link'); if (!a) return;
  const c = state.f.customer, p = new URLSearchParams();
  if (state.f.site) p.set('var-site', state.f.site);
  if (c && c !== '(none)') p.set('var-customer', c);
  a.href = `${grafanaBase()}/d/${c && c !== '(none)' ? 'vm-monitor-customers' : 'vm-monitor-overview'}?${p}`;
}

function siteSummary() {
  const map = new Map();
  for (const v of state.vms.values()) {
    let s = map.get(v.site);
    if (!s) { s = { site: v.site, vms: 0, online: 0, offline: 0, off: 0, cpu: 0, mem: 0, disk: 0, rx: 0, tx: 0, alerts: 0, critical: 0, cust: new Set() }; map.set(v.site, s); }
    s.vms++; s[v.status]++;
    if (v.customer) s.cust.add(v.customer);
    if (v.status === 'online') { s.cpu += v.cpu; s.mem += v.mem; s.disk += v.diskMax || 0; s.rx += v.netRx || 0; s.tx += v.netTx || 0; }
  }
  for (const a of state.alerts.active) { const s = map.get(a.site); if (s) { s.alerts++; if (a.severity === 'critical') s.critical++; } }
  return [...map.values()].sort((a, b) => collator.compare(a.site, b.site));
}

function renderSites() {
  const sites = siteSummary();
  const total = { vms: 0, online: 0, offline: 0, off: 0, cpu: 0, mem: 0, disk: 0, rx: 0, tx: 0, alerts: 0, critical: 0, cust: new Set() };
  for (const s of sites) { for (const k of Object.keys(total)) if (k !== 'cust') total[k] += s[k]; s.cust.forEach((c) => total.cust.add(c)); }
  const bar = (name, v, varName) => `<div>${name} ${pct(v)}<div class="mini"><i style="width:${Math.min(100, v)}%;background:${levelColor(v, varName)}"></i></div></div>`;
  const card = (key, label, s) => {
    const cpu = s.online ? s.cpu / s.online : 0, mem = s.online ? s.mem / s.online : 0, disk = s.online ? s.disk / s.online : 0;
    const inf = infraSummary(key);
    const badge = s.critical ? `<span class="pill crit">${s.critical} critical</span>` : s.alerts ? `<span class="pill warn">${s.alerts}</span>` : `<span class="pill ok">OK</span>`;
    const infra = inf.hosts || inf.ds ? `<span class="sm sm2">
        ${inf.hosts ? `<span title="Host ESXi đang kết nối / tổng${inf.maint ? ` · ${inf.maint} bảo trì` : ''}">Host <b>${inf.hostsUp}</b>/${inf.hosts}</span>${inf.hostsDown ? `<span class="danger">${inf.hostsDown} host lỗi</span>` : ''}` : ''}
        ${inf.ds ? `<span title="Datastore: % dung lượng đã dùng (tổng)">DS <b>${pct(inf.dsPct)}</b></span>${inf.dsFull ? `<span class="warn-t" title="Datastore dùng ≥ 85%">${inf.dsFull} DS đầy</span>` : ''}` : ''}
        ${s.cust.size ? `<span title="Số khách hàng có VM">${s.cust.size} khách</span>` : ''}</span>` : '';
    return `<button class="site ${state.f.site === key ? 'on' : ''}" data-site="${esc(key)}">
      <span class="sn">${esc(label)} <span class="crit">${badge}</span></span>
      <span class="sm"><span><b>${s.online}</b>/${s.vms} online</span>${s.offline ? `<span class="danger">${s.offline} mất kết nối</span>` : ''}${s.off ? `<span>${s.off} tắt</span>` : ''}</span>
      ${infra}
      <span class="sm sm2" title="Network tổng của VM đang chạy"><span>↓ ${rate(s.rx)}</span><span>↑ ${rate(s.tx)}</span></span>
      <span class="bars">${bar('CPU', cpu, '--cpu')}${bar('RAM', mem, '--mem')}${bar('Disk', disk, '--disk')}</span></button>`;
  };
  $('sites').hidden = sites.length < 2;
  $('sites').innerHTML = card('', `Tất cả · ${sites.length} site`, total) + sites.map((s) => card(s.site, s.site, s)).join('');
}

function scoped() {
  const c = state.f.customer;
  return [...state.vms.values()].filter((v) => (!state.f.site || v.site === state.f.site)
    && (!c || (c === '(none)' ? !v.customer : v.customer === c)));
}

function renderStats(list) {
  const on = list.filter((v) => v.status === 'online');
  const avg = (k) => (on.length ? on.reduce((a, v) => a + (v[k] || 0), 0) / on.length : 0);
  const sum = (k) => on.reduce((a, v) => a + (v[k] || 0), 0);
  const act = state.alerts.active.filter((a) => !state.f.site || a.site === state.f.site);
  const memTotal = on.reduce((a, v) => a + (v.memTotal || 0), 0), cores = on.reduce((a, v) => a + (v.cores || 0), 0);
  const cards = [
    ['VM online', `${on.length}<small> / ${list.length}</small>`],
    ['CPU trung bình', `${pct(avg('cpu'))}<small> · ${cores} vCPU</small>`],
    ['RAM trung bình', `${pct(avg('mem'))}<small> · ${bytes(memTotal, 0)}</small>`],
    ['Disk trung bình', pct(avg('diskMax'))],
    ['Network in / out', `${rate(sum('netRx'))}<small> ↑ ${rate(sum('netTx'))}</small>`],
    ['Cảnh báo', `<span class="${act.length ? 'danger' : ''}">${act.length}</span><small> · ${new Set(act.map((a) => a.vmId)).size} VM</small>`],
  ];
  const inf = infraSummary(state.f.site), site = state.f.site ? `&site=${encodeURIComponent(state.f.site)}` : '';
  const custs = new Set(list.map((v) => v.customer).filter(Boolean)).size, noCust = list.filter((v) => !v.customer).length;
  const extra = [];
  if (inf.hosts) extra.push(['Host ESXi', `${inf.hostsUp}<small> / ${inf.hosts}${inf.hostsDown ? ` · <span class="danger">${inf.hostsDown} lỗi</span>` : ''}${inf.maint ? ` · ${inf.maint} bảo trì` : ''}</small>`,
    `hosts#${site.slice(1)}${inf.hostsDown ? '&status=problem' : ''}`, 'Bấm để xem danh sách host']);
  if (inf.hosts) extra.push(['CPU / RAM host', `${pct(inf.hostCpu)}<small> · RAM ${pct(inf.hostMem)}</small>`, `hosts#sort=cpu${site}`, 'Trung bình các host đang kết nối']);
  if (inf.ds) extra.push(['Datastore', `${pct(inf.dsPct)}<small> · trống ${bytes(inf.free, 0)} / ${bytes(inf.cap, 0)}${inf.dsFull ? ` · <span class="warn-t">${inf.dsFull} ≥85%</span>` : ''}${inf.dsDown ? ` · <span class="danger">${inf.dsDown} lỗi</span>` : ''}</small>`,
    `hosts#kind=ds${site}&sort=percent`, `${inf.ds} datastore · bấm để xem chi tiết`]);
  extra.push(['Khách hàng', `${custs}<small> · ${noCust} VM chưa gán</small>`, null, 'Số khách hàng có VM trong phạm vi đang lọc']);
  $('stats').innerHTML = cards.map(([k, v]) => `<div class="stat"><div class="k">${k}</div><div class="v">${v}</div></div>`).join('')
    + extra.map(([k, v, href, tip]) => `<${href ? `a href="${href}"` : 'div'} class="stat${href ? ' link' : ''}" title="${esc(tip)}"><div class="k">${k}</div><div class="v">${v}</div></${href ? 'a' : 'div'}>`).join('');
}

const SORT_KEY = {
  name: (v) => v.name, site: (v) => `${v.site}/${v.name}`, customer: (v) => `${v.customer || '~'}/${v.name}`,
  cpu: (v) => v.cpu, mem: (v) => v.mem, disk: (v) => v.diskMax, net: (v) => v.netRx + v.netTx,
};
function filtered(list) {
  const { status, q, sort, customer } = state.f, ql = q.trim().toLowerCase();
  const out = list.filter((v) => (!customer || (customer === '(none)' ? !v.customer : v.customer === customer))
    && (!ql || `${v.name} ${v.ip} ${v.host} ${v.cluster} ${v.os} ${v.customer || ''}`.toLowerCase().includes(ql))
    && (!status || (status === 'alert' ? vmAlerts(v.id).length > 0 : v.status === status)));
  const key = SORT_KEY[sort] || SORT_KEY.name, text = sort === 'name' || sort === 'site' || sort === 'customer';
  return out.sort(text ? (a, b) => collator.compare(key(a), key(b)) : (a, b) => (key(b) || 0) - (key(a) || 0));
}

function render() {
  renderSites();
  updateGrafanaLink();
  const list = scoped();
  renderStats(list);
  const counts = { all: list.length, online: 0, offline: 0, off: 0, alert: 0 };
  for (const v of list) { counts[v.status]++; if (vmAlerts(v.id).length) counts.alert++; }
  for (const k of Object.keys(counts)) $('c-' + k).textContent = counts[k];
  document.querySelectorAll('#status button').forEach((b) => b.classList.toggle('on', b.dataset.v === state.f.status));
  document.querySelectorAll('#view button').forEach((b) => b.classList.toggle('on', b.dataset.v === state.f.view));
  document.querySelectorAll('.vmtable th[data-sort]').forEach((th) => th.classList.toggle('sorted', th.dataset.sort === state.f.sort));

  const rows = filtered(list);
  const empty = $('empty');
  if (!rows.length) {
    $('tablewrap').hidden = true; $('grid').hidden = true; $('pager').innerHTML = ''; empty.hidden = false;
    empty.innerHTML = state.vms.size ? 'Không có VM khớp bộ lọc.' : `<h3>Chưa có VM nào gửi số liệu</h3>
      <p>Lấy từ vCenter: <code>python vcenter_collector.py</code></p>
      <p>Hoặc cài agent trên VM: <code>node agent.js --server ${esc(location.origin)} --site TEN-SITE</code></p>`;
    return;
  }
  empty.hidden = true;
  renderBulk(rows);
  const size = PAGE_SIZE[state.f.view] || 100, pages = Math.ceil(rows.length / size);
  state.f.page = Math.min(state.f.page, pages - 1);
  const shown = rows.slice(state.f.page * size, (state.f.page + 1) * size);
  renderPager(pages, rows.length, size);

  if (state.f.view === 'table') {
    $('grid').hidden = true; $('grid').innerHTML = ''; $('tablewrap').hidden = false;
    $('vmrows').innerHTML = shown.map(rowHtml).join('');
    $('pick-page').checked = shown.length > 0 && shown.every((v) => state.picked.has(v.id));
  } else {
    $('tablewrap').hidden = true; $('grid').hidden = false;
    renderCards(shown);
  }
}

function cellBar(val, base) {
  return `<div class="cell-bar"><div class="bar"><div style="width:${val || 0}%;background:${levelColor(val || 0, base)}"></div></div><em>${pct(val)}</em></div>`;
}
function statusTag(v) {
  return v.status === 'off' ? `<span class="tag-off">${v.power === 'suspended' ? 'tạm dừng' : 'tắt nguồn'}</span>`
    : v.status === 'offline' ? `<span class="tag-lost">mất kết nối · ${ago(v.lastSeen)}</span>` : '';
}
// ---------- chọn nhiều VM để gán khách hàng loạt (admin) ----------
state.picked = new Set();
const canPick = () => state.me?.role === 'admin';
function renderBulk(rows) {
  state.lastRows = rows;
  const n = state.picked.size;
  $('bulk').hidden = !n;
  $('pick-th').hidden = !canPick();
  if (!n) return;
  $('bulk-n').textContent = `Đã chọn ${n} VM`;
  const unpicked = rows.filter((v) => !state.picked.has(v.id)).length;
  $('bulk-all').hidden = !unpicked;
  $('bulk-all').textContent = `Chọn cả ${rows.length} VM đang lọc`;
}
async function bulkAssign(customer) {
  const ids = [...state.picked];
  $('bulk-msg').className = 'msg'; $('bulk-msg').textContent = 'Đang lưu…';
  try {
    await api('admin/customers/assign', { method: 'PUT', body: JSON.stringify({ ids, customer }) });
    // cập nhật ngay trên bảng (dữ liệu đầy đủ đến ở lần cập nhật kế tiếp)
    const fresh = await api('vms?all=1');
    for (const v of fresh.data) { const o = state.vms.get(v.id); if (o) { o.customer = v.customer; o.customerSource = v.customerSource; } }
    state.picked.clear(); $('bulk-cust').value = '';
    toast(customer ? `✓ Đã gán ${ids.length} VM cho <b>${esc(customer)}</b>` : `✓ Đã bỏ gán tay ${ids.length} VM`);
    loadCustomers(); render();
    $('bulk-msg').textContent = '';
  } catch (e) { $('bulk-msg').className = 'msg err'; $('bulk-msg').textContent = '✗ ' + e.message; }
}
$('bulk-assign').onclick = () => { const c = $('bulk-cust').value.trim(); if (!c) { $('bulk-cust').focus(); return; } bulkAssign(c); };
$('bulk-cust').onkeydown = (e) => { if (e.key === 'Enter') $('bulk-assign').click(); };
$('bulk-clear').onclick = () => { if (confirm(`Bỏ gán tay ${state.picked.size} VM? VM sẽ quay về khách được nhận diện tự động (nếu có).`)) bulkAssign(''); };
$('bulk-all').onclick = () => { for (const v of state.lastRows || []) state.picked.add(v.id); render(); };
$('bulk-none').onclick = () => { state.picked.clear(); render(); };
$('pick-page').onchange = (e) => {
  document.querySelectorAll('#vmrows tr[data-id]').forEach((tr) => { if (e.target.checked) state.picked.add(tr.dataset.id); else state.picked.delete(tr.dataset.id); });
  render();
};

function rowHtml(v) {
  const al = vmAlerts(v.id), lv = al.some((a) => a.severity === 'critical') ? 'crit' : al.length ? 'warn' : '';
  const on = v.status === 'online';
  return `<tr class="${v.status} ${lv}${state.picked.has(v.id) ? ' picked' : ''}" data-id="${esc(v.id)}" title="${esc(al.map((a) => a.text).join('\n'))}">
    ${canPick() ? `<td class="pickc"><input type="checkbox" class="pick" ${state.picked.has(v.id) ? 'checked' : ''} aria-label="Chọn"></td>` : ''}
    <td class="nm"><span class="dot"></span><b>${esc(v.name)}</b>${al.length ? ` <span class="sev ${lv === 'crit' ? 'critical' : 'warning'}">⚠ ${al.length}</span>` : ''}${statusTag(v)}</td>
    <td class="ch" title="${esc(v.customer || '')}">${v.customer ? esc(v.customer) : '<span style="color:var(--muted)">—</span>'}</td>
    <td>${esc(v.site)}</td><td>${esc(v.ip || '—')}</td>
    <td class="ch" title="${esc([v.cluster, v.host].filter(Boolean).join(' / '))}">${esc([v.cluster, v.host].filter(Boolean).join(' / ') || '—')}</td>
    <td>${on ? cellBar(v.cpu, '--cpu') : '—'}</td><td>${on ? cellBar(v.mem, '--mem') : '—'}</td><td>${v.diskTotal ? cellBar(v.diskMax, '--disk') : '—'}</td>
    <td>${on ? `${rate(v.netRx)} / ${rate(v.netTx)}` : '—'}</td><td>${on ? `${rate(v.diskRead)} / ${rate(v.diskWrite)}` : '—'}</td>
    <td class="os" title="${esc(v.os)}">${esc(v.os || '')}</td></tr>`;
}

function renderCards(shown) {
  const grid = $('grid');
  const existing = new Map([...grid.querySelectorAll('.card')].map((el) => [el.dataset.id, el]));
  shown.forEach((v, i) => {
    let el = existing.get(v.id);
    if (!el) {
      el = document.createElement('div'); el.className = 'card'; el.dataset.id = v.id;
      el.innerHTML = `<div class="card-head"><span class="dot"></span><b class="nm"></b><span class="tag"></span></div><div class="sub"></div>
        ${['CPU', 'RAM', 'Disk'].map((l) => `<div class="meter m-${l}"><span>${l}</span><div class="bar"><div></div></div><em></em></div>`).join('')}
        <div class="net"><div>↓ In<b class="rx"></b></div><div>↑ Out<b class="tx"></b></div></div><canvas class="spark"></canvas>`;
    }
    existing.delete(v.id);
    const al = vmAlerts(v.id), lv = al.some((a) => a.severity === 'critical') ? 'crit' : al.length ? 'warn' : '';
    el.className = `card ${v.status} ${lv}`;
    el.querySelector('.nm').textContent = v.name;
    el.querySelector('.tag').textContent = v.status === 'off' ? 'tắt nguồn' : v.status === 'offline' ? `mất kết nối · ${ago(v.lastSeen)}` : `${v.site} · ${v.cores || '?'} vCPU · ${bytes(v.memTotal, 0)}`;
    el.querySelector('.sub').textContent = [v.ip, v.cluster, v.os].filter(Boolean).join(' · ');
    for (const [l, val, base] of [['CPU', v.cpu, '--cpu'], ['RAM', v.mem, '--mem'], ['Disk', v.diskMax, '--disk']]) {
      const m = el.querySelector('.m-' + l), bar = m.querySelector('.bar > div');
      bar.style.width = `${val || 0}%`; bar.style.background = levelColor(val || 0, base);
      m.querySelector('em').textContent = pct(val);
    }
    el.querySelector('.rx').textContent = rate(v.netRx);
    el.querySelector('.tx').textContent = rate(v.netTx);
    let badge = el.querySelector('.badge');
    if (al.length) { if (!badge) { badge = document.createElement('span'); el.appendChild(badge); } badge.className = 'badge' + (lv === 'warn' ? ' warning' : ''); badge.textContent = `⚠ ${al.length}`; }
    else if (badge) badge.remove();
    if (grid.children[i] !== el) grid.insertBefore(el, grid.children[i] || null);
    drawSpark(el.querySelector('.spark'), v.spark || []);
  });
  existing.forEach((el) => el.remove());
}

function renderPager(pages, total, size) {
  const page = state.f.page;
  if (pages <= 1) { $('pager').innerHTML = `<span>${total} VM</span>`; return; }
  const nums = new Set([0, pages - 1, page - 2, page - 1, page, page + 1, page + 2].filter((n) => n >= 0 && n < pages));
  let last = -1, html = `<button data-p="${page - 1}" ${page === 0 ? 'disabled' : ''} aria-label="Trang trước">‹</button>`;
  for (const n of [...nums].sort((a, b) => a - b)) {
    if (n - last > 1) html += '<span>…</span>';
    html += `<button data-p="${n}" class="${n === page ? 'cur' : ''}">${n + 1}</button>`;
    last = n;
  }
  html += `<button data-p="${page + 1}" ${page >= pages - 1 ? 'disabled' : ''} aria-label="Trang sau">›</button>`;
  html += `<span>${page * size + 1}–${Math.min(total, (page + 1) * size)} / ${total} VM</span>`;
  $('pager').innerHTML = html;
}

// ---------- cảnh báo ----------
const SEV_LABEL = { critical: 'Critical', warning: 'Warning', info: 'Info', resolved: 'Đã hết' };
function alertItems(list, { empty, limit = 200, click = true } = {}) {
  if (!list.length) return `<div class="alert-empty">${empty}</div>`;
  const items = list.slice(0, limit).map((a) => {
    const sev = a.type === 'resolved' ? 'resolved' : a.severity;
    const when = a.type === 'resolved' || state.alertTab === 'history' ? fmtTime(a.ts) : 'từ ' + fmtTime(a.since);
    const infra = a.kind === 'host' || a.kind === 'datastore';
    const href = infra ? `hosts#${new URLSearchParams({ ...(a.kind === 'datastore' ? { kind: 'ds' } : {}), site: a.site, q: a.vm })}` : '';
    return `<li class="${click && (infra || state.vms.has(a.vmId)) ? 'click' : ''}" data-id="${esc(a.vmId)}" ${href ? `data-href="${esc(href)}"` : ''}><span class="sev ${sev}">${SEV_LABEL[sev] || sev}</span>
      <span>${esc(a.text)}${a.type === 'resolved' && a.duration ? ` <span style="color:var(--muted)">· kéo dài ${duration(a.duration)}</span>` : ''}</span><time>${when}</time></li>`;
  }).join('');
  return `<ul class="alert-list">${items}</ul>${list.length > limit ? `<div class="alert-more">… và ${list.length - limit} cảnh báo nữa (lọc theo site hoặc dùng API /api/v1/alerts)</div>` : ''}`;
}
function renderAlerts() {
  const site = state.f.site;
  const act = state.alerts.active.filter((a) => !site || a.site === site);
  const crit = act.filter((a) => a.severity === 'critical').length;
  $('a-count').textContent = act.length;
  $('a-count').className = 'count' + (!act.length ? ' zero' : !crit ? ' warnonly' : '');
  $('a-channels').innerHTML = ['telegram', 'email', 'webhook'].map((c) => { const on = state.alerts.channels.includes(c); return `<span class="chip ${on ? 'on' : ''}" title="${on ? 'Đã bật' : 'Chưa bật trong config.json'}">${on ? '●' : '○'} ${c}</span>`; }).join('');
  document.querySelectorAll('#a-tabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === state.alertTab));
  if (state.alertTab === 'active') {
    const sorted = [...act].sort((a, b) => (a.severity === 'critical' ? 0 : 1) - (b.severity === 'critical' ? 0 : 1) || b.since - a.since);
    $('a-body').innerHTML = alertItems(sorted, { empty: `✓ Không có cảnh báo${site ? ` ở site ${esc(site)}` : ''} (${state.alerts.rules.length} rule đang theo dõi).` });
  } else if (!state.alerts.history) {
    $('a-body').innerHTML = '<div class="alert-empty">Đang tải…</div>';
    api('alerts?state=history&limit=500').then((r) => { state.alerts.history = r.data; renderAlerts(); }).catch(() => {});
  } else {
    $('a-body').innerHTML = alertItems(state.alerts.history.filter((a) => !site || a.site === site), { empty: 'Chưa có sự kiện cảnh báo.' });
  }
}

// ---------- chi tiết VM ----------
async function openDetail(id) {
  state.selected = id;
  $('overlay').classList.add('open'); $('drawer').classList.add('open'); $('drawer').setAttribute('aria-hidden', 'false');
  const v = state.vms.get(id);
  $('d-title').textContent = v ? v.name : id;
  await fetchDetail();
}
async function fetchDetail() {
  const id = state.selected; if (!id) return;
  try {
    const [d, s] = await Promise.all([api('vms/' + vmPath(id)), api(`vms/${vmPath(id)}/metrics?${TW.query()}`)]);
    if (state.selected !== id) return;
    state.detail = d; state.series = s; renderDetail();
  } catch (e) { if (e.status === 404) { toast('VM không còn trên portal'); closeDetail(); } }
}
let mountsFetchedAt = 0;
async function refreshDetailExtras() {
  // phân vùng ổ đĩa + cảnh báo không có trong luồng realtime: lấy lại tối đa 15 giây/lần
  const id = state.selected; if (!id || Date.now() - mountsFetchedAt < 15000) return;
  mountsFetchedAt = Date.now();
  try {
    const d = await api('vms/' + vmPath(id));
    if (state.selected === id && state.detail) { Object.assign(state.detail, { mounts: d.mounts, activeAlerts: d.activeAlerts, load: d.load }); renderDetail(); }
  } catch {}
}
function onDetailUpdate(v) {
  Object.assign(state.detail, v);
  refreshDetailExtras();
  if (state.series?.resolution === 'raw' && v.status !== 'off' && TW.live()) {
    state.series.points.push({ ts: v.lastSeen, cpu: v.cpu, mem: v.mem, disk: v.disk, rx: v.netRx, tx: v.netTx, rd: v.diskRead, wr: v.diskWrite });
  }
  renderDetail();
}
function closeDetail() {
  state.selected = null; state.detail = null; state.series = null;
  $('overlay').classList.remove('open'); $('drawer').classList.remove('open'); $('drawer').setAttribute('aria-hidden', 'true');
}
function renderDetail() {
  const d = state.detail; if (!d) return;
  $('d-title').textContent = d.name;
  const stText = { online: 'Online', offline: 'Mất kết nối · ' + ago(d.lastSeen), off: d.power === 'suspended' ? 'Tạm dừng' : 'Tắt nguồn' }[d.status];
  $('d-status').className = 'live ' + (d.status === 'online' ? 'on' : d.status === 'off' ? 'off' : '');
  $('d-status').innerHTML = `<i></i><span>${stText}</span>`;
  $('d-info').innerHTML = [
    ['Khách hàng', d.customer ? `${d.customer}${d.customerSourceLabel ? ` (${d.customerSourceLabel})` : ''}` : 'chưa xác định'],
    ['Site', d.site], ['IP', d.ip], ['Hostname', d.hostname], ['Hệ điều hành', d.os],
    ['Cluster', d.cluster], ['Host ESXi', d.host], ['Datacenter', d.datacenter], ['Nguồn dữ liệu', d.source === 'vcenter' ? 'vCenter' : 'Agent'],
    ['vCPU', d.cores], [d.memSource === 'active' ? 'RAM (active)' : 'RAM', d.memTotal ? `${bytes(d.memUsed)} / ${bytes(d.memTotal)}` : '—'],
    [d.diskSource === 'guest' ? 'Disk (df)' : 'Disk', d.diskTotal ? `${bytes(d.diskUsed)} / ${bytes(d.diskTotal)}` : '—'], ['Uptime', d.uptime ? duration(d.uptime) : '—'],
    ['Cập nhật', ago(d.lastSeen)], ...(d.load && d.load.some((x) => x) ? [['Load avg', d.load.map((x) => x.toFixed(2)).join(' · ')]] : []),
  ].filter(([, v]) => v !== undefined && v !== '' && v !== null).map(([k, v]) => `<div><small>${k}</small>${esc(v)}</div>`).join('');
  if (document.activeElement !== $('d-cust-in')) $('d-cust-in').value = d.customerSource === 'manual' ? d.customer : '';
  $('d-cust-in').placeholder = d.customer && d.customerSource !== 'manual' ? `Đang tự nhận là "${d.customer}" — nhập để gán tay` : 'Tên khách hàng';
  const al = vmAlerts(d.id);
  $('d-alerts').innerHTML = al.length ? `<div class="panel-head"><h2>Cảnh báo đang hoạt động</h2></div>${alertItems(al, { click: false })}` : '';
  const vc = d.source === 'vcenter';
  $('d-note').hidden = !vc;
  if (vc) {
    const ram = d.memSource === 'guest'
      ? 'ℹ <b>RAM</b> đọc trong VM (<code>/proc/meminfo</code>, giống lệnh <code>free</code>). '
      : 'ℹ <b>RAM</b> lấy từ vCenter là bộ nhớ hoạt động (active memory) mà ESXi đo được, thường thấp hơn RAM đã dùng trong hệ điều hành. ';
    $('d-note').innerHTML = d.diskSource === 'guest'
      ? ram + '<b>Disk</b> đọc trực tiếp trong VM bằng <code>df</code> qua Guest Operations: khớp với hệ điều hành (cập nhật mỗi ~15 phút).'
      : ram + '<b>Disk</b>: "Còn trống" luôn khớp cột Avail của <code>df</code>; "Đã dùng" = dung lượng − còn trống. '
        + 'Với Linux ext4 còn bật vùng dự trữ cho root (mặc định 5%), "Đã dùng" sẽ cao hơn cột Used của <code>df</code> đúng bằng vùng đó; '
        + 'chạy <code>tune2fs -m 0</code> (hoặc bật Guest Operations) thì khớp <code>df</code>, chỉ còn lệch 16 MiB.';
  }
  $('d-api').textContent =`${location.origin}${location.pathname.replace(/[^/]*$/, '')}api/v1/vms/${vmPath(d.id)}`;

  const [w0, w1] = TW.win(), pts = (state.series?.points || []).filter((p) => p.ts >= w0 && p.ts <= w1);
  $('n-cpu').textContent = pct(d.cpu);
  $('n-mem').textContent = pct(d.mem);
  $('n-net').textContent = `↓${rate(d.netRx)} ↑${rate(d.netTx)}`;
  $('n-io').textContent = `R ${rate(d.diskRead)} W ${rate(d.diskWrite)}`;
  lineChart($('c-cpu'), pts, [{ k: 'cpu', c: '--cpu', fill: true }], { max: 100, fmt: (v) => v.toFixed(0) + '%', tip: pct });
  lineChart($('c-mem'), pts, [{ k: 'mem', c: '--mem', fill: true }], { max: 100, fmt: (v) => v.toFixed(0) + '%', tip: pct });
  lineChart($('c-net'), pts, [{ k: 'rx', c: '--rx', fill: true }, { k: 'tx', c: '--tx' }], { fmt: (v) => bytes(v, 0), tip: rate });
  lineChart($('c-io'), pts, [{ k: 'rd', c: '--rx', fill: true }, { k: 'wr', c: '--disk' }], { fmt: (v) => bytes(v, 0), tip: rate });

  $('d-mounts').innerHTML = (d.mounts || []).map((m) => `<tr><td>${esc(m.mount)}</td><td class="num">${bytes(m.total)}</td><td class="num">${bytes(m.used)}</td><td class="num">${bytes(m.avail ?? m.total - m.used)}</td>
    <td><div class="cell-bar" style="grid-template-columns:1fr 50px"><div class="bar"><div style="width:${m.percent}%;background:${levelColor(m.percent, '--disk')}"></div></div><em>${pct(m.percent)}</em></div></td></tr>`).join('')
    || `<tr><td colspan="5" style="color:var(--muted)">Không có dữ liệu phân vùng${d.source === 'vcenter' ? ' (cần VMware Tools trong VM)' : ''}</td></tr>`;
}

// ---------- biểu đồ (canvas) ----------
function setupCanvas(cv) {
  const dpr = window.devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
  if (cv.width !== w * dpr || cv.height !== h * dpr) { cv.width = w * dpr; cv.height = h * dpr; }
  const ctx = cv.getContext('2d'); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, w, h);
  return { ctx, w, h };
}
function drawSpark(cv, pts) {
  const { ctx, w, h } = setupCanvas(cv);
  if (pts.length < 2 || !w) return;
  for (const [k, c] of [['mem', '--mem'], ['cpu', '--cpu']]) {
    ctx.beginPath();
    pts.forEach((p, i) => { const x = (i / (pts.length - 1)) * w, y = h - 2 - ((p[k] || 0) / 100) * (h - 4); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
    ctx.strokeStyle = css(c); ctx.lineWidth = 1.5; ctx.globalAlpha = k === 'cpu' ? 1 : 0.55; ctx.stroke();
  }
  ctx.globalAlpha = 1;
}
function niceMax(v) { if (v <= 0) return 1; const e = Math.pow(10, Math.floor(Math.log10(v))); for (const m of [1, 2, 2.5, 5, 10]) if (m * e >= v) return m * e; return 10 * e; }
function lineChart(cv, pts, series, opt) {
  const { ctx, w, h } = setupCanvas(cv);
  if (!w) return;
  const L = 58, R = 8, T = 8, B = 20, pw = w - L - R, ph = h - T - B;
  const max = opt.max || niceMax(Math.max(1, ...pts.flatMap((p) => series.map((s) => p[s.k] || 0))) * 1.1);
  const [t0, t1] = TW.win();
  const X = (t) => L + ((t - t0) / (t1 - t0)) * pw, Y = (v) => T + ph - (v / max) * ph;
  TW.mark(cv, t0, t1, L / w, (w - R) / w);
  ctx.font = '11px system-ui, sans-serif'; ctx.fillStyle = css('--muted'); ctx.strokeStyle = css('--border'); ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const v = (max * i) / 4, y = Math.round(Y(v)) + 0.5;
    ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(w - R, y); ctx.stroke();
    ctx.textAlign = 'right'; ctx.textBaseline = 'middle'; ctx.fillText(opt.fmt(v), L - 6, y);
  }
  ctx.textAlign = 'center'; ctx.textBaseline = 'top';
  for (let i = 0; i <= 4; i++) {
    const t = t0 + ((t1 - t0) * i) / 4;
    const label = t1 - t0 > 86400000 ? new Date(t).toLocaleString([], { day: '2-digit', month: '2-digit', hour: '2-digit' }) : new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    ctx.fillText(label, Math.min(w - R - 18, Math.max(L + 18, X(t))), h - B + 5);
  }
  if (opt.max === 100) { ctx.setLineDash([4, 4]); ctx.strokeStyle = css('--crit'); ctx.globalAlpha = 0.5; const y = Y(CRIT); ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(w - R, y); ctx.stroke(); ctx.setLineDash([]); ctx.globalAlpha = 1; }
  cv._chart = { pts, series, X, opt };
  if (pts.length < 2) { ctx.fillText('Đang thu thập dữ liệu…', L + pw / 2, T + ph / 2); return; }
  const gap = Math.max(...pts.slice(1).map((p, i) => p.ts - pts[i].ts).sort((a, b) => a - b).slice(0, Math.ceil(pts.length / 2)), 1) * 4;
  const path = (k) => { ctx.beginPath(); pts.forEach((p, i) => { const move = i === 0 || p.ts - pts[i - 1].ts > gap; move ? ctx.moveTo(X(p.ts), Y(p[k] || 0)) : ctx.lineTo(X(p.ts), Y(p[k] || 0)); }); };
  for (const s of series) {
    const col = css(s.c);
    if (s.fill) {
      ctx.beginPath(); pts.forEach((p, i) => (i ? ctx.lineTo(X(p.ts), Y(p[s.k] || 0)) : ctx.moveTo(X(p.ts), Y(p[s.k] || 0))));
      ctx.lineTo(X(pts[pts.length - 1].ts), T + ph); ctx.lineTo(X(pts[0].ts), T + ph); ctx.closePath();
      ctx.fillStyle = col; ctx.globalAlpha = 0.12; ctx.fill(); ctx.globalAlpha = 1;
    }
    path(s.k); ctx.strokeStyle = col; ctx.lineWidth = 1.8; ctx.lineJoin = 'round'; ctx.stroke();
  }
}
document.querySelectorAll('.chart canvas').forEach((cv) => {
  cv.addEventListener('mousemove', (e) => {
    const c = cv._chart; if (!c || !c.pts.length) return;
    let best = c.pts[0], bd = Infinity;
    for (const p of c.pts) { const d = Math.abs(c.X(p.ts) - e.offsetX); if (d < bd) { bd = d; best = p; } }
    const names = { cpu: 'CPU', mem: 'RAM', rx: 'In', tx: 'Out', rd: 'Read', wr: 'Write' };
    $('tip').innerHTML = `<b>${new Date(best.ts).toLocaleString()}</b><br>` + c.series.map((s) => `<span style="color:${css(s.c)}">●</span> ${names[s.k]}: ${c.opt.tip(best[s.k] || 0)}`).join('<br>');
    Object.assign($('tip').style, { display: 'block', left: Math.min(innerWidth - 180, e.clientX + 12) + 'px', top: e.clientY + 12 + 'px' });
  });
  cv.addEventListener('mouseleave', () => { $('tip').style.display = 'none'; });
});

// ---------- sự kiện ----------
let qTimer;
$('q').oninput = () => { clearTimeout(qTimer); qTimer = setTimeout(() => setFilter({ q: $('q').value }), 200); };
$('sort').onchange = () => setFilter({ sort: $('sort').value });
$('cust').onchange = () => setFilter({ customer: $('cust').value });
async function loadCustomers() {
  try {
    const r = await api('customers');
    state.customers = r.data.filter((x) => x.customer);
    const cur = state.f.customer;
    $('cust').innerHTML = `<option value="">Tất cả khách hàng (${r.total})</option><option value="(none)">— Chưa xác định khách (${r.unassigned}) —</option>`
      + state.customers.slice().sort((a, b) => collator.compare(a.customer, b.customer)).map((x) => `<option value="${esc(x.customer)}">${esc(x.customer)} (${x.vms})</option>`).join('');
    $('cust').value = cur;
    $('cust-list').innerHTML = state.customers.map((x) => `<option value="${esc(x.customer)}">`).join('');
  } catch {}
}
async function assignCustomer(customer) {
  const d = state.detail; if (!d) return;
  $('d-cust-msg').textContent = 'Đang lưu…';
  try {
    await api('admin/customers/assign', { method: 'PUT', body: JSON.stringify({ ids: [d.id], customer }) });
    $('d-cust-msg').textContent = customer ? `✓ Đã gán cho ${customer}` : '✓ Đã bỏ gán tay';
    await fetchDetail(); const v = state.vms.get(d.id); if (v) v.customer = state.detail.customer; loadCustomers(); render();
  } catch (e) { $('d-cust-msg').textContent = '✗ ' + e.message; }
}
$('d-cust-save').onclick = () => assignCustomer($('d-cust-in').value.trim());
$('d-cust-clear').onclick = () => assignCustomer('');
$('d-cust-in').onkeydown = (e) => { if (e.key === 'Enter') assignCustomer($('d-cust-in').value.trim()); };
$('status').onclick = (e) => { const b = e.target.closest('button'); if (b) setFilter({ status: b.dataset.v }); };
$('view').onclick = (e) => { const b = e.target.closest('button'); if (!b) return; try { localStorage.setItem('vm-view', b.dataset.v); } catch {} setFilter({ view: b.dataset.v }); };
$('sites').onclick = (e) => { const b = e.target.closest('.site'); if (b) setFilter({ site: b.dataset.site }); };
$('pager').onclick = (e) => { const b = e.target.closest('button[data-p]'); if (b && !b.disabled) { setFilter({ page: Number(b.dataset.p) }, false); scrollTo({ top: document.querySelector('.toolbar').offsetTop - 70 }); } };
$('vmrows').onclick = (e) => {
  const tr = e.target.closest('tr[data-id]'); if (!tr) return;
  if (e.target.closest('.pickc')) {
    const id = tr.dataset.id, box = tr.querySelector('.pick');
    if (e.target !== box) box.checked = !box.checked;
    if (box.checked) state.picked.add(id); else state.picked.delete(id);
    return render();
  }
  openDetail(tr.dataset.id);
};
$('grid').onclick = (e) => { const c = e.target.closest('.card'); if (c) openDetail(c.dataset.id); };
document.querySelectorAll('.vmtable th[data-sort]').forEach((th) => { th.onclick = () => { $('sort').value = th.dataset.sort; setFilter({ sort: th.dataset.sort }); }; });
$('csv').onclick = () => {
  const p = new URLSearchParams({ format: 'csv', sort: state.f.sort });
  if (state.f.site) p.set('site', state.f.site); if (state.f.status) p.set('status', state.f.status); if (state.f.q) p.set('q', state.f.q);
  location.href = 'api/v1/vms?' + p;
};
$('a-tabs').onclick = (e) => { const b = e.target.closest('button'); if (!b) return; state.alertTab = b.dataset.tab; if (b.dataset.tab === 'history') state.alerts.history = null; renderAlerts(); };
$('a-body').onclick = (e) => { const li = e.target.closest('li.click'); if (!li) return; if (li.dataset.href) location.href = li.dataset.href; else openDetail(li.dataset.id); };
$('a-toggle').onclick = () => { const c = $('alerts').classList.toggle('collapsed'); $('a-toggle').textContent = c ? '▸' : '▾'; try { localStorage.setItem('vm-alerts-collapsed', c ? '1' : ''); } catch {} };
$('a-test').onclick = async () => {
  $('a-test').disabled = true;
  try {
    const r = await api('alerts/test', { method: 'POST' });
    toast(r.results.map((x) => (x.ok ? `✓ <b>${x.channel}</b>: đã gửi` : `✗ <b>${x.channel}</b>: ${esc(x.error)}`)).join('<br>'), 8000);
  } catch (e) { toast('⚠ ' + esc(e.message)); } finally { $('a-test').disabled = false; }
};
TW.init($('range'), () => fetchDetail());
$('overlay').onclick = $('d-close').onclick = closeDetail;
$('d-remove').onclick = async () => {
  const d = state.detail; if (!d || !confirm(`Xoá "${d.name}" (site ${d.site}) khỏi portal?\nVM sẽ xuất hiện lại nếu agent / collector vẫn gửi số liệu.`)) return;
  try { await api('vms/' + vmPath(d.id), { method: 'DELETE' }); closeDetail(); } catch (e) { toast('⚠ ' + esc(e.message)); }
};
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDetail(); if (e.key === '/' && document.activeElement.tagName !== 'INPUT') { e.preventDefault(); $('q').focus(); } });
addEventListener('hashchange', () => { readHash(); render(); renderAlerts(); });
addEventListener('resize', () => { render(); renderDetail(); });
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { render(); renderDetail(); });
setInterval(() => { if (state.detail) { if (state.series?.resolution !== 'raw') fetchDetail(); else renderDetail(); } }, 60000);
setInterval(scheduleRender, 15000); // cập nhật "x phút trước"

function renderUser() {
  const me = state.me;
  document.querySelectorAll('.admin-only').forEach((el) => { el.hidden = me.role !== 'admin'; });
  $('user').innerHTML = me.via === 'open'
    ? `<a class="role" href="login" title="Portal chưa có tài khoản: bấm để tạo tài khoản admin và bật đăng nhập">⚠ chưa bật đăng nhập · tạo tài khoản</a>`
    : `<a href="account" title="Tài khoản của tôi: đổi mật khẩu, xác thực 2 lớp"><b>${esc(me.user)}</b></a><span class="role">${({ admin: 'admin', read: me.sites ? 'nhân viên · ' + me.sites.join(', ') : 'nhân viên', manager: 'phụ trách ' + (me.customers || []).length + ' khách', customer: 'khách hàng' })[me.role] || me.role}</span>${me.via === 'session' ? '<a href="#" id="logout">Đăng xuất</a>' : ''}`;
  // tài khoản khách hàng: chỉ thấy VM của mình, không có trang hạ tầng / Grafana
  // nhân viên phụ trách khách: không có trang hạ tầng; Grafana chứa mọi dữ liệu nên chỉ nhân viên không giới hạn mới thấy
  if (me.role === 'manager' || me.role === 'customer') document.querySelectorAll('.navtabs a[href="hosts"]').forEach((a) => a.remove());
  if (me.customers || me.sites) document.querySelectorAll('#grafana-link').forEach((a) => a.remove());
  const lo = $('logout'); if (lo) lo.onclick = async (e) => { e.preventDefault(); await fetch('api/v1/logout', { method: 'POST' }); location.href = 'login'; };
}

// ---------- khởi động ----------
(async function init() {
  try { if (localStorage.getItem('vm-alerts-collapsed')) { $('alerts').classList.add('collapsed'); $('a-toggle').textContent = '▸'; } } catch {}
  state.me = await api('me');
  if (state.me.enroll) return location.replace('account?enroll=1');
  if (state.me.role === 'customer') return location.replace('my'); // khách hàng dùng trang riêng
  renderUser();
  readHash();
  await loadAll();
  readHash();
  render(); renderAlerts();
  connect();
  loadCustomers(); setInterval(loadCustomers, 60000);
  loadInfra(); setInterval(loadInfra, 30000);
  // mở lại VM đang xem trước khi trang tự tải lại
  let reopen = null; try { reopen = sessionStorage.getItem('vm-open'); sessionStorage.removeItem('vm-open'); } catch {}
  if (reopen && state.vms.has(reopen)) openDetail(reopen);
})().catch((e) => { if (e.message !== 'unauthorized') toast('⚠ Không tải được dữ liệu: ' + esc(e.message), 10000); });
