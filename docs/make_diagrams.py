#!/usr/bin/env python3
"""Sinh 3 sơ đồ SVG có mũi tên chuyển động cho README (tự đổi màu theo giao diện sáng / tối của GitHub).

    python docs/make_diagrams.py
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"

# màu (sáng / tối) theo nhóm: nền ô, viền, chữ tiêu đề, chữ phụ
RAMPS = {
    "p": ("#EEEDFE", "#7F77DD", "#3C3489", "#534AB7", "#3C3489", "#7F77DD", "#CECBF6", "#AFA9EC"),  # web app
    "t": ("#E1F5EE", "#1D9E75", "#085041", "#0F6E56", "#085041", "#1D9E75", "#9FE1CB", "#5DCAA5"),  # agent / dữ liệu
    "g": ("#F1EFE8", "#888780", "#2C2C2A", "#5F5E5A", "#444441", "#888780", "#D3D1C7", "#B4B2A9"),  # người dùng / vCenter
    "c": ("#FAECE7", "#D85A30", "#712B13", "#993C1D", "#712B13", "#D85A30", "#F5C4B3", "#F0997B"),  # đầu ra
    "r": ("#FCEBEB", "#E24B4A", "#791F1F", "#A32D2D", "#791F1F", "#E24B4A", "#F7C1C1", "#F09595"),  # chặn
}
FLOW = {"p": ("#7F77DD", "#AFA9EC"), "t": ("#1D9E75", "#5DCAA5"), "c": ("#D85A30", "#F0997B"), "r": ("#E24B4A", "#F09595"), "g": ("#888780", "#B4B2A9")}


def style():
    light, dark = [], []
    for k, (f, s, t, u, df, ds, dt, du) in RAMPS.items():
        light.append(f".b{k} rect{{fill:{f};stroke:{s}}} .b{k} .t{{fill:{t}}} .b{k} .s{{fill:{u}}}")
        dark.append(f".b{k} rect{{fill:{df};stroke:{ds}}} .b{k} .t{{fill:{dt}}} .b{k} .s{{fill:{du}}}")
    for k, (c, dc) in FLOW.items():
        light.append(f".f{k}{{stroke:{c}}} .d{k}{{fill:{c}}} .m{k}{{fill:{c}}}")
        dark.append(f".f{k}{{stroke:{dc}}} .d{k}{{fill:{dc}}} .m{k}{{fill:{dc}}}")
    return f"""<style>
text{{font-family:{FONT}}}
.t{{font-size:15px;font-weight:600}} .s{{font-size:12.5px}} .lbl{{font-size:12.5px;fill:#59636e}} .ttl{{font-size:18px;font-weight:600;fill:#1f2328}}
.zone{{fill:none;stroke:#8c959f;stroke-width:1;stroke-dasharray:5 5}} .zl{{font-size:12.5px;fill:#59636e;font-weight:600}}
rect{{stroke-width:1.2}}
.fl{{fill:none;stroke-width:2;stroke-dasharray:7 6;animation:run 1s linear infinite}}
@keyframes run{{to{{stroke-dashoffset:-26}}}}
{chr(10).join(light)}
@media (prefers-color-scheme:dark){{
.lbl,.zl{{fill:#9198a1}} .ttl{{fill:#f0f6fc}} .zone{{stroke:#656c76}}
{chr(10).join(dark)}
}}
@media (prefers-reduced-motion:reduce){{.fl{{animation:none}} .dot{{display:none}}}}
</style>"""


def markers():
    out = []
    for k in FLOW:
        out.append(f'<marker id="a{k}" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
                   f'<path d="M1 1L9 5L1 9z" class="m{k}"/></marker>')
    return "<defs>" + "".join(out) + "</defs>"


def box(x, y, w, h, kind, title, sub=None):
    cx = x + w / 2
    if sub:
        txt = (f'<text class="t" x="{cx}" y="{y + h / 2 - 9}" text-anchor="middle" dominant-baseline="central">{title}</text>'
               f'<text class="s" x="{cx}" y="{y + h / 2 + 12}" text-anchor="middle" dominant-baseline="central">{sub}</text>')
    else:
        txt = f'<text class="t" x="{cx}" y="{y + h / 2}" text-anchor="middle" dominant-baseline="central">{title}</text>'
    return f'<g class="b{kind}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10"/>{txt}</g>'


def flow(d, kind, dur=2.4, dots=1, begin=0.0, arrow=True):
    """Đường đi có nét đứt chạy + chấm số liệu di chuyển dọc đường."""
    m = f' marker-end="url(#a{kind})"' if arrow else ""
    s = f'<path class="fl f{kind}" d="{d}"{m}/>'
    for i in range(dots):
        b = begin + i * dur / dots
        s += (f'<circle class="dot d{kind}" r="4.5"><animateMotion dur="{dur}s" begin="{b:.2f}s" repeatCount="indefinite" path="{d}"/></circle>')
    return s


def zone(x, y, w, h, label, pos="tl"):
    """pos: tl / tr / bl / br — đặt nhãn ở góc không có mũi tên đi qua."""
    tx, anchor = (x + 18, "start") if pos[1] == "l" else (x + w - 18, "end")
    ty = y + 22 if pos[0] == "t" else y + h - 12
    return f'<rect class="zone" x="{x}" y="{y}" width="{w}" height="{h}" rx="16"/><text class="zl" x="{tx}" y="{ty}" text-anchor="{anchor}">{label}</text>'


def svg(w, h, title, desc, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-labelledby="t d">'
            f'<title id="t">{title}</title><desc id="d">{desc}</desc>{style()}{markers()}{body}</svg>\n')


# ---------------------------------------------------------------- 1. kiến trúc tổng thể
def architecture():
    b = []
    b.append('<text class="ttl" x="450" y="34" text-anchor="middle">Kiến trúc tổng thể</text>')
    b.append(box(110, 60, 260, 62, "g", "Admin / nhân viên", "Mạng nội bộ / VPN"))
    b.append(box(530, 60, 260, 62, "g", "Khách hàng", "Internet, HTTPS"))
    b.append(zone(60, 150, 780, 170, "Server trung tâm — service (systemd / Windows)", "br"))
    b.append(box(110, 196, 260, 84, "p", "web-admin  :3000", "Dashboard · API · cảnh báo"))
    b.append(box(530, 196, 260, 84, "p", "web-khach  :8080", "Chỉ API của khách"))
    b.append(flow("M240 122 V192", "p", 1.6))
    b.append(flow("M660 122 V192", "p", 1.6, begin=0.5))
    b.append(flow("M530 238 H374", "p", 1.8))
    b.append('<text class="lbl" x="452" y="228" text-anchor="middle">API khách</text>')
    # agent -> web-admin (dữ liệu đi lên)
    for i, x in enumerate((190, 450, 710)):
        b.append(flow(f"M{x} 404 V362 H240 V284", "t", 2.6, begin=i * 0.7))
    b.append('<text class="lbl" x="470" y="352">HTTPS + API key · mỗi 20 giây</text>')
    for i, (x, n) in enumerate(((80, "A"), (340, "B"), (600, "C"))):
        b.append(box(x, 406, 220, 62, "t", f"Agent site {n}", "Docker"))
        b.append(flow(f"M{x + 110} 468 V512", "g", 1.4, begin=i * 0.3))
        b.append(box(x, 516, 220, 62, "g", f"vCenter site {n}", "API :443 · read-only"))
    b.append('<text class="lbl" x="450" y="610" text-anchor="middle">Agent chỉ gọi ra (vCenter và web-admin) — không mở cổng nào vào site</text>')
    return svg(900, 630, "Kiến trúc tổng thể VM Monitor", "Nhân viên dùng web-admin, khách dùng web-khach; agent tại mỗi site đọc vCenter và gửi về web-admin.", "".join(b))


# ---------------------------------------------------------------- 2. luồng số liệu
def data_flow():
    b = []
    b.append('<text class="ttl" x="450" y="34" text-anchor="middle">Luồng số liệu</text>')
    b.append(box(90, 60, 230, 62, "g", "vCenter", "Bộ đếm hiệu năng 20 giây"))
    b.append(flow("M320 91 H396", "t", 1.2))
    b.append(box(400, 60, 230, 62, "t", "Agent Docker", "Gom ~1.600 VM / lô"))
    b.append(zone(60, 156, 780, 236, "web-admin"))
    b.append(flow("M515 122 V200", "t", 1.6))
    b.append('<text class="lbl" x="528" y="168">POST /api/v1/report</text>')
    b.append(box(400, 204, 230, 62, "p", "Nhận số liệu", "Kiểm tra API key"))
    b.append(flow("M455 266 V290 H195 V310", "t", 2.0))
    b.append(flow("M515 266 V310", "t", 1.4, begin=0.3))
    b.append(flow("M575 266 V290 H735 V310", "t", 2.0, begin=0.6))
    b.append(box(90, 314, 210, 62, "p", "Lưu lịch sử", "Điểm thô + trung bình 5′"))
    b.append(box(410, 314, 210, 62, "p", "Xét cảnh báo", "Rule VM · host · datastore"))
    b.append(box(630, 314, 190, 62, "p", "Báo cáo tháng", "Theo khách · 13 tháng"))
    # đầu ra
    outs = [(60, "Dashboard", "Realtime (SSE)"), (255, "Thông báo", "Telegram · email · webhook"), (470, "Prometheus", "Grafana · /metrics"), (665, "InfluxDB", "Line protocol")]
    widths = [175, 195, 175, 175]
    b.append('<path class="fl fc" d="M450 392 V420" />')
    b.append(flow("M450 420 H147 V448", "c", 1.8, arrow=True))
    b.append(flow("M450 420 H352 V448", "c", 1.6, begin=0.4))
    b.append(flow("M450 420 H557 V448", "c", 1.6, begin=0.8))
    b.append(flow("M450 420 H752 V448", "c", 1.8, begin=1.2))
    for (x, t, s), w in zip(outs, widths):
        b.append(box(x, 452, w, 62, "c", t, s))
    return svg(900, 540, "Luồng số liệu VM Monitor", "vCenter → agent → web-admin (lưu, cảnh báo, báo cáo) → dashboard, thông báo, Prometheus, InfluxDB.", "".join(b))


# ---------------------------------------------------------------- 3. yêu cầu của khách hàng
def customer_flow():
    b = []
    b.append('<text class="ttl" x="450" y="34" text-anchor="middle">Yêu cầu của khách hàng qua 4 lớp kiểm tra</text>')
    b.append(box(170, 58, 380, 62, "g", "Trình duyệt của khách", "Gọi /api/v1/… qua cổng 8080"))
    b.append(flow("M360 120 V176", "p", 1.2))
    b.append(zone(130, 150, 710, 300, "web-khach — cổng khách hàng (internet)", "br"))
    steps = [(180, "API có trong danh sách trắng?", "VM · cảnh báo · báo cáo · tài khoản", "Không → 404", "Cài đặt, host, /metrics…"),
             (276, "Là tài khoản khách hàng?", "Kiểm tra khi đăng nhập", "Không → 403", "Nhân viên, admin"),
             (372, "Cookie riêng kh_sess", "Chuyển tiếp kèm IP thật", None, None)]
    for i, (y, t, s, rt, rs) in enumerate(steps):
        b.append(box(170, y, 380, 62, "p", t, s))
        if rt:
            b.append(flow(f"M550 {y + 31} H616", "r", 1.4, begin=i * 0.4))
            b.append(box(620, y, 200, 62, "r", rt, rs))
        if i < 2:
            b.append(flow(f"M360 {y + 62} V{y + 92}", "p", 1.0, begin=i * 0.3))
    b.append(flow("M360 434 V500", "t", 1.4))
    b.append(zone(130, 474, 710, 110, "web-admin — mạng nội bộ", "br"))
    b.append(box(170, 504, 380, 62, "t", "Lọc theo khách được gán", "Ẩn site · cluster · host · vCenter"))
    b.append(flow("M360 566 V612", "t", 1.2, begin=0.4))
    b.append(box(170, 616, 380, 54, "g", "Chỉ VM của chính khách đó"))
    return svg(900, 700, "Yêu cầu của khách hàng qua web-khach", "Danh sách trắng API, chỉ tài khoản khách, cookie riêng, web-admin lọc theo khách.", "".join(b))


for name, fn in [("architecture.svg", architecture), ("data-flow.svg", data_flow), ("customer-flow.svg", customer_flow)]:
    with open(os.path.join(HERE, name), "w", encoding="utf-8") as f:
        f.write(fn())
    print("đã tạo docs/" + name)
