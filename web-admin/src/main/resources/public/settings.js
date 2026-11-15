'use strict';
// Trang Cài đặt — quản lý vCenter, cảnh báo, kênh gửi, InfluxDB, người dùng & API key.

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const csv = (a) => (Array.isArray(a) ? a.join(', ') : a || '');
let S = null;         // settings hiện tại
let editingVc = null; // id vCenter đang sửa, 'new' khi thêm

async function api(path, opts = {}) {
  const res = await fetch('api/v1/' + path, { credentials: 'same-origin', ...opts, headers: { 'content-type': 'application/json' }, body: opts.body ? JSON.stringify(opts.body) : undefined });
  if (res.status === 401) { location.href = 'login?next=' + encodeURIComponent('/settings' + location.hash); throw new Error('unauthorized'); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}
function toast(html, ms = 4000) { const t = $('toast'); t.innerHTML = html; t.style.display = 'block'; clearTimeout(t._h); t._h = setTimeout(() => { t.style.display = 'none'; }, ms); }
function msg(id, text, ok = true) { const el = $(id); el.textContent = text; el.className = 'msg ' + (ok ? 'ok' : 'err'); if (ok) setTimeout(() => { if (el.textContent === text) el.textContent = ''; }, 5000); }
async function run(btn, msgId, fn) {
  btn.disabled = true;
  try { const r = await fn(); if (r !== false) msg(msgId, typeof r === 'string' ? r : '✓ Đã lưu'); }
  catch (e) { if (e.message !== 'unauthorized') msg(msgId, '✗ ' + e.message, false); }
  finally { btn.disabled = false; }
}
function ago(ts) { if (!ts) return '—'; const s = Math.round((Date.now() - ts) / 1000); return s < 60 ? `${s}s trước` : s < 3600 ? `${Math.round(s / 60)} phút trước` : `${Math.round(s / 3600)} giờ trước`; }

// ---------- tabs ----------
function showTab(t) {
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('on', b.dataset.t === t));
  document.querySelectorAll('.tab').forEach((s) => s.classList.toggle('on', s.id === 't-' + t));
  history.replaceState(null, '', '#' + t);
}
$('tabs').onclick = (e) => { const b = e.target.closest('button'); if (b) showTab(b.dataset.t); };

// ---------- vCenter ----------
const STATE = {
  ok: ['ok', 'Đang chạy'], connecting: ['warn', 'Đang kết nối'], error: ['err', 'Lỗi'], auth_failed: ['err', 'Sai mật khẩu'],
  no_password: ['warn', 'Chưa có mật khẩu'], disabled: ['off', 'Đã tắt'], portal_error: ['warn', 'Lỗi gửi portal'],
};
function vcCard(v) {
  const s = v.status;
  const colName = v.collector || 'máy chạy portal';
  const col = v.collector ? S.collectors.find((c) => c.name === v.collector) : S.collectors.find((c) => c.local);
  const collectorAlive = s ? s.collectorAlive : !!col?.alive;
  let st = s ? STATE[s.state] || ['off', s.state] : !v.enabled ? ['off', 'Đã tắt'] : collectorAlive ? ['warn', 'Chờ collector nhận'] : ['err', `Collector "${colName}" chưa chạy`];
  if (s && !collectorAlive) st = ['err', `Collector "${colName}" mất kết nối`];
  const g = v.guestOps, gs = s?.guest;
  return `<div class="vc" data-id="${esc(v.id)}">
    <div class="vc-h"><b>${esc(v.host)}</b><span class="st ${st[0]}">${st[1]}</span>
      <span class="sp"></span>
      <button data-act="toggle">${v.enabled ? 'Tắt' : 'Bật'}</button><button data-act="edit">Sửa</button><button data-act="del">Xoá</button></div>
    <div class="vc-meta">
      <span>Collector <b>${esc(colName)}</b></span>
      <span>Tài khoản <b>${esc(v.user)}</b></span>
      <span>Site <b>${esc(v.site === 'auto' ? (s?.datacenters?.join(', ') || 'auto (theo Datacenter)') : v.site)}</b></span>
      <span>Mật khẩu <b>${v.hasPassword ? 'lưu trên portal' : s && s.state !== 'no_password' ? 'lưu trên máy collector' : 'chưa có'}</b></span>
      ${s?.vms != null && s.state !== 'no_password' && s.state !== 'disabled' ? `<span>VM <b>${s.vms}</b> (${s.running ?? 0} đang chạy)</span><span>Cập nhật <b>${ago(s.lastCycle)}</b></span>` : ''}
      ${s?.version ? `<span>${esc(s.version)}</span>` : ''}
      <span>Đọc trong VM <b>${g.enabled ? `bật · ${esc(csv(g.include) || 'chưa chọn VM')}${gs ? ` · ${gs.ok} đọc được${gs.auth ? `, ${gs.auth} sai mật khẩu` : ''}${gs.err ? `, ${gs.err} lỗi` : ''}` : ''}` : 'tắt'}</b></span>
    </div>
    ${s && ['error', 'auth_failed', 'no_password', 'portal_error'].includes(s.state) && s.message ? `<div class="vc-err">${esc(s.message)}</div>` : ''}
    ${gs && gs.errors && Object.keys(gs.errors).length ? `<div class="vc-err">Đọc trong VM: ${esc(Object.keys(gs.errors).join(' · '))}</div>` : ''}
  </div>`;
}
function renderVc() {
  $('vc-list').innerHTML = S.vcenters.length ? S.vcenters.map(vcCard).join('') : '<div class="empty">Chưa có vCenter / host ESXi nào. Bấm <b>＋ Thêm vCenter / host ESXi</b>.</div>';
  const cols = S.collectors.length ? S.collectors : [{ name: 'local', local: true, alive: false, vcenters: S.vcenters.filter((v) => !v.collector).length }];
  $('collectors').innerHTML = cols.map((c) => `<tr>
    <td><b>${esc(c.local ? 'Máy chạy portal' : c.name)}</b>${c.local ? `<div class="sm" style="color:var(--muted);font-size:12px">nhận các vCenter không gán collector</div>` : ''}</td>
    <td>${c.alive ? '<span class="st ok">Đang chạy</span>' : c.lastSeen ? '<span class="st err">Mất kết nối</span>' : '<span class="st warn">Chưa chạy lần nào</span>'}</td>
    <td class="sm">${esc([c.host, c.ip].filter(Boolean).join(' · ') || '—')}</td>
    <td>${c.vcenters}</td>
    <td class="sm">${c.lastSeen ? ago(c.lastSeen) : '—'}</td>
    <td style="text-align:right">${c.local ? '' : `<button data-col-guide="${esc(c.name)}">Hướng dẫn</button> <button data-col-del="${esc(c.name)}">Xoá</button>`}</td></tr>`).join('');
}
$('vc-list').onclick = async (e) => {
  const b = e.target.closest('button'); if (!b) return;
  const id = b.closest('.vc').dataset.id, v = S.vcenters.find((x) => x.id === id);
  if (b.dataset.act === 'edit') return openVc(v);
  if (b.dataset.act === 'del') {
    if (!confirm(`Xoá vCenter ${v.host}?\nCollector sẽ ngừng lấy số liệu từ vCenter này (VM đã có trên portal sẽ chuyển sang mất kết nối).`)) return;
    try { await api('admin/vcenters/' + encodeURIComponent(id), { method: 'DELETE' }); toast('Đã xoá ' + esc(v.host)); load(); } catch (err) { toast('✗ ' + esc(err.message)); }
  }
  if (b.dataset.act === 'toggle') {
    try { await api('admin/vcenters/' + encodeURIComponent(id), { method: 'PUT', body: { ...vcBody(v), enabled: !v.enabled } }); load(); } catch (err) { toast('✗ ' + esc(err.message)); }
  }
};
function vcBody(v) {
  return { collector: v.collector, host: v.host, port: v.port, user: v.user, site: v.site, interval: v.interval, include: v.include, exclude: v.exclude, prefix: v.prefix,
    insecure: v.insecure, sendPoweredOff: v.sendPoweredOff, enabled: v.enabled,
    guestOps: { enabled: v.guestOps.enabled, user: v.guestOps.user, include: v.guestOps.include, intervalSeconds: v.guestOps.intervalSeconds } };
}
function openVc(v) {
  editingVc = v ? v.id : 'new';
  const f = $('vc-form');
  $('vc-title').textContent = v ? `Sửa ${v.host}` : 'Thêm vCenter / host ESXi';
  const remote = S.collectors.filter((c) => !c.local).map((c) => c.name);
  if (v?.collector && !remote.includes(v.collector)) remote.push(v.collector);
  $('vc-collector').innerHTML = `<option value="">Máy chạy portal (mặc định)</option>` + remote.map((n) => `<option value="${esc(n)}">${esc(n)}</option>`).join('');
  f.collector.value = v?.collector || '';
  f.host.value = v?.host || ''; f.port.value = v?.port || 443; f.user.value = v?.user || ''; f.password.value = '';
  f.site.value = v?.site || 'auto'; f.interval.value = v?.interval || 20; f.include.value = csv(v?.include || ['*']); f.exclude.value = csv(v?.exclude || ['vCLS-*']);
  f.prefix.value = v?.prefix || ''; f.insecure.checked = v ? v.insecure : true; f.sendPoweredOff.checked = v ? v.sendPoweredOff : true; f.enabled.checked = v ? v.enabled : true;
  f.g_enabled.checked = !!v?.guestOps.enabled; f.g_user.value = v?.guestOps.user || 'root'; f.g_password.value = '';
  f.g_include.value = csv(v?.guestOps.include || []); f.g_interval.value = v?.guestOps.intervalSeconds || 60;
  $('pw-hint').textContent = v?.hasPassword ? '(đã lưu — để trống nếu không đổi)' : v ? '(chưa lưu trên portal — đang dùng mật khẩu trên máy collector nếu có)' : '';
  $('gpw-hint').textContent = v?.guestOps.hasPassword ? '(đã lưu — để trống nếu không đổi)' : '';
  $('vc-msg').textContent = '';
  $('vc-dlg').showModal();
}
$('vc-add').onclick = () => openVc(null);
document.querySelectorAll('[data-close]').forEach((b) => { b.onclick = () => $('vc-dlg').close(); });
$('vc-form').onsubmit = async (e) => {
  e.preventDefault();
  const f = e.target;
  if (f.g_enabled.checked && !f.g_include.value.trim()) { msg('vc-msg', '✗ Bật đọc trong VM thì phải nhập tên VM', false); return; }
  const body = {
    collector: f.collector.value, host: f.host.value, port: f.port.value, user: f.user.value, password: f.password.value, site: f.site.value, interval: f.interval.value,
    include: f.include.value, exclude: f.exclude.value, prefix: f.prefix.value, insecure: f.insecure.checked, sendPoweredOff: f.sendPoweredOff.checked, enabled: f.enabled.checked,
    guestOps: { enabled: f.g_enabled.checked, user: f.g_user.value, password: f.g_password.value, include: f.g_include.value, intervalSeconds: f.g_interval.value },
  };
  await run($('vc-save'), 'vc-msg', async () => {
    await api('admin/vcenters/' + encodeURIComponent(editingVc), { method: 'PUT', body });
    $('vc-dlg').close();
    toast('✓ Đã lưu. Collector sẽ áp dụng trong khoảng 30 giây — theo dõi trạng thái ngay trên trang này.', 6000);
    load();
    return false;
  });
};

// ---------- collector ở site xa ----------
function guideHtml(name, key) {
  const portal = location.origin;
  const cfg = JSON.stringify({ collector: { name, portal, token: key || '<API key của collector này>' } }, null, 2);
  const env = `MONITOR_SERVER=${portal}\nMONITOR_TOKEN=${key || '<API key của collector này>'}\nMONITOR_INSECURE=${location.protocol === 'https:' ? '1' : '0'}\nTZ=Asia/Ho_Chi_Minh`;
  return `<p style="margin:0 0 8px">Trên <b>một máy Linux / Windows có Docker trong mạng của site</b> (kết nối được tới vCenter của site và gọi ra được <code>${esc(portal)}</code>):</p>
    <ol style="margin:0;padding-left:20px;line-height:1.7;font-size:13px">
      <li>Lấy image agent: trên máy portal chạy <code>powershell -File agent-docker\\build-image.ps1</code>, mang 2 file <code>agent-docker\\vm-monitor-agent.tar</code> và <code>agent-docker\\docker-compose.yml</code> sang máy đó rồi <code>docker load -i vm-monitor-agent.tar</code>.</li>
      <li>Tạo file <code>.env</code> cạnh <code>docker-compose.yml</code>:<div class="keybox" style="white-space:pre">${esc(env)}</div></li>
      <li>Chạy: <code>docker compose up -d</code> · xem log: <code>docker compose logs -f</code></li>
      <li>Quay lại trang này: collector <b>${esc(name)}</b> chuyển sang <b>Đang chạy</b>. Thêm / sửa vCenter của site và chọn <b>Chạy bởi collector = ${esc(name)}</b>, nhập mật khẩu vCenter ngay trên web.</li>
    </ol>
    <details style="margin-top:8px;font-size:12.5px"><summary>Không dùng Docker (chạy Python trực tiếp)</summary>
      <ol style="margin:6px 0 0;padding-left:20px;line-height:1.7">
        <li><code>pip install pyvmomi</code>, copy <code>vcenter_collector.py</code> + <code>guest_df.py</code> sang máy đó.</li>
        <li>Tạo <code>config.json</code> cạnh 2 file:<div class="keybox" style="white-space:pre">${esc(cfg)}</div></li>
        <li><code>python vcenter_collector.py</code> (Linux: systemd; Windows: <code>start_collector.ps1</code>).</li>
      </ol></details>
    <p style="margin:10px 0 0;color:var(--muted);font-size:12.5px">Portal phải gọi tới được từ site: đặt portal ở IP public / domain sau HTTPS (nginx, Caddy) hoặc qua VPN. Key này chỉ đọc được vCenter gán cho <b>${esc(name)}</b>.</p>`;
}
function openCollectorDlg(name) {
  const f = $('col-form');
  f.reset(); $('col-msg').textContent = '';
  $('col-step1').hidden = !!name; $('col-step2').hidden = !name; $('col-create').hidden = !!name;
  if (name) $('col-step2').innerHTML = guideHtml(name, null) + '<p style="margin:10px 0 0;font-size:12.5px">Key chỉ hiện một lần lúc tạo. Mất key thì <b>Xoá</b> collector rồi tạo lại.</p>';
  $('col-dlg').showModal();
}
$('col-add').onclick = () => openCollectorDlg(null);
document.querySelectorAll('[data-close-col]').forEach((b) => { b.onclick = () => $('col-dlg').close(); });
$('col-form').onsubmit = async (e) => {
  e.preventDefault();
  const name = e.target.colname.value.trim();
  if (!/^[A-Za-z0-9_-]{2,40}$/.test(name)) { msg('col-msg', '✗ Tên chỉ gồm chữ, số, - _ (2–40 ký tự)', false); return; }
  if (S.collectors.some((c) => c.name === name)) { msg('col-msg', '✗ Đã có collector tên này', false); return; }
  await run($('col-create'), 'col-msg', async () => {
    const r = await api('admin/apikeys', { method: 'POST', body: { name, role: 'collector' } });
    $('col-step1').hidden = true; $('col-create').hidden = true; $('col-step2').hidden = false;
    $('col-step2').innerHTML = `<p style="margin:0 0 10px"><b>Đã tạo collector "${esc(name)}".</b> API key chỉ hiện <b>một lần</b> — đã điền sẵn vào config bên dưới, hãy copy lại.</p>` + guideHtml(name, r.key);
    await load();
    return false;
  });
};
$('collectors').onclick = async (e) => {
  const g = e.target.dataset.colGuide, d = e.target.dataset.colDel;
  if (g) return openCollectorDlg(g);
  if (d) {
    if (!confirm(`Xoá collector "${d}"? API key của nó bị thu hồi, máy collector đó sẽ không lấy được cấu hình nữa.`)) return;
    try { await api('admin/collectors/' + encodeURIComponent(d), { method: 'DELETE' }); toast('Đã xoá collector ' + esc(d)); load(); } catch (err) { toast('✗ ' + esc(err.message), 6000); }
  }
};

// ---------- khách hàng ----------
const SRC_INFO = {
  manual: ['Gán tay trên portal', 'Gán trong trang chi tiết VM hoặc ô gán hàng loạt bên dưới'],
  rules: ['Quy tắc theo tên VM', 'Bảng quy tắc bên dưới'],
  tag: ['vSphere Tag', 'Tag thuộc category'],
  attribute: ['Custom Attribute', 'Tên attribute'],
  folder: ['Thư mục VM', 'Cấp thư mục (1 = ngay dưới gốc) và thư mục cha tuỳ chọn'],
  name: ['Trích từ tên VM', 'Regex, nhóm (…) đầu tiên là tên khách'],
  notes: ['Trích từ Notes', 'Regex, nhóm (…) đầu tiên là tên khách'],
};
let CU = { sources: [], rules: [], list: [] };
function srcRow(s, i, n) {
  const p = s.type === 'tag' ? `<input data-p="category" value="${esc(csv(s.category))}" placeholder="Customer">`
    : s.type === 'attribute' ? `<input data-p="name" value="${esc(csv(s.name))}" placeholder="Customer">`
    : s.type === 'folder' ? `<input data-p="level" type="number" min="1" value="${s.level || 1}" style="max-width:70px"> dưới <input data-p="prefix" value="${esc(s.prefix || '')}" placeholder="(gốc)" style="max-width:140px">`
    : s.type === 'name' || s.type === 'notes' ? `<input data-p="regex" value="${esc(s.regex || '')}" placeholder="^([A-Za-z]+)_\\d+$" style="font-family:ui-monospace,Consolas,monospace">` : '';
  return `<div class="vc" data-i="${i}" style="display:grid;grid-template-columns:auto 26px 1fr auto;gap:10px;align-items:center;margin-bottom:8px;padding:10px 12px">
    <label class="check"><input type="checkbox" data-p="enabled" ${s.enabled !== false ? 'checked' : ''}></label>
    <b style="color:var(--muted)">${i + 1}</b>
    <div><b>${SRC_INFO[s.type][0]}</b> <small style="color:var(--muted)">— ${SRC_INFO[s.type][1]}</small><div style="margin-top:${p ? 6 : 0}px;display:flex;gap:6px;align-items:center;flex-wrap:wrap">${p}</div></div>
    <div><button data-mv="-1" ${i === 0 ? 'disabled' : ''}>↑</button> <button data-mv="1" ${i === n - 1 ? 'disabled' : ''}>↓</button></div></div>`;
}
function readSources() {
  return [...$('cu-sources').children].map((el) => {
    const s = { ...CU.sources[Number(el.dataset.i)] };
    el.querySelectorAll('[data-p]').forEach((inp) => { s[inp.dataset.p] = inp.type === 'checkbox' ? inp.checked : inp.value; });
    return s;
  });
}
function renderSources() { $('cu-sources').innerHTML = CU.sources.map((s, i) => srcRow(s, i, CU.sources.length)).join(''); }
$('cu-sources').onclick = (e) => {
  const b = e.target.closest('button[data-mv]'); if (!b) return;
  CU.sources = readSources();
  const i = Number(b.closest('[data-i]').dataset.i), j = i + Number(b.dataset.mv);
  [CU.sources[i], CU.sources[j]] = [CU.sources[j], CU.sources[i]];
  renderSources();
};
function cuRuleRow(r = {}) {
  const tr = document.createElement('tr');
  tr.innerHTML = `<td><input data-k="customer" value="${esc(r.customer || '')}" placeholder="Tên khách hàng"></td><td><input data-k="vms" value="${esc(csv(r.vms))}" placeholder="KHE-*, *khache*"></td>
    <td><input data-k="sites" value="${esc(csv(r.sites))}" placeholder="tất cả"></td><td class="cnt" style="color:var(--muted)">${r._count ?? ''}</td><td><button class="ghost">✕</button></td>`;
  tr.querySelector('button').onclick = () => tr.remove();
  return tr;
}
function renderCustomers() {
  const c = S.customers || {};
  CU.sources = (c.sources || []).map((s) => ({ ...s }));
  for (const t of Object.keys(SRC_INFO)) if (!CU.sources.some((s) => s.type === t)) CU.sources.push({ type: t, enabled: false });
  renderSources();
  $('cu-rules').innerHTML = ''; (c.rules || []).forEach((r) => $('cu-rules').appendChild(cuRuleRow(r)));
  $('cu-manual').textContent = c.manualCount || 0;
  api('customers').then((r) => {
    $('cu-sum').textContent = `${r.total} khách · ${r.unassigned} VM chưa xác định`;
    const LBL = { manual: 'gán tay', rules: 'quy tắc', tag: 'tag', attribute: 'attribute', folder: 'thư mục', name: 'tên VM', notes: 'notes' };
    $('cu-list').innerHTML = r.data.map((x) => `<tr><td><b>${x.customer ? esc(x.customer) : '<i style="color:var(--muted)">(chưa xác định)</i>'}</b></td><td>${x.vms}</td><td>${x.online}</td><td>${x.off}</td>
      <td class="sm">${esc(x.sites.join(', '))}</td><td class="sm">${Object.entries(x.sources).map(([k, n]) => `${LBL[k] || k} ${n}`).join(', ')}</td></tr>`).join('');
  }).catch(() => {});
}
$('cu-rule-add').onclick = () => $('cu-rules').appendChild(cuRuleRow({}));
$('cu-save').onclick = () => run($('cu-save'), 'cu-msg', async () => {
  const rules = [...$('cu-rules').children].map((tr) => Object.fromEntries([...tr.querySelectorAll('[data-k]')].map((el) => [el.dataset.k, el.value])));
  await api('admin/customers', { method: 'PUT', body: { sources: readSources(), rules } });
  await load();
  return '✓ Đã lưu — dashboard áp dụng ngay';
});
$('cu-import-btn').onclick = () => run($('cu-import-btn'), 'cu-imp-msg', async () => {
  const lines = $('cu-import').value.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (!lines.length) throw new Error('Chưa có dòng nào');
  const vms = await fetch('api/v1/vms?all=1', { credentials: 'same-origin' }).then((r) => r.json());
  const byName = new Map(), byId = new Map(vms.data.map((v) => [v.id.toLowerCase(), v.id]));
  for (const v of vms.data) { const k = v.name.toLowerCase(); byName.set(k, [...(byName.get(k) || []), v.id]); }
  const groups = new Map(); const missing = [];
  for (const l of lines) {
    const m = l.match(/^(.+?)\s*[,;\t]\s*(.*)$/); const vm = (m ? m[1] : l).trim(), cust = (m ? m[2] : '').trim();
    const ids = byId.get(vm.toLowerCase()) ? [byId.get(vm.toLowerCase())] : byName.get(vm.toLowerCase());
    if (!ids) { missing.push(vm); continue; }
    groups.set(cust, [...(groups.get(cust) || []), ...ids]);
  }
  let n = 0;
  for (const [cust, ids] of groups) { await api('admin/customers/assign', { method: 'PUT', body: { ids, customer: cust } }); n += ids.length; }
  await load();
  if (missing.length) throw new Error(`Đã gán ${n} VM; không tìm thấy ${missing.length} VM: ${missing.slice(0, 5).join(', ')}${missing.length > 5 ? '…' : ''}`);
  $('cu-import').value = '';
  return `✓ Đã gán ${n} VM`;
});

// ---------- rule ----------
const METRICS_BY_TARGET = {
  vm: { cpu: 'CPU %', mem: 'RAM %', disk: 'Disk % (phân vùng đầy nhất)', rx: 'Network in (B/s)', tx: 'Network out (B/s)', rd: 'Disk read (B/s)', wr: 'Disk write (B/s)', offline: 'Mất kết nối' },
  host: { cpu: 'CPU %', mem: 'RAM %', rx: 'Network in (B/s)', tx: 'Network out (B/s)', disconnected: 'Mất kết nối', alarm: 'Alarm đỏ trên vCenter' },
  datastore: { disk: 'Đã dùng %', free_gb: 'Còn trống (GB)', provisioned: 'Đã cấp phát % (thin)', inaccessible: 'Không truy cập được' },
};
const NO_THRESHOLD = ['offline', 'disconnected', 'alarm', 'inaccessible'];
const metricOptions = (target, cur) => Object.entries(METRICS_BY_TARGET[target]).map(([k, l]) => `<option value="${k}" ${cur === k ? 'selected' : ''}>${l}</option>`).join('');
function ruleRow(r = {}) {
  const tr = document.createElement('tr');
  const target = r.target || 'vm';
  tr.innerHTML = `<td><select data-k="target"><option value="vm">VM</option><option value="host" ${target === 'host' ? 'selected' : ''}>Host ESXi</option><option value="datastore" ${target === 'datastore' ? 'selected' : ''}>Datastore</option></select></td>
    <td><select data-k="metric">${metricOptions(target, r.metric)}</select></td>
    <td><select data-k="op"><option value=">">&gt;</option><option value="<" ${r.op === '<' ? 'selected' : ''}>&lt;</option></select></td>
    <td><input data-k="threshold" type="number" step="any" value="${r.threshold ?? ''}"></td>
    <td><input data-k="for" type="number" min="0" value="${r.for ?? 0}"></td>
    <td><select data-k="severity">${['critical', 'warning', 'info'].map((s) => `<option ${r.severity === s ? 'selected' : ''}>${s}</option>`).join('')}</select></td>
    <td><input data-k="sites" value="${esc(csv(r.sites))}" placeholder="tất cả"></td>
    <td><input data-k="vms" value="${esc(csv(r.vms))}" placeholder="tất cả"></td>
    <td><input data-k="excludeVms" value="${esc(csv(r.excludeVms))}"></td>
    <td><button class="ghost" title="Xoá rule">✕</button></td>`;
  const sync = () => {
    const m = tr.querySelector('[data-k=metric]').value, off = NO_THRESHOLD.includes(m);
    tr.querySelector('[data-k=threshold]').disabled = off; tr.querySelector('[data-k=op]').disabled = off;
    if (m === 'free_gb' && !r._opTouched) tr.querySelector('[data-k=op]').value = '<';
    tr.querySelector('[data-k=vms]').placeholder = tr.querySelector('[data-k=target]').value === 'vm' ? 'tất cả' : 'tất cả (tên host / datastore)';
  };
  tr.querySelector('[data-k=target]').onchange = () => { tr.querySelector('[data-k=metric]').innerHTML = metricOptions(tr.querySelector('[data-k=target]').value); sync(); };
  tr.querySelector('[data-k=metric]').onchange = sync;
  tr.querySelector('[data-k=op]').onchange = () => { r._opTouched = true; };
  sync();
  tr.querySelector('button').onclick = () => tr.remove();
  return tr;
}
function renderRules() {
  const a = S.alerts;
  $('al-enabled').checked = a.enabled; $('al-resolved').checked = a.sendResolved; $('al-repeat').value = a.repeatMinutes;
  $('rules').innerHTML = ''; a.rules.forEach((r) => $('rules').appendChild(ruleRow(r)));
}
$('rule-add').onclick = () => $('rules').appendChild(ruleRow({ metric: 'cpu', threshold: 90, for: 120, severity: 'warning' }));
$('al-save').onclick = () => run($('al-save'), 'al-msg', async () => {
  const rules = [...$('rules').children].map((tr) => Object.fromEntries([...tr.querySelectorAll('[data-k]')].map((el) => [el.dataset.k, el.value])));
  await api('admin/alerts', { method: 'PUT', body: { enabled: $('al-enabled').checked, sendResolved: $('al-resolved').checked, repeatMinutes: $('al-repeat').value, rules } });
});

// ---------- kênh gửi ----------
function renderNotify() {
  const n = S.notify;
  $('tg-enabled').checked = n.telegram.enabled; $('tg-chat').value = n.telegram.chatId; $('tg-token').value = ''; $('tg-token').placeholder = n.telegram.hasBotToken ? '•••••• đã lưu (để trống = giữ nguyên)' : '';
  const m = n.email;
  $('em-enabled').checked = m.enabled; $('em-host').value = m.host; $('em-port').value = m.port; $('em-secure').checked = m.secure; $('em-user').value = m.user;
  $('em-pass').value = ''; $('em-pass').placeholder = m.hasPass ? '•••••• đã lưu (để trống = giữ nguyên)' : ''; $('em-from').value = m.from; $('em-fromname').value = m.fromName; $('em-to').value = m.to;
  $('wh-enabled').checked = n.webhook.enabled; $('wh-format').value = n.webhook.format; $('wh-url').value = ''; $('wh-url').placeholder = n.webhook.hasUrl ? `${n.webhook.urlHint} đã lưu (để trống = giữ nguyên)` : 'https://hooks.slack.com/services/…';
}
const notifyBody = () => ({
  telegram: { enabled: $('tg-enabled').checked, botToken: $('tg-token').value, chatId: $('tg-chat').value },
  email: { enabled: $('em-enabled').checked, host: $('em-host').value, port: $('em-port').value, secure: $('em-secure').checked, user: $('em-user').value, pass: $('em-pass').value, from: $('em-from').value, fromName: $('em-fromname').value, to: $('em-to').value },
  webhook: { enabled: $('wh-enabled').checked, format: $('wh-format').value, url: $('wh-url').value },
});
$('nt-save').onclick = () => run($('nt-save'), 'nt-msg', async () => { await api('admin/notify', { method: 'PUT', body: notifyBody() }); await load(); });
$('nt-test').onclick = () => run($('nt-test'), 'nt-msg', async () => {
  await api('admin/notify', { method: 'PUT', body: notifyBody() });
  const r = await api('alerts/test', { method: 'POST' });
  const bad = r.results.filter((x) => !x.ok);
  if (bad.length) throw new Error(bad.map((x) => `${x.channel}: ${x.error}`).join(' · '));
  return '✓ Đã gửi thử: ' + r.results.map((x) => x.channel).join(', ');
});

// ---------- InfluxDB ----------
function renderInflux() {
  const i = S.influxdb;
  $('ix-enabled').checked = i.enabled; $('ix-version').value = String(i.version); $('ix-url').value = i.url; $('ix-org').value = i.org; $('ix-bucket').value = i.bucket;
  $('ix-token').value = ''; $('ix-token').placeholder = i.hasToken ? '•••••• đã lưu (để trống = giữ nguyên)' : '';
  $('ix-db').value = i.database; $('ix-user').value = i.username; $('ix-pass').value = ''; $('ix-pass').placeholder = i.hasPassword ? '•••••• đã lưu' : '';
  $('ix-meas').value = i.measurement; $('ix-schema').value = i.schema || 'portal'; $('ix-batch').value = i.batchSeconds; $('ix-mounts').checked = i.mounts; $('ix-alerts').checked = i.alerts;
  const s = i.status;
  $('ix-status').innerHTML = !s.enabled ? '<span class="st off">Tắt</span>'
    : s.lastError && s.lastErrorAt > s.lastOk ? `<span class="st err" title="${esc(s.lastError)}">Lỗi ghi · ${s.pending} dòng chờ</span>`
    : `<span class="st ok">Đang ghi · ${s.written.toLocaleString()} dòng</span>`;
  syncInfluxVersion();
}
function syncInfluxVersion() { const v1 = $('ix-version').value === '1'; document.querySelectorAll('.v1').forEach((e) => { e.hidden = !v1; }); document.querySelectorAll('.v2').forEach((e) => { e.hidden = v1; }); }
$('ix-version').onchange = syncInfluxVersion;
const influxBody = () => ({
  enabled: $('ix-enabled').checked, version: $('ix-version').value, url: $('ix-url').value, org: $('ix-org').value, bucket: $('ix-bucket').value, token: $('ix-token').value,
  database: $('ix-db').value, username: $('ix-user').value, password: $('ix-pass').value, measurement: $('ix-meas').value, batchSeconds: $('ix-batch').value,
  mounts: $('ix-mounts').checked, alerts: $('ix-alerts').checked, schema: $('ix-schema').value,
});
$('ix-save').onclick = () => run($('ix-save'), 'ix-msg', async () => { await api('admin/influxdb', { method: 'PUT', body: influxBody() }); await load(); });
$('ix-test').onclick = () => run($('ix-test'), 'ix-msg', async () => {
  const r = await api('admin/influxdb/test', { method: 'POST', body: influxBody() });
  if (!r.ok) throw new Error(r.message);
  return '✓ ' + r.message;
});

// ---------- người dùng & key ----------
function renderUsers() {
  const ROLE = { admin: 'Quản trị', viewer: 'Nhân viên', manager: 'Nhân viên phụ trách khách', customer: 'Khách hàng' };
  const need2 = (u) => S.security?.require2fa === 'all' || (S.security?.require2fa === 'admin' && u.role === 'admin');
  $('users').innerHTML = S.users.length ? S.users.map((u) => `<tr><td><b>${esc(u.username)}</b></td><td>${ROLE[u.role] || esc(u.role)}</td>
      <td class="sm">${u.customers ? `${u.role === 'manager' ? 'Khách: ' : ''}${esc(u.customers.join(', '))}` : u.sites ? `Site: ${esc(u.sites.join(', '))}` : u.role === 'admin' ? 'Toàn bộ' : 'Mọi site'}</td>
      <td>${u.totp ? `<span class="st ok" title="Bật từ ${u.totpSince ? new Date(u.totpSince).toLocaleString('vi-VN') : '?'} · còn ${u.recoveryLeft} mã khôi phục">Đang bật</span>` : need2(u) ? '<span class="st warn">Chưa bật (bắt buộc)</span>' : '<span class="st off">Tắt</span>'}</td>
      <td>${u.totp ? `<button data-reset2fa="${esc(u.username)}" title="Dùng khi người này mất điện thoại">Tắt 2FA</button> ` : ''}${u.role !== 'admin' ? `<button data-imp="${esc(u.username)}" title="Mở portal đúng như ${esc(u.username)} thấy">Xem như</button> ` : ''}<button data-edit-user="${esc(u.username)}">Sửa</button> <button data-del-user="${esc(u.username)}">Xoá</button></td></tr>`).join('')
    : '<tr><td colspan="5" class="sm">Chưa có tài khoản — portal đang mở cho mọi người.</td></tr>';
  $('u-cust-list').innerHTML = (S.customerNames || []).map((c) => `<option value="${esc(c)}">`).join('');
  $('u-site-list').innerHTML = (S.siteNames || []).map((c) => `<option value="${esc(c)}">`).join('');
  renderSecurity();
  $('keys').innerHTML = S.apiKeys.length ? S.apiKeys.map((k) => `<tr><td><b>${esc(k.name)}</b></td><td>${esc(k.role)}</td><td class="sm"><code>${esc(k.prefix)}</code></td><td class="sm">${k.created ? new Date(k.created).toLocaleString() : ''}</td><td style="text-align:right"><button data-del-key="${esc(k.name)}">Thu hồi</button></td></tr>`).join('')
    : '<tr><td colspan="5" class="sm">Chưa có API key.</td></tr>';
  $('openwarn').hidden = S.users.length > 0;
}
$('users').onclick = async (e) => {
  const d = e.target.dataset;
  if (d.imp) {
    try {
      const r = await api('admin/users/' + encodeURIComponent(d.imp) + '/impersonate', { method: 'POST' });
      location.href = r.go;
    } catch (err) { toast('✗ ' + esc(err.message)); }
    return;
  }
  if (d.editUser) {
    const u = S.users.find((x) => x.username === d.editUser);
    $('u-name').value = u.username; $('u-role').value = u.role; $('u-cust').value = (u.customers || []).join(', '); $('u-sites').value = (u.sites || []).join(', ');
    $('u-role').dispatchEvent(new Event('change')); $('u-pass').focus();
    msg('u-msg', 'Nhập mật khẩu mới (có thể giữ mật khẩu cũ bằng cách nhập lại mật khẩu cũ) rồi bấm Tạo / đặt lại');
    return;
  }
  if (d.reset2fa) {
    if (!confirm(`Tắt xác thực 2 lớp của ${d.reset2fa}? Dùng khi người này mất điện thoại; họ sẽ phải bật lại.`)) return;
    try { await api('admin/users/' + encodeURIComponent(d.reset2fa) + '/reset2fa', { method: 'POST' }); load(); toast('✓ Đã tắt 2FA của ' + esc(d.reset2fa)); } catch (err) { toast('✗ ' + esc(err.message)); }
    return;
  }
  const n = d.delUser; if (!n || !confirm(`Xoá tài khoản ${n}?`)) return;
  try { await api('admin/users/' + encodeURIComponent(n), { method: 'DELETE' }); load(); } catch (err) { toast('✗ ' + esc(err.message)); }
};
$('u-role').onchange = () => {
  const r = $('u-role').value;
  $('u-cust-wrap').hidden = !['customer', 'manager'].includes(r);
  $('u-cust-label').textContent = r === 'manager' ? 'Phụ trách các khách hàng' : 'Thuộc khách hàng';
  $('u-sites-wrap').hidden = r !== 'viewer';
};

// ---------- bảo mật: 2FA bắt buộc, HTTPS ----------
function renderSecurity() {
  const sec = S.security || {}, h = sec.https || {};
  $('sec-2fa').value = sec.require2fa || 'none';
  $('https-on').checked = !!h.enabled; $('https-port').value = h.port || 3443; $('https-redir').checked = !!h.redirectHttp;
  $('https-pfx').value = h.pfx || ''; $('https-cert').value = h.cert || ''; $('https-key').value = h.key || '';
  $('https-pp-st').textContent = h.hasPassphrase ? '(đã lưu)' : '';
  const st = h.status || {};
  $('sec-st').textContent = st.running ? `HTTPS đang chạy: cổng ${st.port}` : h.enabled ? `HTTPS chưa chạy${st.error ? ': ' + st.error : ' (cần khởi động lại portal)'}` : 'HTTPS đang tắt';
}
$('sec-save').onclick = () => run($('sec-save'), 'sec-msg', async () => {
  const r = await api('admin/security', { method: 'PUT', body: { require2fa: $('sec-2fa').value, https: {
    enabled: $('https-on').checked, port: $('https-port').value, redirectHttp: $('https-redir').checked,
    pfx: $('https-pfx').value.trim(), cert: $('https-cert').value.trim(), key: $('https-key').value.trim(), passphrase: $('https-pp').value } } });
  $('https-pp').value = '';
  await load();
  return '✓ Đã lưu' + (r.note ? ` (${r.note})` : '');
});

// ---------- nhật ký thao tác ----------
async function loadAudit() {
  const q = new URLSearchParams({ limit: 500, q: $('au-q').value.trim(), user: $('au-user').value, failed: $('au-failed').checked ? '1' : '' });
  const { data } = await api('admin/audit?' + q);
  const users = [...new Set([...S.users.map((u) => u.username), ...data.map((e) => e.user)])].filter(Boolean).sort();
  const cur = $('au-user').value;
  $('au-user').innerHTML = '<option value="">Mọi người dùng</option>' + users.map((u) => `<option ${u === cur ? 'selected' : ''}>${esc(u)}</option>`).join('');
  const detail = (e) => {
    const d = e.detail || {};
    if (e.action === 'Gán khách cho VM' && d.ids) return `${d.ids.length} VM → <b>${esc(d.customer || '(bỏ gán)')}</b>${d.ids.length <= 3 ? ': ' + esc(d.ids.join(', ')) : ''}`;
    if (e.path?.startsWith('DELETE')) return esc(decodeURIComponent(e.path.replace(/^DELETE \/api\/v1\/[a-z]+\//, '')));
    if (/Đăng nhập/.test(e.action)) return '';
    const s = JSON.stringify(d); return s && s !== '{}' ? `<code style="font-size:11.5px">${esc(s.length > 160 ? s.slice(0, 160) + '…' : s)}</code>` : '';
  };
  $('au-rows').innerHTML = data.map((e) => `<tr><td class="sm" style="white-space:nowrap">${new Date(e.ts).toLocaleString('vi-VN')}</td><td><b>${esc(e.user)}</b>${e.via === 'apikey' ? ' <span class="st off">API key</span>' : ''}</td>
    <td>${esc(e.action)}</td><td class="sm" style="max-width:420px;overflow-wrap:anywhere">${detail(e)}</td><td class="sm">${esc(String(e.ip || '').replace('::ffff:', ''))}</td>
    <td>${e.ok === false ? `<span class="st err">lỗi ${e.status}</span>` : '<span class="st ok">OK</span>'}</td></tr>`).join('') || '<tr><td colspan="6" class="sm">Chưa có thao tác nào.</td></tr>';
  $('au-msg').textContent = `${data.length} dòng gần nhất`;
}
let auTimer = 0;
$('au-q').oninput = () => { clearTimeout(auTimer); auTimer = setTimeout(() => loadAudit().catch(() => {}), 300); };
$('au-user').onchange = $('au-failed').onchange = $('au-reload').onclick = () => loadAudit().catch((e) => toast('✗ ' + esc(e.message)));
document.querySelector('[data-t="audit"]').addEventListener('click', () => loadAudit().catch((e) => toast('✗ ' + esc(e.message))));
if (location.hash === '#audit') setTimeout(() => loadAudit().catch(() => {}), 500);
$('keys').onclick = async (e) => {
  const n = e.target.dataset.delKey; if (!n || !confirm(`Thu hồi API key "${n}"? Hệ thống đang dùng key này sẽ bị từ chối ngay.`)) return;
  try { await api('admin/apikeys/' + encodeURIComponent(n), { method: 'DELETE' }); load(); } catch (err) { toast('✗ ' + esc(err.message)); }
};
$('u-add').onclick = () => run($('u-add'), 'u-msg', async () => {
  if ($('u-pass').value !== $('u-pass2').value) throw new Error('Hai lần nhập mật khẩu không khớp');
  const r = await api('admin/users', { method: 'POST', body: { username: $('u-name').value, role: $('u-role').value, password: $('u-pass').value, customers: $('u-cust').value, sites: $('u-sites').value } });
  $('u-pass').value = $('u-pass2').value = '';
  if (r.firstUser) { toast('✓ Đã tạo tài khoản đầu tiên — portal giờ bắt buộc đăng nhập. Chuyển tới trang đăng nhập…', 4000); setTimeout(() => { location.href = 'login?next=/settings%23users'; }, 2500); return false; }
  await load();
  return '✓ Đã lưu tài khoản ' + $('u-name').value;
});
$('k-add').onclick = () => run($('k-add'), 'k-msg', async () => {
  const r = await api('admin/apikeys', { method: 'POST', body: { name: $('k-name').value, role: $('k-role').value } });
  $('k-new').hidden = false;
  $('k-new').innerHTML = `<p style="margin:12px 0 0;font-size:13px"><b>Key cho "${esc(r.name)}" (${esc(r.role)})</b> — chỉ hiện <b>một lần</b>, hãy copy và lưu lại:</p>
    <div class="keybox" id="k-val">${esc(r.key)}</div><div class="actions"><button id="k-copy">Copy</button><span class="sm" style="color:var(--muted);font-size:12px">Gửi kèm header <code>X-API-Key</code></span></div>`;
  $('k-copy').onclick = () => navigator.clipboard.writeText(r.key).then(() => toast('Đã copy key'));
  await load();
  return false;
});

// ---------- chung ----------
function renderGeneral() {
  const g = S.general;
  $('g-url').value = g.publicUrl; $('g-grafana').value = g.grafanaUrl || ''; $('g-ret').value = g.retentionDays; $('g-off').value = g.offlineAfterSeconds; $('g-site').value = g.defaultSite; $('g-cors').value = csv(g.cors); $('g-metrics').checked = g.metricsPublic;
}
$('g-save').onclick = () => run($('g-save'), 'g-msg', async () => {
  const r = await api('admin/general', { method: 'PUT', body: { publicUrl: $('g-url').value, grafanaUrl: $('g-grafana').value, retentionDays: $('g-ret').value, offlineAfterSeconds: $('g-off').value, defaultSite: $('g-site').value, cors: $('g-cors').value, metricsPublic: $('g-metrics').checked } });
  return '✓ Đã lưu' + (r.note ? ` (${r.note})` : '');
});

// ---------- tải & realtime ----------
async function load() {
  S = await api('admin/settings');
  try { S.siteNames = (await api('sites')).data.map((x) => x.site).sort(); } catch { S.siteNames = []; }
  try { const c = await api('customers'); S.customerNames = c.data.filter((x) => x.customer).map((x) => x.customer).sort((a, b) => a.localeCompare(b)); } catch { S.customerNames = []; }
  renderVc(); renderRules(); renderNotify(); renderInflux(); renderUsers(); renderGeneral(); renderCustomers();
}
function connect() {
  const es = new EventSource('api/v1/stream');
  es.addEventListener('collector', (e) => {
    const d = JSON.parse(e.data);
    if (!S) return;
    S.collectors = d.collectors;
    const map = new Map(d.vcenters.map((x) => [x.id, x.status]));
    S.vcenters.forEach((v) => { if (map.has(v.id)) v.status = map.get(v.id); });
    if (!$('vc-dlg').open) renderVc();
  });
  es.addEventListener('reload', () => setTimeout(() => location.reload(), 300));
}

(async function init() {
  const me = await api('me');
  if (me.enroll) return location.replace('account?enroll=1');
  if (me.role !== 'admin') { document.querySelector('main').innerHTML = '<div class="empty">Chỉ tài khoản <b>admin</b> mới vào được trang Cài đặt.</div>'; return; }
  $('user').innerHTML = me.via === 'session' ? `<a href="account" title="Tài khoản của tôi"><b>${esc(me.user)}</b></a><span class="role">admin</span><a href="#" id="logout">Đăng xuất</a>`
    : me.via === 'open' ? '<span class="role">⚠ chưa bật đăng nhập</span>' : '';
  $('grafana-link').href = `${(me.grafanaUrl || `${location.protocol}//${location.hostname}:3001`).replace(/\/+$/, '')}/d/vm-monitor-overview`;
  const lo = $('logout'); if (lo) lo.onclick = async (e) => { e.preventDefault(); await fetch('api/v1/logout', { method: 'POST' }); location.href = 'login'; };
  const t = location.hash.slice(1); if (t && $('t-' + t)) showTab(t);
  await load();
  connect();
  setInterval(() => { if (S && !$('vc-dlg').open) renderVc(); }, 15000);
})().catch((e) => { if (e.message !== 'unauthorized') toast('✗ ' + esc(e.message), 8000); });

// ---------- gợi ý gán khách ----------
let SG = null;
function renderSuggest() {
  if (!SG) return;
  const low = $('sg-low').checked;
  const groups = SG.groups.filter((g) => low || g.confidence !== 'thấp');
  $('sg-sum').textContent = `${SG.suggested} / ${SG.unassigned} VM chưa có khách có gợi ý`;
  $('sg-table').hidden = !groups.length;
  const tone = { cao: 'ok', 'trung bình': 'warn', thấp: 'off' };
  $('sg-rows').innerHTML = groups.map((g) => {
    const i = SG.groups.indexOf(g);
    const names = g.vms.map((v) => v.name);
    return `<tr data-i="${i}"><td><input type="checkbox" class="sg-pick" ${g.confidence === 'thấp' ? '' : 'checked'}></td>
      <td><input class="sg-name" value="${esc(g.customer)}" style="min-width:170px">${g.isNew ? ' <span class="st off">mới</span>' : ''}</td>
      <td><span class="st ${tone[g.confidence]}">${g.confidence}</span></td><td class="sm">${esc(g.reason)}</td>
      <td class="sm"><b>${g.vms.length}</b> · ${esc(names.slice(0, 4).join(', '))}${names.length > 4 ? ` <a href="#" class="sg-more">+${names.length - 4}</a>` : ''}</td></tr>`;
  }).join('') || '';
  $('sg-apply').disabled = !groups.length;
  if (!groups.length) msg('sg-msg', SG.unassigned ? 'Không tìm được gợi ý nào' : 'Mọi VM đều đã có khách');
}
$('sg-load').onclick = () => run($('sg-load'), 'sg-msg', async () => { SG = await api('admin/customers/suggest'); renderSuggest(); return `${SG.groups.length} nhóm`; });
$('sg-low').onchange = renderSuggest;
$('sg-all').onchange = (e) => document.querySelectorAll('.sg-pick').forEach((c) => { c.checked = e.target.checked; });
$('sg-rows').onclick = (e) => {
  const a = e.target.closest('.sg-more'); if (!a) return;
  e.preventDefault();
  const g = SG.groups[a.closest('tr').dataset.i];
  a.parentElement.innerHTML = `<b>${g.vms.length}</b> · ${esc(g.vms.map((v) => v.name).join(', '))}`;
};
$('sg-apply').onclick = () => run($('sg-apply'), 'sg-msg', async () => {
  const rows = [...document.querySelectorAll('#sg-rows tr')].filter((tr) => tr.querySelector('.sg-pick').checked);
  if (!rows.length) throw new Error('Chưa chọn nhóm nào');
  const byCust = new Map();
  for (const tr of rows) {
    const name = tr.querySelector('.sg-name').value.trim(); if (!name) continue;
    const ids = byCust.get(name) || []; ids.push(...SG.groups[tr.dataset.i].vms.map((v) => v.id)); byCust.set(name, ids);
  }
  let n = 0;
  for (const [customer, ids] of byCust) { await api('admin/customers/assign', { method: 'PUT', body: { ids, customer } }); n += ids.length; }
  SG = await api('admin/customers/suggest'); renderSuggest();
  S = await api('admin/settings'); renderCustomers();
  return `✓ Đã gán ${n} VM cho ${byCust.size} khách`;
});

// ---------- vận hành: dọn VM, sao lưu, báo cáo ----------
let OP = null;
const mb = (b) => (b >= 1073741824 ? `${(b / 1073741824).toFixed(2)} GB` : `${(b / 1048576).toFixed(1)} MB`);
const dt = (ts) => (ts ? new Date(ts).toLocaleString('vi-VN') : '—');
function renderOps() {
  if (!OP) return;
  $('op-purge').value = OP.purgeAfterDays;
  $('op-stale').innerHTML = OP.stale.length ? OP.stale.map((v) => `<tr><td>${esc(v.id)}</td><td class="sm">${dt(v.lastSeen)}</td><td>${v.days}${OP.purgeAfterDays && v.days >= OP.purgeAfterDays ? ' <span class="st err">sẽ xoá</span>' : ''}</td></tr>`).join('')
    : '<tr><td colspan="3" class="sm">Không có VM nào ngừng gửi số liệu quá 1 ngày.</td></tr>';
  const lp = OP.lastPurge; $('op-purge-last').textContent = lp ? `Lần dọn gần nhất ${dt(lp.at)}: xoá ${lp.removed}` : '';
  const b = OP.backup;
  $('op-bk-on').checked = b.enabled; $('op-bk-hour').value = b.hour; $('op-bk-keep').value = b.keepDays; $('op-bk-dir').value = b.dirRaw || '';
  $('op-bk-dir').placeholder = b.dir;
  $('op-bk-st').className = 'st ' + (!b.enabled ? 'off' : b.last && !b.last.ok ? 'err' : 'ok');
  $('op-bk-st').textContent = !b.enabled ? 'Đang tắt' : b.last && !b.last.ok ? 'Lỗi' : `${b.count} bản`;
  $('op-bk-list').innerHTML = b.backups.length ? b.backups.map((x) => `<tr><td>${esc(x.name)}</td><td class="sm">${mb(x.bytes)}</td></tr>`).join('') : '<tr><td colspan="2" class="sm">Chưa có bản sao lưu nào.</td></tr>';
  $('op-bk-last').textContent = b.last ? (b.last.ok ? `Lần gần nhất ${dt(b.last.at)}: ${b.last.files} file, ${mb(b.last.bytes)}` : `Lỗi ${dt(b.last.at)}: ${b.last.error}`) : `Lưu vào ${b.dir}`;
  const r = OP.reports;
  $('op-rp-on').checked = r.enabled; $('op-rp-day').value = r.day; $('op-rp-hour').value = r.hour; $('op-rp-vm').checked = r.vmDetail; $('op-rp-to').value = r.to.join(', ');
  $('op-rp-to').placeholder = r.defaultTo.length ? `mặc định: ${r.defaultTo.join(', ')}` : 'noc@company.vn';
  $('op-rp-st').className = 'st ' + (!r.smtp ? 'warn' : r.enabled ? 'ok' : 'off');
  $('op-rp-st').textContent = !r.smtp ? 'Chưa cấu hình SMTP' : r.enabled ? `Gửi ngày ${r.day} lúc ${r.hour}h` : 'Đang tắt';
  const l = r.state?.last;
  $('op-rp-last').textContent = l ? (l.ok ? `Đã gửi tháng ${l.month} lúc ${dt(l.at)} tới ${l.to.join(', ')}` : `Gửi tháng ${l.month} lỗi: ${l.error}`) : '';
  const months = [...OP.usageMonths].reverse();
  $('op-rp-month').innerHTML = months.map((m) => `<option ${m === r.nextMonth ? 'selected' : ''}>${m}</option>`).join('') || '<option>(chưa có số liệu)</option>';
}
async function loadOps() { OP = await api('admin/ops'); renderOps(); }
const saveOps = (btn, msgId, body) => run(btn, msgId, async () => { const r = await api('admin/ops', { method: 'PUT', body }); OP = r.status; renderOps(); });
$('op-purge-save').onclick = () => saveOps($('op-purge-save'), 'op-purge-msg', { purgeAfterDays: Number($('op-purge').value) });
$('op-purge-now').onclick = () => run($('op-purge-now'), 'op-purge-msg', async () => {
  if (!confirm(`Xoá ngay mọi VM / host / datastore không gửi số liệu quá ${OP.purgeAfterDays} ngày? Lịch sử của chúng sẽ mất.`)) return false;
  const r = await api('admin/ops/purge', { method: 'POST' }); await loadOps(); return r.disabled ? 'Đang tắt tự xoá (0 ngày)' : `✓ Đã xoá ${r.removed}`;
});
$('op-bk-save').onclick = () => saveOps($('op-bk-save'), 'op-bk-msg', { backup: { enabled: $('op-bk-on').checked, hour: $('op-bk-hour').value, keepDays: $('op-bk-keep').value, dir: $('op-bk-dir').value.trim() } });
$('op-bk-now').onclick = () => run($('op-bk-now'), 'op-bk-msg', async () => { const r = await api('admin/backup', { method: 'POST' }); await loadOps(); return `✓ ${r.files} file, ${mb(r.bytes)}`; });
$('op-rp-save').onclick = () => saveOps($('op-rp-save'), 'op-rp-msg', {
  reports: { enabled: $('op-rp-on').checked, day: $('op-rp-day').value, hour: $('op-rp-hour').value, vmDetail: $('op-rp-vm').checked, to: $('op-rp-to').value.split(/[,;\s]+/).filter(Boolean) },
});
$('op-rp-send').onclick = () => run($('op-rp-send'), 'op-rp-msg', async () => {
  const r = await api('admin/reports/send', { method: 'POST', body: { month: $('op-rp-month').value, to: $('op-rp-to').value.split(/[,;\s]+/).filter(Boolean) } });
  await loadOps(); return `✓ Đã gửi tới ${r.to.join(', ')}`;
});
document.querySelector('[data-t="ops"]').addEventListener('click', () => loadOps().catch((e) => toast('✗ ' + esc(e.message))));
if (location.hash === '#ops') loadOps().catch(() => {});
