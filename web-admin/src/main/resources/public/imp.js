// Thanh báo "Admin đang xem như …" (hiện trên mọi trang khi admin dùng chức năng Xem như)
'use strict';
(function () {
  const ROLE = { read: 'nhân viên', manager: 'nhân viên phụ trách khách', customer: 'khách hàng' };
  fetch('api/v1/me', { credentials: 'same-origin' }).then((r) => (r.ok ? r.json() : null)).then((me) => {
    if (!me || !me.imp) return;
    const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const bar = document.createElement('div');
    bar.setAttribute('role', 'status');
    bar.style.cssText = 'position:fixed;left:16px;bottom:16px;z-index:1000;display:flex;gap:12px;align-items:center;'
      + 'background:#1f2937;color:#fff;padding:10px 12px 10px 16px;border-radius:12px;box-shadow:0 10px 30px rgba(0,0,0,.35);font-size:13.5px;max-width:min(560px, calc(100% - 32px));flex-wrap:wrap';
    bar.innerHTML = `<span>👁 <b>${esc(me.imp)}</b> đang xem như <b>${esc(me.user)}</b> (${esc(ROLE[me.role] || me.role)}) — thấy đúng như người này thấy</span>`
      + '<button type="button" style="background:#3b82f6;color:#fff;border:0;border-radius:8px;padding:6px 12px;font-weight:600;cursor:pointer">Quay lại admin</button>';
    bar.querySelector('button').onclick = async () => {
      const r = await fetch('api/v1/impersonate/stop', { method: 'POST' }).then((x) => x.json()).catch(() => ({}));
      location.href = r.back ? 'settings#users' : 'login';
    };
    document.body.appendChild(bar);
  }).catch(() => {});
})();
