// Khoảng thời gian cho biểu đồ: chọn sẵn (15 phút … 30 ngày), tuỳ chỉnh từ ngày–đến ngày, kéo chuột trên biểu đồ để phóng to,
// nhấp đúp để thu nhỏ. Dùng chung cho trang VM, host và cổng khách.
//   TW.init(selectEl, onChange)   gắn vào ô chọn khoảng thời gian; onChange() được gọi khi khoảng đổi (tải lại số liệu)
//   TW.win() -> [t0, t1]          khoảng đang xem (ms)
//   TW.live()                     true khi đang xem "tới hiện tại" (nhận số liệu realtime)
//   TW.query()                    "range=3600" hoặc "from=…&to=…" để gọi API metrics
//   TW.mark(el, t0, t1, x0, x1)   đánh dấu một biểu đồ: vùng vẽ từ x0 đến x1 (tỉ lệ 0..1 theo bề ngang phần tử)
(function () {
  const TW = { mode: 'live', t0: 0, t1: 0, sel: null, onChange: null, preset: '3600' };
  const pad = (n) => String(n).padStart(2, '0');
  const toInput = (t) => { const d = new Date(t); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };
  const fmt = (t) => { const d = new Date(t); return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`; };
  const span = (ms) => { const m = Math.round(ms / 60000); return m < 60 ? `${m} phút` : m < 2880 ? `${Math.round(m / 6) / 10} giờ` : `${Math.round(m / 144) / 10} ngày`; };
  let ui = {};

  const css = `
.tw-bar{display:inline-flex;align-items:center;gap:6px;flex-wrap:wrap}
.tw-row{display:none;flex-direction:column;gap:6px;padding:8px 20px;border-bottom:1px solid var(--border);background:var(--panel,var(--bg));position:sticky;z-index:4}
.tw-row.open{display:flex}
.tw-custom{display:none;align-items:center;gap:6px;flex-wrap:wrap}
.tw-custom.open{display:flex}
.tw-custom input{font:inherit;font-size:13px;padding:5px 7px;border:1px solid var(--border);border-radius:8px;background:var(--card,var(--bg));color:inherit}
.tw-custom button,.tw-reset{font:inherit;font-size:13px;padding:5px 10px;border:1px solid var(--border);border-radius:8px;background:var(--card,var(--bg));color:inherit;cursor:pointer}
.tw-custom button.go{background:var(--accent,#2563eb);border-color:var(--accent,#2563eb);color:#fff}
.tw-label{display:none;font-size:12px;color:var(--muted);align-items:center;gap:6px}
.tw-label.on{display:inline-flex}
.tw-label b{color:var(--text,inherit);font-weight:600}
.tw-hint{font-size:11.5px;color:var(--muted)}
.tw-msg{font-size:12px;color:var(--crit,#dc2626)}
[data-tw]{cursor:crosshair;user-select:none;-webkit-user-select:none}
.tw-selbox{position:fixed;z-index:9999;pointer-events:none;background:color-mix(in srgb,var(--accent,#2563eb) 16%,transparent);border-left:1px solid var(--accent,#2563eb);border-right:1px solid var(--accent,#2563eb)}`;

  TW.init = function (sel, onChange) {
    TW.sel = sel; TW.onChange = onChange; TW.preset = sel.value;
    if (!document.getElementById('tw-style')) { const st = document.createElement('style'); st.id = 'tw-style'; st.textContent = css; document.head.appendChild(st); }
    for (const [v, t] of [['2592000', '30 ngày']]) if (![...sel.options].some((o) => o.value === v)) sel.add(new Option(t, v));
    if (![...sel.options].some((o) => o.value === '900')) sel.add(new Option('15 phút', '900'), 0);
    sel.add(new Option('Tuỳ chỉnh…', 'custom'));
    ui.label = Object.assign(document.createElement('span'), { className: 'tw-label' });
    ui.label.innerHTML = '<span>Đang xem <b></b></span><button class="tw-reset" type="button" title="Quay về xem tới hiện tại">⟲ Về hiện tại</button>';
    ui.custom = Object.assign(document.createElement('div'), { className: 'tw-custom' });
    ui.custom.innerHTML = '<span class="tw-hint">Từ</span><input type="datetime-local" class="tw-from"><span class="tw-hint">đến</span><input type="datetime-local" class="tw-to">'
      + '<button type="button" class="go">Xem</button><button type="button" class="cancel">Huỷ</button><span class="tw-msg"></span>'
      + '<span class="tw-hint" style="flex-basis:100%">Mẹo: kéo chuột trên biểu đồ để phóng to một đoạn, nhấp đúp để thu nhỏ.</span>';
    ui.row = Object.assign(document.createElement('div'), { className: 'tw-row' });
    ui.row.append(ui.label, ui.custom);
    const host = sel.closest('header') || sel.parentElement;
    host.insertAdjacentElement('afterend', ui.row);
    ui.custom.querySelector('.go').onclick = applyCustom;
    ui.custom.querySelector('.cancel').onclick = () => { ui.custom.classList.remove('open'); update(); };
    ui.custom.addEventListener('keydown', (e) => { if (e.key === 'Enter') applyCustom(); });
    ui.label.querySelector('.tw-reset').onclick = () => TW.reset();
    sel.onchange = () => {
      if (sel.value === 'custom') {
        const [a, b] = TW.win();
        ui.custom.querySelector('.tw-from').value = toInput(a); ui.custom.querySelector('.tw-to').value = toInput(b);
        ui.custom.querySelector('.tw-msg').textContent = '';
        ui.custom.classList.add('open'); syncRow(); ui.custom.querySelector('.tw-from').focus();
        return;
      }
      TW.preset = sel.value; TW.mode = 'live'; ui.custom.classList.remove('open'); update(); TW.onChange && TW.onChange();
    };
    update();
  };
  function applyCustom() {
    const a = new Date(ui.custom.querySelector('.tw-from').value).getTime(), b = new Date(ui.custom.querySelector('.tw-to').value).getTime();
    const msg = ui.custom.querySelector('.tw-msg');
    if (!a || !b) { msg.textContent = 'Chọn đủ ngày giờ bắt đầu và kết thúc'; return; }
    if (b - a < 60000) { msg.textContent = 'Thời điểm kết thúc phải sau thời điểm bắt đầu'; return; }
    if (a > Date.now()) { msg.textContent = 'Thời điểm bắt đầu đang ở tương lai'; return; }
    ui.custom.classList.remove('open');
    TW.set(a, Math.min(b, Date.now()), 'custom');
  }
  const syncRow = () => {
    if (!ui.row) return;
    const h = ui.row.previousElementSibling, sticky = h && getComputedStyle(h).position === 'sticky';
    ui.row.style.top = sticky ? h.offsetHeight + 'px' : '0px';
    ui.row.classList.toggle('open', ui.label.classList.contains('on') || ui.custom.classList.contains('open'));
  };
  function update() {
    if (!TW.sel) return;
    const on = TW.mode !== 'live';
    ui.label.classList.toggle('on', on);
    setTimeout(syncRow);
    if (on) {
      ui.label.querySelector('b').textContent = `${fmt(TW.t0)} → ${fmt(TW.t1)} (${span(TW.t1 - TW.t0)})`;
      TW.sel.value = 'custom';
    } else TW.sel.value = TW.preset;
  }
  TW.win = () => TW.mode === 'live' ? [Date.now() - Number(TW.preset) * 1000, Date.now()] : [TW.t0, TW.t1];
  TW.live = () => TW.mode === 'live';
  TW.query = () => TW.mode === 'live' ? `range=${TW.preset}` : `from=${Math.round(TW.t0)}&to=${Math.round(TW.t1)}`;
  TW.set = function (t0, t1, mode = 'zoom') {
    if (t1 - t0 < 60000) { const c = (t0 + t1) / 2; t0 = c - 30000; t1 = c + 30000; }
    TW.t0 = t0; TW.t1 = t1; TW.mode = mode; update(); TW.onChange && TW.onChange();
  };
  TW.reset = function () { TW.mode = 'live'; ui.custom && ui.custom.classList.remove('open'); update(); TW.onChange && TW.onChange(); };
  TW.zoomOut = function () {
    const [a, b] = TW.win(), c = (a + b) / 2, half = b - a;
    let t0 = c - half, t1 = c + half;
    const now = Date.now();
    if (t1 >= now - 5000) {
      // chạm hiện tại: quay về xem "tới hiện tại" với mốc chọn sẵn gần nhất
      const need = (now - t0) / 1000, opts = [...TW.sel.options].map((o) => Number(o.value)).filter((v) => v > 0).sort((x, y) => x - y);
      TW.preset = String(opts.find((v) => v >= need) || opts[opts.length - 1]); TW.reset(); return;
    }
    TW.set(t0, t1, TW.mode === 'live' ? 'zoom' : TW.mode);
  };
  TW.mark = function (el, t0, t1, x0, x1) {
    el.dataset.tw = '1'; el.dataset.t0 = t0; el.dataset.t1 = t1; el.dataset.x0 = x0; el.dataset.x1 = x1;
    if (!el.title) el.title = 'Kéo chuột để phóng to một đoạn · nhấp đúp để thu nhỏ';
  };

  // ---- kéo chọn để phóng to ----
  let drag = null;
  const tAt = (el, clientX) => {
    const r = el.getBoundingClientRect(), d = el.dataset, f = (clientX - r.left) / r.width;
    const k = Math.min(1, Math.max(0, (f - Number(d.x0)) / (Number(d.x1) - Number(d.x0))));
    return Number(d.t0) + k * (Number(d.t1) - Number(d.t0));
  };
  const clampX = (el, clientX) => { const r = el.getBoundingClientRect(), d = el.dataset; return Math.min(r.left + r.width * Number(d.x1), Math.max(r.left + r.width * Number(d.x0), clientX)); };
  document.addEventListener('mousedown', (e) => {
    const el = e.target.closest && e.target.closest('[data-tw]'); if (!el || e.button !== 0) return;
    e.preventDefault();
    const r = el.getBoundingClientRect(), x = clampX(el, e.clientX);
    const box = Object.assign(document.createElement('div'), { className: 'tw-selbox' });
    Object.assign(box.style, { left: x + 'px', top: r.top + 'px', height: r.height + 'px', width: '0px' });
    document.body.appendChild(box);
    drag = { el, x, box };
  });
  document.addEventListener('mousemove', (e) => {
    if (!drag) return;
    const x = clampX(drag.el, e.clientX);
    Object.assign(drag.box.style, { left: Math.min(x, drag.x) + 'px', width: Math.abs(x - drag.x) + 'px' });
    const tip = document.getElementById('tip'); if (tip) tip.style.display = 'none';
  });
  document.addEventListener('mouseup', (e) => {
    if (!drag) return;
    const { el, x, box } = drag; drag = null; box.remove();
    const x2 = clampX(el, e.clientX);
    if (Math.abs(x2 - x) < 6) return;
    const a = tAt(el, Math.min(x, x2)), b = tAt(el, Math.max(x, x2));
    TW.set(a, b, 'zoom');
  });
  document.addEventListener('dblclick', (e) => { const el = e.target.closest && e.target.closest('[data-tw]'); if (el) { e.preventDefault(); TW.zoomOut(); } });
  addEventListener('keydown', (e) => { if (e.key === 'Escape' && drag) { drag.box.remove(); drag = null; } });
  window.TW = TW;
})();
