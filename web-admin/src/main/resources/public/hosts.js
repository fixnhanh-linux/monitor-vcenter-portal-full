'use strict';
// Trang Host ESXi & Datastore. Làm mới mỗi 20 giây.

const WARN = 80, CRIT = 90;
const $ = (id) => document.getElementById(id);
const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const collator = new Intl.Collator(undefined, { numeric: true });
function bytes(n, dec = 1) { if (!n || n < 0) return '0 B'; const u = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']; let i = 0; while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; } return `${n.toFixed(i ? dec : 0)} ${u[i]}`; }
const rate = (n) => bytes(n) + '/s';
const pct = (n) => `${(n ?? 0).toFixed(1)}%`;
const ghz = (mhz) => `${((mhz || 0) / 1000).toFixed(1)} GHz`;
const levelColor = (p, base) => (p >= CRIT ? css('--crit') : p >= WARN ? css('--warn') : css(base));
function duration(s) { s = Math.floor(s || 0); const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60); return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`; }
const vmPath = (id) => { const i = id.indexOf('/'); return `${encodeURIComponent(id.slice(0, i))}/${encodeURIComponent(id.slice(i + 1))}`; };

const state = { hosts: [], ds: [], f: { kind: 'hosts', site: '', status: '', q: '', sort: 'name' }, selected: null, series: null, detail: null };

async function api(path) {
  const res = await fetch('api/v1/' + path, { credentials: 'same-origin' });
  if (res.status === 401) { location.href = 'login?next=' + encodeURIComponent('/hosts' + location.hash); throw new Error('unauthorized'); }
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || `HTTP ${res.status}`);
  return res.json();
}

const HOST_STATUS = { ok: ['ok', 'Kết nối'], maintenance: ['warn', 'Bảo trì'], disconnected: ['err', 'Mất kết nối'], notResponding: ['err', 'Không phản hồi'], stale: ['off', 'Không có số liệu mới'] };
const SORTS = {
  hosts: [['name', 'Tên A→Z'], ['site', 'Site'], ['cpu', 'CPU cao nhất'], ['mem', 'RAM cao nhất'], ['vms', 'Nhiều VM nhất'], ['netRx', 'Network cao nhất']],
  ds: [['name', 'Tên A→Z'], ['site', 'Site'], ['percent', 'Đầy nhất'], ['free', 'Còn trống nhiều nhất'], ['-free', 'Còn trống ít nhất'], ['overProvisioned', 'Cấp phát nhiều nhất']],
};

function readHash() {
  const h = new URLSearchParams(location.hash.slice(1));
  Object.assign(state.f, { kind: ({ ds: 'ds', datastores: 'ds' })[h.get('kind')] || 'hosts', site: h.get('site') || '', status: h.get('status') || '', q: h.get('q') || '', sort: h.get('sort') || 'name' });
  $('q').value = state.f.q;
}
function writeHash() {
  const h = new URLSearchParams(); const f = state.f;
  if (f.kind !== 'hosts') h.set('kind', f.kind); if (f.site) h.set('site', f.site); if (f.status) h.set('status', f.status); if (f.q) h.set('q', f.q); if (f.sort !== 'name') h.set('sort', f.sort);
  history.replaceState(null, '', h.toString() ? '#' + h : location.pathname);
}
function setF(p) { Object.assign(state.f, p); writeHash(); render(); }

async function load() {
  const [h, d] = await Promise.all([api('hosts'), api('datastores')]);
  state.hosts = h.data; state.ds = d.data;
  render();
}

// vàng (alarm cảnh báo của vCenter) chỉ hiển thị chấm màu, không tính là vấn đề
const isProblemHost = (h) => (h.status !== 'ok' && h.status !== 'maintenance') || h.cpu >= CRIT || h.mem >= CRIT || h.health === 'red';
const isProblemDs = (d) => d.percent >= 85 || !d.accessible || d.stale;

function render() {
  const f = state.f;
  // site
  const sites = [...new Set([...state.hosts.map((h) => h.site), ...state.ds.map((d) => d.site)])].sort(collator.compare);
  const card = (key, label) => {
    const hs = state.hosts.filter((h) => !key || h.site === key), ds = state.ds.filter((d) => !key || d.site === key);
    const prob = hs.filter(isProblemHost).length + ds.filter(isProblemDs).length;
    return `<button class="site ${f.site === key ? 'on' : ''}" data-site="${esc(key)}"><span class="sn">${esc(label)} <span class="crit">${prob ? `<span class="pill warn">${prob} vấn đề</span>` : '<span class="pill ok">OK</span>'}</span></span>
      <span class="sm"><span><b>${hs.length}</b> host</span><span><b>${ds.length}</b> datastore</span></span></button>`;
  };
  $('sites').hidden = sites.length < 2;
  $('sites').innerHTML = card('', `Tất cả · ${sites.length} site`) + sites.map((s) => card(s, s)).join('');

  const hosts = state.hosts.filter((h) => !f.site || h.site === f.site);
  const dss = state.ds.filter((d) => !f.site || d.site === f.site);
  const ok = hosts.filter((h) => h.status === 'ok');
  const sum = (a, k) => a.reduce((x, y) => x + (y[k] || 0), 0);
  const cpuUsed = sum(ok, 'cpuUsedMhz'), cpuTot = sum(ok, 'cpuTotalMhz'), memUsed = sum(ok, 'memUsed'), memTot = sum(ok, 'memTotal');
  const cap = sum(dss, 'capacity'), free = sum(dss, 'free');
  $('stats').innerHTML = [
    ['Host kết nối', `${ok.length}<small> / ${hosts.length}${hosts.filter((h) => h.status === 'maintenance').length ? ` · ${hosts.filter((h) => h.status === 'maintenance').length} bảo trì` : ''}</small>`],
    ['CPU cụm', `${pct(cpuTot ? cpuUsed / cpuTot * 100 : 0)}<small> · ${ghz(cpuUsed)} / ${ghz(cpuTot)}</small>`],
    ['RAM cụm', `${pct(memTot ? memUsed / memTot * 100 : 0)}<small> · ${bytes(memUsed, 0)} / ${bytes(memTot, 0)}</small>`],
    ['VM đang chạy', `${sum(hosts, 'vmsRunning')}<small> / ${sum(hosts, 'vms')}</small>`],
    ['Datastore', `${pct(cap ? (cap - free) / cap * 100 : 0)}<small> · còn ${bytes(free, 0)} / ${bytes(cap, 0)}</small>`],
    ['Datastore ≥ 85%', `<span class="${dss.filter((d) => d.percent >= 85).length ? 'danger' : ''}">${dss.filter((d) => d.percent >= 85).length}</span><small> / ${dss.length}</small>`],
  ].map(([k, v]) => `<div class="stat"><div class="k">${k}</div><div class="v">${v}</div></div>`).join('');

  $('c-hosts').textContent = hosts.length; $('c-ds').textContent = dss.length;
  $('c-problem').textContent = f.kind === 'hosts' ? hosts.filter(isProblemHost).length : dss.filter(isProblemDs).length;
  document.querySelectorAll('#kind button').forEach((b) => b.classList.toggle('on', b.dataset.v === f.kind));
  document.querySelectorAll('#status button').forEach((b) => b.classList.toggle('on', b.dataset.v === f.status));
  const sorts = SORTS[f.kind];
  if (!sorts.some(([v]) => v === f.sort)) f.sort = 'name';
  $('sort').innerHTML = sorts.map(([v, l]) => `<option value="${v}" ${v === f.sort ? 'selected' : ''}>${l}</option>`).join('');

  const q = f.q.trim().toLowerCase();
  const sortFn = (key) => { const desc = key.startsWith('-'), k = key.replace(/^-/, ''); return (a, b) => { const c = typeof a[k] === 'number' ? (b[k] || 0) - (a[k] || 0) : collator.compare(String(a[k] ?? ''), String(b[k] ?? '')); return desc ? -c : c; }; };
  $('hostwrap').hidden = f.kind !== 'hosts'; $('dswrap').hidden = f.kind !== 'ds';
  let rows;
  if (f.kind === 'hosts') {
    rows = hosts.filter((h) => (!q || `${h.name} ${h.cluster} ${h.model} ${h.vendor} ${h.version}`.toLowerCase().includes(q)) && (!f.status || isProblemHost(h))).sort(sortFn(f.sort));
    $('hrows').innerHTML = rows.map(hostRow).join('');
  } else {
    rows = dss.filter((d) => (!q || `${d.name} ${d.type} ${d.hostNames.join(' ')}`.toLowerCase().includes(q)) && (!f.status || isProblemDs(d))).sort(sortFn(f.sort));
    $('drows').innerHTML = rows.map(dsRow).join('');
  }
  $('empty').hidden = rows.length > 0;
  $('empty').innerHTML = state.hosts.length ? 'Không có mục nào khớp bộ lọc.' : 'Chưa có số liệu host ESXi — collector sẽ gửi sau lượt lấy số liệu kế tiếp (khoảng 20 giây).';
  if (rows.length === 0) { $('hostwrap').hidden = true; $('dswrap').hidden = true; }
}

function bar(val, base, label) {
  return `<div class="cell2"><div class="cell-bar"><div class="bar"><div style="width:${Math.min(100, val || 0)}%;background:${levelColor(val || 0, base)}"></div></div><em>${pct(val)}</em></div>${label ? `<small>${label}</small>` : ''}</div>`;
}
function hostRow(h) {
  const st = HOST_STATUS[h.status] || ['err', h.status];
  const health = { green: 'green', yellow: 'yellow', red: 'red' }[h.health] || 'gray';
  const on = h.status === 'ok' || h.status === 'maintenance';
  return `<tr data-id="${esc(h.id)}" class="${isProblemHost(h) ? (h.status !== 'ok' && h.status !== 'maintenance' ? 'crit' : 'warn') : ''}">
    <td class="nm"><span class="health ${health}" title="${esc({ green: 'vCenter: bình thường', yellow: 'vCenter: có alarm mức cảnh báo (vàng)', red: 'vCenter: có alarm mức nghiêm trọng (đỏ) — xem Alarms của host trong vCenter' }[h.health] || 'vCenter: không rõ')}"></span><b>${esc(h.name)}</b></td>
    <td>${esc(h.site)}<div class="sub">${esc(h.cluster || '—')}</div></td>
    <td><span class="st ${st[0]}">${st[1]}</span></td>
    <td>${on ? bar(h.cpu, '--cpu', `${ghz(h.cpuUsedMhz)} / ${ghz(h.cpuTotalMhz)} · ${h.cores} core`) : '—'}</td>
    <td>${on ? bar(h.mem, '--mem', `${bytes(h.memUsed, 0)} / ${bytes(h.memTotal, 0)}`) : '—'}</td>
    <td><b>${h.vmsRunning}</b><span style="color:var(--muted)"> / ${h.vms}</span></td>
    <td>${on ? `${rate(h.netRx)} / ${rate(h.netTx)}` : '—'}</td>
    <td class="sub">${esc([h.vendor, h.model].filter(Boolean).join(' '))}</td>
    <td class="sub">${esc((h.version || '').replace('VMware ', ''))}</td>
    <td class="sub">${h.uptime ? duration(h.uptime) : '—'}</td></tr>`;
}
function dsRow(d) {
  const st = !d.accessible ? ['err', 'Không truy cập được'] : d.stale ? ['off', 'Không có số liệu mới'] : d.maintenance && d.maintenance !== 'normal' ? ['warn', 'Bảo trì'] : ['ok', 'Bình thường'];
  return `<tr class="${isProblemDs(d) ? (d.percent >= 95 || !d.accessible ? 'crit' : 'warn') : ''}" title="${esc(d.hostNames.join(', '))}">
    <td class="nm"><b>${esc(d.name)}</b></td><td>${esc(d.site)}</td><td>${esc(d.type)}</td>
    <td>${bar(d.percent, '--disk', `${bytes(d.used, 1)}`)}</td>
    <td>${bytes(d.capacity, 1)}</td><td><b>${bytes(d.free, 1)}</b></td>
    <td class="${d.overProvisioned > 100 ? 'danger' : ''}">${pct(d.overProvisioned)}<div class="sub">${bytes(d.provisioned, 1)}</div></td>
    <td>${d.hosts}</td><td>${d.vms}</td><td><span class="st ${st[0]}">${st[1]}</span></td></tr>`;
}

// ---------- chi tiết host ----------
async function openHost(id) {
  state.selected = id;
  $('overlay').classList.add('open'); $('drawer').classList.add('open');
  await fetchDetail();
}
async function fetchDetail() {
  const id = state.selected; if (!id) return;
  const [d, s] = await Promise.all([api('hosts/' + vmPath(id)), api(`hosts/${vmPath(id)}/metrics?range=${$('range').value}`)]);
  if (state.selected !== id) return;
  state.detail = d; state.series = s; renderDetail();
}
function closeHost() { state.selected = null; state.detail = null; $('overlay').classList.remove('open'); $('drawer').classList.remove('open'); }
function renderDetail() {
  const h = state.detail; if (!h) return;
  const st = HOST_STATUS[h.status] || ['err', h.status];
  $('d-title').textContent = h.name;
  $('d-status').innerHTML = `<span class="st ${st[0]}">${st[1]}</span>`;
  $('d-vms').href = `./#site=${encodeURIComponent(h.site)}&q=${encodeURIComponent(h.name)}`;
  $('d-info').innerHTML = [
    ['Site', h.site], ['Cluster', h.cluster || '—'], ['Datacenter', h.datacenter], ['vCenter', h.vcenter],
    ['CPU', `${ghz(h.cpuUsedMhz)} / ${ghz(h.cpuTotalMhz)} · ${h.cores} core / ${h.threads} luồng`], ['RAM', `${bytes(h.memUsed)} / ${bytes(h.memTotal)}`],
    ['VM', `${h.vmsRunning} đang chạy / ${h.vms}`], ['Phần cứng', [h.vendor, h.model].filter(Boolean).join(' ') || '—'],
    ['Phiên bản', h.version], ['Uptime', h.uptime ? duration(h.uptime) : '—'], ['Alarm vCenter', { green: 'Bình thường', yellow: 'Có alarm cảnh báo (vàng)', red: 'Có alarm nghiêm trọng (đỏ)' }[h.health] || '—'],
  ].map(([k, v]) => `<div><small>${k}</small>${esc(v)}</div>`).join('');
  const pts = state.series?.points || [];
  $('n-cpu').textContent = pct(h.cpu); $('n-mem').textContent = pct(h.mem);
  $('n-net').textContent = `↓${rate(h.netRx)} ↑${rate(h.netTx)}`; $('n-io').textContent = `R ${rate(h.diskRead)} W ${rate(h.diskWrite)}`;
  lineChart($('c-cpu'), pts, [{ k: 'cpu', c: '--cpu', fill: true }], { max: 100, fmt: (v) => v.toFixed(0) + '%', tip: pct });
  lineChart($('c-mem'), pts, [{ k: 'mem', c: '--mem', fill: true }], { max: 100, fmt: (v) => v.toFixed(0) + '%', tip: pct });
  lineChart($('c-net'), pts, [{ k: 'rx', c: '--rx', fill: true }, { k: 'tx', c: '--tx' }], { fmt: (v) => bytes(v, 0), tip: rate });
  lineChart($('c-io'), pts, [{ k: 'rd', c: '--rx', fill: true }, { k: 'wr', c: '--disk' }], { fmt: (v) => bytes(v, 0), tip: rate });
  $('d-ds').innerHTML = (h.datastores || []).sort((a, b) => b.percent - a.percent).map((d) => `<tr><td>${esc(d.name)}</td><td>${esc(d.type)}</td><td class="num">${bytes(d.capacity)}</td><td class="num">${bytes(d.free)}</td>
    <td><div class="cell-bar" style="grid-template-columns:1fr 50px"><div class="bar"><div style="width:${d.percent}%;background:${levelColor(d.percent, '--disk')}"></div></div><em>${pct(d.percent)}</em></div></td></tr>`).join('')
    || '<tr><td colspan="5" style="color:var(--muted)">Không có datastore</td></tr>';
}

// ---------- biểu đồ ----------
function setupCanvas(cv) {
  const dpr = window.devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
  if (cv.width !== w * dpr || cv.height !== h * dpr) { cv.width = w * dpr; cv.height = h * dpr; }
  const ctx = cv.getContext('2d'); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, w, h); return { ctx, w, h };
}
function niceMax(v) { if (v <= 0) return 1; const e = Math.pow(10, Math.floor(Math.log10(v))); for (const m of [1, 2, 2.5, 5, 10]) if (m * e >= v) return m * e; return 10 * e; }
function lineChart(cv, pts, series, opt) {
  const { ctx, w, h } = setupCanvas(cv); if (!w) return;
  const L = 58, R = 8, T = 8, B = 20, pw = w - L - R, ph = h - T - B;
  const max = opt.max || niceMax(Math.max(1, ...pts.flatMap((p) => series.map((s) => p[s.k] || 0))) * 1.1);
  const t1 = Date.now(), t0 = t1 - Number($('range').value) * 1000;
  const X = (t) => L + ((t - t0) / (t1 - t0)) * pw, Y = (v) => T + ph - (v / max) * ph;
  ctx.font = '11px system-ui, sans-serif'; ctx.fillStyle = css('--muted'); ctx.strokeStyle = css('--border'); ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) { const v = (max * i) / 4, y = Math.round(Y(v)) + 0.5; ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(w - R, y); ctx.stroke(); ctx.textAlign = 'right'; ctx.textBaseline = 'middle'; ctx.fillText(opt.fmt(v), L - 6, y); }
  ctx.textAlign = 'center'; ctx.textBaseline = 'top';
  for (let i = 0; i <= 4; i++) { const t = t0 + ((t1 - t0) * i) / 4; ctx.fillText(t1 - t0 > 86400000 ? new Date(t).toLocaleString([], { day: '2-digit', month: '2-digit', hour: '2-digit' }) : new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), Math.min(w - R - 18, Math.max(L + 18, X(t))), h - B + 5); }
  if (opt.max === 100) { ctx.setLineDash([4, 4]); ctx.strokeStyle = css('--crit'); ctx.globalAlpha = 0.5; ctx.beginPath(); ctx.moveTo(L, Y(CRIT)); ctx.lineTo(w - R, Y(CRIT)); ctx.stroke(); ctx.setLineDash([]); ctx.globalAlpha = 1; }
  cv._chart = { pts, series, X, opt };
  if (pts.length < 2) { ctx.fillText('Đang thu thập dữ liệu…', L + pw / 2, T + ph / 2); return; }
  for (const s of series) {
    const col = css(s.c);
    ctx.beginPath(); pts.forEach((p, i) => (i ? ctx.lineTo(X(p.ts), Y(p[s.k] || 0)) : ctx.moveTo(X(p.ts), Y(p[s.k] || 0))));
    if (s.fill) { ctx.save(); ctx.lineTo(X(pts[pts.length - 1].ts), T + ph); ctx.lineTo(X(pts[0].ts), T + ph); ctx.closePath(); ctx.fillStyle = col; ctx.globalAlpha = 0.12; ctx.fill(); ctx.restore();
      ctx.beginPath(); pts.forEach((p, i) => (i ? ctx.lineTo(X(p.ts), Y(p[s.k] || 0)) : ctx.moveTo(X(p.ts), Y(p[s.k] || 0)))); }
    ctx.strokeStyle = col; ctx.lineWidth = 1.8; ctx.stroke();
  }
}
document.querySelectorAll('.chart canvas').forEach((cv) => {
  cv.addEventListener('mousemove', (e) => {
    const c = cv._chart; if (!c || !c.pts.length) return;
    let best = c.pts[0], bd = Infinity; for (const p of c.pts) { const d = Math.abs(c.X(p.ts) - e.offsetX); if (d < bd) { bd = d; best = p; } }
    const names = { cpu: 'CPU', mem: 'RAM', rx: 'In', tx: 'Out', rd: 'Read', wr: 'Write' };
    $('tip').innerHTML = `<b>${new Date(best.ts).toLocaleString()}</b><br>` + c.series.map((s) => `<span style="color:${css(s.c)}">●</span> ${names[s.k]}: ${c.opt.tip(best[s.k] || 0)}`).join('<br>');
    Object.assign($('tip').style, { display: 'block', left: Math.min(innerWidth - 180, e.clientX + 12) + 'px', top: e.clientY + 12 + 'px' });
  });
  cv.addEventListener('mouseleave', () => { $('tip').style.display = 'none'; });
});

// ---------- sự kiện ----------
$('sites').onclick = (e) => { const b = e.target.closest('.site'); if (b) setF({ site: b.dataset.site }); };
$('kind').onclick = (e) => { const b = e.target.closest('button'); if (b) setF({ kind: b.dataset.v, status: '', sort: 'name' }); };
$('status').onclick = (e) => { const b = e.target.closest('button'); if (b) setF({ status: b.dataset.v }); };
$('sort').onchange = () => setF({ sort: $('sort').value });
let qt; $('q').oninput = () => { clearTimeout(qt); qt = setTimeout(() => setF({ q: $('q').value }), 200); };
$('hrows').onclick = (e) => { const tr = e.target.closest('tr[data-id]'); if (tr) openHost(tr.dataset.id); };
$('overlay').onclick = $('d-close').onclick = closeHost;
$('range').onchange = fetchDetail;
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeHost(); });
addEventListener('resize', renderDetail);

(async function init() {
  const me = await api('me');
  if (me.enroll) return location.replace('account?enroll=1');
  if (me.role === 'customer') return location.replace('my');
  if (me.role === 'manager') return location.replace('./');
  if (me.sites) $('grafana-link').style.display = 'none'; // Grafana có mọi site
  $('settings-link').hidden = me.role !== 'admin';
  // link Grafana: dashboard host hoặc datastore theo tab đang xem, giữ site đang lọc
  const gl = $('grafana-link'), gBase = (me.grafanaUrl || `${location.protocol}//${location.hostname}:3001`).replace(/\/+$/, '');
  const setGl = () => { const p = new URLSearchParams(location.hash.slice(1)), s = p.get('site');
    gl.href = `${gBase}/d/${['ds', 'datastores'].includes(p.get('kind')) ? 'vm-monitor-datastores' : 'vm-monitor-hosts'}${s ? `?var-site=${encodeURIComponent(s)}` : ''}`; };
  setGl(); addEventListener('hashchange', setGl); gl.addEventListener('mouseenter', setGl); gl.addEventListener('focus', setGl);
  readHash();
  await load();
  setInterval(() => { load().catch(() => {}); if (state.selected) fetchDetail().catch(() => {}); }, 20000);
  try { const es = new EventSource('api/v1/stream'); es.addEventListener('reload', () => setTimeout(() => location.reload(), 300)); } catch {}
})().catch((e) => { if (e.message !== 'unauthorized') { $('empty').hidden = false; $('empty').textContent = 'Không tải được dữ liệu: ' + e.message; } });
