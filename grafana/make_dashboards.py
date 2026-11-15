#!/usr/bin/env python3
"""
Sinh bộ dashboard Grafana cho VM Monitor Portal (datasource Prometheus).

    python grafana/make_dashboards.py

Tạo:
  grafana/dashboards/*.json               để Import vào Grafana bất kỳ (chọn datasource khi import)
  grafana/stack/grafana/dashboards/*.json bản đã gắn datasource uid "prometheus" cho bộ Docker Compose
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
DS = {"type": "prometheus", "uid": "${DS_PROMETHEUS}"}
TAG = "vm-monitor"

# ---------------------------------------------------------------- bộ lọc (label matcher)
VM = 'site=~"$site", customer=~"$customer", cluster=~"$cluster", host=~"$esxi", vm=~"(?i).*${q:regex}.*"'
VMD = 'site=~"$site", customer=~"$customer", cluster=~"$cluster", host=~"$esxi", vm=~"$vm"'
HOST = 'site=~"$site", cluster=~"$cluster", host=~"$esxi"'
DSM = 'site=~"$site", datastore=~"$ds"'
UP = " and on(site, vm) vm_up == 1"


# ---------------------------------------------------------------- biến
def qvar(name, label, query, multi=True, all_=True, hide=0):
    v = {"name": name, "label": label, "type": "query", "datasource": DS, "query": {"query": query, "refId": name},
         "definition": query, "includeAll": all_, "multi": multi, "refresh": 2, "sort": 1, "hide": hide}
    if all_:
        v["allValue"] = ".*"
        v["current"] = {"selected": True, "text": "All", "value": "$__all"}
    return v


V_SITE = qvar("site", "Site", "label_values(vm_up, site)")
V_CUST = qvar("customer", "Khách hàng", 'label_values(vm_up{site=~"$site"}, customer)')
V_CLUSTER = qvar("cluster", "Cluster", 'label_values(vmhost_up{site=~"$site"}, cluster)')
V_ESXI = qvar("esxi", "Host ESXi", 'label_values(vmhost_up{site=~"$site", cluster=~"$cluster"}, host)')
V_Q = {"name": "q", "label": "Tìm tên VM", "type": "textbox", "query": "", "current": {"text": "", "value": ""}}
V_VM = qvar("vm", "VM (chi tiết)", f'label_values(vm_up{{{VM}}}, vm)', all_=False)
V_DS = qvar("ds", "Datastore", 'label_values(vmdatastore_capacity_bytes{site=~"$site"}, datastore)')
V_GROUP = {"name": "groupby", "label": "Nhóm theo", "type": "custom",
           "query": "Site : site, Khách hàng : customer, Cluster : cluster, Host ESXi : host, VM : vm",
           "options": [{"text": t, "value": v, "selected": v == "site"} for t, v in
                       [("Site", "site"), ("Khách hàng", "customer"), ("Cluster", "cluster"), ("Host ESXi", "host"), ("VM", "vm")]],
           "current": {"text": "Site", "value": "site"}, "includeAll": False, "multi": False}


# ---------------------------------------------------------------- panel
class Board:
    def __init__(self):
        self.panels, self.y, self.pid = [], 0, 0

    def _id(self):
        self.pid += 1
        return self.pid

    def row(self, title):
        self.panels.append({"type": "row", "title": title, "id": self._id(), "collapsed": False, "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}, "panels": []})
        self.y += 1

    @staticmethod
    def targets(exprs, instant=False, fmt=None):
        out = []
        for i, (e, legend) in enumerate(exprs):
            t = {"datasource": DS, "expr": e, "legendFormat": legend, "refId": chr(65 + i)}
            if instant:
                t.update(instant=True, range=False)
            if fmt:
                t["format"] = fmt
            out.append(t)
        return out

    def stat(self, title, expr, x, w=4, h=4, unit="none", steps=None, color_mode="value", desc="", decimals=None):
        d = {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps or [{"color": "green", "value": None}]}, "color": {"mode": "thresholds"}}
        if decimals is not None:
            d["decimals"] = decimals
        self.panels.append({"type": "stat", "title": title, "id": self._id(), "datasource": DS, "description": desc,
                            "gridPos": {"h": h, "w": w, "x": x, "y": self.y}, "targets": self.targets([(expr, "")], instant=True),
                            "fieldConfig": {"defaults": d, "overrides": []},
                            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "colorMode": color_mode,
                                        "graphMode": "none", "textMode": "value", "justifyMode": "center"}})

    def ts(self, title, exprs, x, w=12, h=8, unit="percent", maxv=None, desc="", stack=False):
        d = {"unit": unit, "custom": {"lineWidth": 1, "fillOpacity": 10 if stack else 6, "showPoints": "never", "spanNulls": True,
                                      **({"stacking": {"mode": "normal"}} if stack else {})}}
        if maxv is not None:
            d.update(max=maxv, min=0)
        self.panels.append({"type": "timeseries", "title": title, "id": self._id(), "datasource": DS, "description": desc,
                            "gridPos": {"h": h, "w": w, "x": x, "y": self.y}, "targets": self.targets(exprs),
                            "fieldConfig": {"defaults": d, "overrides": []},
                            "options": {"legend": {"displayMode": "table", "placement": "right", "showLegend": True, "calcs": ["lastNotNull", "max"], "sortBy": "Last *", "sortDesc": True},
                                        "tooltip": {"mode": "multi", "sort": "desc"}}})

    def bar(self, title, expr, legend, x, w=8, h=10, unit="percent", maxv=100, plain=False):
        steps = [{"color": "blue", "value": None}] if plain else [{"color": "green", "value": None}, {"color": "#EAB839", "value": 80}, {"color": "red", "value": 90}]
        d = {"unit": unit, "min": 0, "thresholds": {"mode": "absolute", "steps": steps}, "color": {"mode": "thresholds"}}
        if maxv is not None:
            d["max"] = maxv
        self.panels.append({"type": "bargauge", "title": title, "id": self._id(), "datasource": DS,
                            "gridPos": {"h": h, "w": w, "x": x, "y": self.y}, "targets": self.targets([(expr, legend)], instant=True),
                            "fieldConfig": {"defaults": d, "overrides": []},
                            "options": {"orientation": "horizontal", "displayMode": "gradient", "showUnfilled": True, "valueMode": "color",
                                        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "minVizHeight": 10, "maxVizHeight": 22}})

    def table(self, title, cols, x=0, w=24, h=12, sort=None, hide=("Time", "__name__", "job", "instance", "portal")):
        """cols: [(expr, tên cột, unit, kiểu)] kiểu: None | 'gauge' (thanh %) | 'bool'"""
        rename = {f"Value #{chr(65 + i)}": c[1] for i, c in enumerate(cols)}
        overrides = []
        for _, name, unit, kind in cols:
            props = [{"id": "unit", "value": unit}]
            if kind == "gauge":
                props += [{"id": "custom.cellOptions", "value": {"type": "gauge", "mode": "gradient", "valueDisplayMode": "text"}}, {"id": "min", "value": 0}, {"id": "max", "value": 100},
                          {"id": "thresholds", "value": {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "#EAB839", "value": 80}, {"color": "red", "value": 90}]}}]
            if kind == "bool":
                props += [{"id": "mappings", "value": [{"type": "value", "options": {"1": {"text": "✓", "color": "green"}, "0": {"text": "✗", "color": "red"}}}]},
                          {"id": "custom.cellOptions", "value": {"type": "color-text"}}]
            overrides.append({"matcher": {"id": "byName", "options": name}, "properties": props})
        self.panels.append({"type": "table", "title": title, "id": self._id(), "datasource": DS,
                            "gridPos": {"h": h, "w": w, "x": x, "y": self.y},
                            "targets": self.targets([(c[0], "") for c in cols], instant=True, fmt="table"),
                            "transformations": [{"id": "merge", "options": {}},
                                                {"id": "organize", "options": {"excludeByName": {k: True for k in hide}, "renameByName": {**rename, "site": "Site", "vm": "VM", "customer": "Khách hàng", "cluster": "Cluster", "host": "Host ESXi", "ip": "IP", "datastore": "Datastore", "type": "Loại"}}}],
                            "fieldConfig": {"defaults": {"custom": {"align": "auto", "filterable": True}}, "overrides": overrides},
                            "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}, **({"sortBy": [{"displayName": sort, "desc": True}]} if sort else {})}})

    def text(self, md, x=0, w=24, h=3):
        self.panels.append({"type": "text", "title": "", "id": self._id(), "gridPos": {"h": h, "w": w, "x": x, "y": self.y},
                            "options": {"mode": "markdown", "content": md}})

    def nl(self, h):
        self.y += h


RED1 = [{"color": "green", "value": None}, {"color": "red", "value": 1}]
YEL1 = [{"color": "green", "value": None}, {"color": "#EAB839", "value": 1}]
PCT = [{"color": "green", "value": None}, {"color": "#EAB839", "value": 80}, {"color": "red", "value": 90}]


def grouped(agg, metric, sel, k, extra=""):
    return f'label_replace(topk({k}, {agg} by ($groupby) ({metric}{{{sel}}}{extra})), "$groupby", "(chưa xác định)", "$groupby", "")'


# ================================================================ 1. Tổng quan
def overview():
    b = Board()
    b.row("Tình trạng")
    b.stat("VM online", f"sum(vm_up{{{VM}}})", 0)
    b.stat("VM tắt nguồn", f"count(vm_power_on{{{VM}}} == 0) or vector(0)", 4, color_mode="none")
    b.stat("VM mất kết nối", f"count(vm_up{{{VM}}} == 0 and vm_power_on{{{VM}}} == 1) or vector(0)", 8, steps=RED1)
    b.stat("Host ESXi kết nối", f"sum(vmhost_up{{{HOST}}})", 12)
    b.stat("Host mất kết nối", f"count(vmhost_up{{{HOST}}} == 0 and vmhost_maintenance{{{HOST}}} == 0) or vector(0)", 16, steps=RED1)
    b.stat("Datastore ≥ 85%", 'count(vmdatastore_usage_percent{site=~"$site"} >= 85) or vector(0)', 20, steps=YEL1)
    b.nl(4)
    b.stat("CPU trung bình VM", f"avg(vm_cpu_usage_percent{{{VM}}}{UP})", 0, unit="percent", steps=PCT, decimals=1)
    b.stat("RAM trung bình VM", f"avg(vm_memory_usage_percent{{{VM}}}{UP})", 4, unit="percent", steps=PCT, decimals=1)
    b.stat("Network in", f"sum(vm_network_receive_bytes_per_second{{{VM}}})", 8, unit="Bps", color_mode="none")
    b.stat("Network out", f"sum(vm_network_transmit_bytes_per_second{{{VM}}})", 12, unit="Bps", color_mode="none")
    b.stat("VM đang cảnh báo", f"count(vm_alerts_active{{{VM}}} > 0) or vector(0)", 16, steps=YEL1)
    b.stat("Số khách hàng", f'count(count by (customer) (vm_up{{{VM}, customer!=""}})) or vector(0)', 20, color_mode="none")
    b.nl(4)
    b.row("Xu hướng (đổi ô \"Nhóm theo\" để chia theo site / khách hàng / cluster / host / VM)")
    b.ts("CPU trung bình VM theo ${groupby:text}", [(grouped("avg", "vm_cpu_usage_percent", VM, 15, UP), "{{$groupby}}")], 0, maxv=100)
    b.ts("RAM trung bình VM theo ${groupby:text}", [(grouped("avg", "vm_memory_usage_percent", VM, 15, UP), "{{$groupby}}")], 12, maxv=100)
    b.nl(8)
    b.ts("Network in theo ${groupby:text}", [(grouped("sum", "vm_network_receive_bytes_per_second", VM, 10), "{{$groupby}}")], 0, unit="Bps")
    b.ts("Network out theo ${groupby:text}", [(grouped("sum", "vm_network_transmit_bytes_per_second", VM, 10), "{{$groupby}}")], 12, unit="Bps")
    b.nl(8)
    b.ts("Disk read theo ${groupby:text}", [(grouped("sum", "vm_disk_read_bytes_per_second", VM, 10), "{{$groupby}}")], 0, unit="Bps")
    b.ts("Disk write theo ${groupby:text}", [(grouped("sum", "vm_disk_write_bytes_per_second", VM, 10), "{{$groupby}}")], 12, unit="Bps")
    b.nl(8)
    b.row("Hạ tầng")
    b.bar("Host CPU cao nhất", f"topk(10, vmhost_cpu_usage_percent{{{HOST}}})", "{{host}} ({{cluster}})", 0, h=9)
    b.bar("Host RAM cao nhất", f"topk(10, vmhost_memory_usage_percent{{{HOST}}})", "{{host}} ({{cluster}})", 8, h=9)
    b.bar("Datastore đầy nhất", 'topk(10, vmdatastore_usage_percent{site=~"$site"})', "{{datastore}} ({{site}})", 16, h=9)
    b.nl(9)
    return "Tổng quan", "vm-monitor-overview", b, [V_SITE, V_CUST, V_CLUSTER, V_ESXI, V_GROUP]


# ================================================================ 2. VM
def vms():
    b = Board()
    b.row("Danh sách VM (lọc bằng các ô phía trên, bấm tiêu đề cột để sắp xếp)")
    b.table("VM", [
        (f"vm_cpu_usage_percent{{{VM}}}", "CPU %", "percent", "gauge"),
        (f"vm_memory_usage_percent{{{VM}}}", "RAM %", "percent", "gauge"),
        (f"vm_disk_max_usage_percent{{{VM}}}", "Disk %", "percent", "gauge"),
        (f"vm_memory_total_bytes{{{VM}}}", "RAM", "bytes", None),
        (f"vm_network_receive_bytes_per_second{{{VM}}}", "Net in", "Bps", None),
        (f"vm_network_transmit_bytes_per_second{{{VM}}}", "Net out", "Bps", None),
        (f"vm_alerts_active{{{VM}}}", "Cảnh báo", "none", None),
        (f"vm_up{{{VM}}}", "Online", "none", "bool"),
    ], h=14, sort="CPU %")
    b.nl(14)
    b.row("Dùng nhiều nhất")
    b.bar("Top 15 VM theo CPU", f"topk(15, vm_cpu_usage_percent{{{VM}}})", "{{vm}}", 0)
    b.bar("Top 15 VM theo RAM", f"topk(15, vm_memory_usage_percent{{{VM}}})", "{{vm}}", 8)
    b.bar("Top 15 VM theo Disk (phân vùng đầy nhất)", f"topk(15, vm_disk_max_usage_percent{{{VM}}})", "{{vm}}", 16)
    b.nl(10)
    b.bar("Top 15 VM theo Network in", f"topk(15, vm_network_receive_bytes_per_second{{{VM}}})", "{{vm}}", 0, unit="Bps", maxv=None, plain=True)
    b.bar("Top 15 VM theo Network out", f"topk(15, vm_network_transmit_bytes_per_second{{{VM}}})", "{{vm}}", 8, unit="Bps", maxv=None, plain=True)
    b.bar("Top 15 VM theo Disk I/O", f"topk(15, vm_disk_read_bytes_per_second{{{VM}}} + vm_disk_write_bytes_per_second{{{VM}}})", "{{vm}}", 16, unit="Bps", maxv=None, plain=True)
    b.nl(10)
    b.row("Chi tiết VM đã chọn ở ô \"VM (chi tiết)\"")
    b.ts("CPU", [(f"vm_cpu_usage_percent{{{VMD}}}", "{{vm}}")], 0, maxv=100)
    b.ts("RAM", [(f"vm_memory_usage_percent{{{VMD}}}", "{{vm}}")], 12, maxv=100)
    b.nl(8)
    b.ts("Network", [(f"vm_network_receive_bytes_per_second{{{VMD}}}", "{{vm}} in"), (f"-vm_network_transmit_bytes_per_second{{{VMD}}}", "{{vm}} out")], 0, unit="Bps", desc="Âm = chiều ra")
    b.ts("Disk I/O", [(f"vm_disk_read_bytes_per_second{{{VMD}}}", "{{vm}} read"), (f"-vm_disk_write_bytes_per_second{{{VMD}}}", "{{vm}} write")], 12, unit="Bps", desc="Âm = ghi")
    b.nl(8)
    b.ts("Disk (phân vùng đầy nhất)", [(f"vm_disk_max_usage_percent{{{VMD}}}", "{{vm}}")], 0, w=24, maxv=100, h=7)
    b.nl(7)
    return "VM", "vm-monitor-vms", b, [V_SITE, V_CUST, V_CLUSTER, V_ESXI, V_Q, V_VM]


# ================================================================ 3. Khách hàng
def customers():
    C = 'site=~"$site", customer=~"$customer", vm=~"(?i).*${q:regex}.*"'
    CK = C + ', customer!=""'
    b = Board()
    b.text("Chọn **Khách hàng** ở ô phía trên (để *All* để xem xếp hạng tất cả khách). Khách hàng được portal nhận diện "
           "theo thứ tự nguồn trong *Cài đặt → Khách hàng* (gán tay, quy tắc tên, tag, attribute, tên VM…).", h=2)
    b.nl(2)
    b.row("Tóm tắt")
    b.stat("Khách hàng", f"count(count by (customer) (vm_up{{{CK}}})) or vector(0)", 0, color_mode="none")
    b.stat("VM", f"count(vm_up{{{C}}})", 4, color_mode="none")
    b.stat("VM online", f"sum(vm_up{{{C}}})", 8)
    b.stat("VM tắt nguồn", f"count(vm_power_on{{{C}}} == 0) or vector(0)", 12, color_mode="none")
    b.stat("CPU trung bình", f'avg(vm_cpu_usage_percent{{{C}}} and on(site, vm) vm_up == 1)', 16, unit="percent", steps=PCT, decimals=1)
    b.stat("RAM được cấp", f"sum(vm_memory_total_bytes{{{C}}} and on(site, vm) vm_power_on == 1)", 20, unit="bytes", color_mode="none")
    b.nl(4)
    b.row("Xếp hạng khách hàng")
    b.bar("Số VM đang chạy", f"topk(20, count by (customer) (vm_power_on{{{CK}}} == 1))", "{{customer}}", 0, unit="none", maxv=None, plain=True, h=12)
    b.bar("Tổng CPU (% cộng dồn các VM)", f"topk(20, sum by (customer) (vm_cpu_usage_percent{{{CK}}}))", "{{customer}}", 8, unit="percent", maxv=None, plain=True, h=12)
    b.bar("RAM được cấp (VM đang chạy)", f"topk(20, sum by (customer) (vm_memory_total_bytes{{{CK}}} and on(site, vm) vm_power_on == 1))", "{{customer}}", 16, unit="bytes", maxv=None, plain=True, h=12)
    b.nl(12)
    b.bar("Network in", f"topk(20, sum by (customer) (vm_network_receive_bytes_per_second{{{CK}}}))", "{{customer}}", 0, unit="Bps", maxv=None, plain=True, h=12)
    b.bar("Network out", f"topk(20, sum by (customer) (vm_network_transmit_bytes_per_second{{{CK}}}))", "{{customer}}", 8, unit="Bps", maxv=None, plain=True, h=12)
    b.bar("Số VM đang cảnh báo", f"topk(20, count by (customer) (vm_alerts_active{{{CK}}} > 0))", "{{customer}}", 16, unit="none", maxv=None, plain=True, h=12)
    b.nl(12)
    b.row("Xu hướng theo khách hàng")
    b.ts("Tổng CPU theo khách (top 10)", [(f"topk(10, sum by (customer) (vm_cpu_usage_percent{{{CK}}}))", "{{customer}}")], 0, unit="percent")
    b.ts("Network in + out theo khách (top 10)", [(f"topk(10, sum by (customer) (vm_network_receive_bytes_per_second{{{CK}}} + vm_network_transmit_bytes_per_second{{{CK}}}))", "{{customer}}")], 12, unit="Bps")
    b.nl(8)
    b.row("VM của khách hàng đã chọn")
    b.table("VM", [
        (f"vm_cpu_usage_percent{{{C}}}", "CPU %", "percent", "gauge"),
        (f"vm_memory_usage_percent{{{C}}}", "RAM %", "percent", "gauge"),
        (f"vm_disk_max_usage_percent{{{C}}}", "Disk %", "percent", "gauge"),
        (f"vm_memory_total_bytes{{{C}}}", "RAM", "bytes", None),
        (f"vm_network_receive_bytes_per_second{{{C}}}", "Net in", "Bps", None),
        (f"vm_network_transmit_bytes_per_second{{{C}}}", "Net out", "Bps", None),
        (f"vm_power_on{{{C}}}", "Bật nguồn", "none", "bool"),
    ], h=14, sort="CPU %")
    b.nl(14)
    return "Khách hàng", "vm-monitor-customers", b, [V_SITE, V_CUST, V_Q]


# ================================================================ 4. Host ESXi
def hosts():
    b = Board()
    b.row("Tình trạng")
    b.stat("Host", f"count(vmhost_up{{{HOST}}})", 0, color_mode="none")
    b.stat("Đang kết nối", f"sum(vmhost_up{{{HOST}}})", 4)
    b.stat("Bảo trì", f"sum(vmhost_maintenance{{{HOST}}})", 8, steps=YEL1)
    b.stat("Mất kết nối", f"count(vmhost_up{{{HOST}}} == 0 and vmhost_maintenance{{{HOST}}} == 0) or vector(0)", 12, steps=RED1)
    b.stat("CPU trung bình", f"avg(vmhost_cpu_usage_percent{{{HOST}}} and vmhost_up{{{HOST}}} == 1)", 16, unit="percent", steps=PCT, decimals=1)
    b.stat("RAM trung bình", f"avg(vmhost_memory_usage_percent{{{HOST}}} and vmhost_up{{{HOST}}} == 1)", 20, unit="percent", steps=PCT, decimals=1)
    b.nl(4)
    b.row("Host")
    b.table("Host ESXi", [
        (f"vmhost_cpu_usage_percent{{{HOST}}}", "CPU %", "percent", "gauge"),
        (f"vmhost_memory_usage_percent{{{HOST}}}", "RAM %", "percent", "gauge"),
        (f"vmhost_memory_total_bytes{{{HOST}}}", "RAM", "bytes", None),
        (f"vmhost_vms_running{{{HOST}}}", "VM chạy", "none", None),
        (f"vmhost_vms{{{HOST}}}", "VM", "none", None),
        (f"vmhost_network_receive_bytes_per_second{{{HOST}}}", "Net in", "Bps", None),
        (f"vmhost_network_transmit_bytes_per_second{{{HOST}}}", "Net out", "Bps", None),
        (f"vmhost_up{{{HOST}}}", "Kết nối", "none", "bool"),
        (f"vmhost_maintenance{{{HOST}}}", "Bảo trì", "none", None),
    ], h=14, sort="CPU %")
    b.nl(14)
    b.row("Theo thời gian")
    b.ts("CPU từng host", [(f"vmhost_cpu_usage_percent{{{HOST}}}", "{{host}}")], 0, maxv=100, h=10)
    b.ts("RAM từng host", [(f"vmhost_memory_usage_percent{{{HOST}}}", "{{host}}")], 12, maxv=100, h=10)
    b.nl(10)
    b.ts("CPU trung bình theo cluster", [(f"avg by (cluster) (vmhost_cpu_usage_percent{{{HOST}}} and vmhost_up{{{HOST}}} == 1)", "{{cluster}}")], 0, maxv=100)
    b.ts("RAM trung bình theo cluster", [(f"avg by (cluster) (vmhost_memory_usage_percent{{{HOST}}} and vmhost_up{{{HOST}}} == 1)", "{{cluster}}")], 12, maxv=100)
    b.nl(8)
    b.ts("Network in từng host", [(f"topk(15, vmhost_network_receive_bytes_per_second{{{HOST}}})", "{{host}}")], 0, unit="Bps")
    b.ts("Số VM đang chạy từng host", [(f"vmhost_vms_running{{{HOST}}}", "{{host}}")], 12, unit="none")
    b.nl(8)
    return "Host ESXi", "vm-monitor-hosts", b, [V_SITE, V_CLUSTER, V_ESXI]


# ================================================================ 5. Datastore
def datastores():
    b = Board()
    b.row("Tình trạng")
    b.stat("Datastore", f"count(vmdatastore_capacity_bytes{{{DSM}}})", 0, color_mode="none")
    b.stat("Tổng dung lượng", f"sum(vmdatastore_capacity_bytes{{{DSM}}})", 4, unit="bytes", color_mode="none")
    b.stat("Còn trống", f"sum(vmdatastore_free_bytes{{{DSM}}})", 8, unit="bytes", color_mode="none")
    b.stat("Đã dùng", f"(1 - sum(vmdatastore_free_bytes{{{DSM}}}) / sum(vmdatastore_capacity_bytes{{{DSM}}})) * 100", 12, unit="percent", steps=PCT, decimals=1)
    b.stat("≥ 85%", f"count(vmdatastore_usage_percent{{{DSM}}} >= 85) or vector(0)", 16, steps=YEL1)
    b.stat("Không truy cập được", f"count(vmdatastore_accessible{{{DSM}}} == 0) or vector(0)", 20, steps=RED1)
    b.nl(4)
    b.row("Datastore")
    b.table("Datastore", [
        (f"vmdatastore_usage_percent{{{DSM}}}", "Đã dùng %", "percent", "gauge"),
        (f"vmdatastore_free_bytes{{{DSM}}}", "Còn trống", "bytes", None),
        (f"vmdatastore_capacity_bytes{{{DSM}}}", "Dung lượng", "bytes", None),
        (f"vmdatastore_provisioned_percent{{{DSM}}}", "Đã cấp phát %", "percent", None),
        (f"vmdatastore_accessible{{{DSM}}}", "Truy cập được", "none", "bool"),
    ], h=12, sort="Đã dùng %")
    b.nl(12)
    b.row("Dung lượng")
    b.bar("% đã dùng", f"topk(20, vmdatastore_usage_percent{{{DSM}}})", "{{datastore}}", 0, w=12, h=12)
    b.bar("% đã cấp phát (thin provisioning, >100% = cấp vượt dung lượng thật)", f"topk(20, vmdatastore_provisioned_percent{{{DSM}}})", "{{datastore}}", 12, w=12, h=12, maxv=200)
    b.nl(12)
    b.ts("% đã dùng theo thời gian", [(f"vmdatastore_usage_percent{{{DSM}}}", "{{datastore}}")], 0, maxv=100, h=9)
    b.ts("Dung lượng còn trống theo thời gian", [(f"vmdatastore_free_bytes{{{DSM}}}", "{{datastore}}")], 12, unit="bytes", h=9)
    b.nl(9)
    return "Datastore", "vm-monitor-datastores", b, [V_SITE, V_DS]


# ================================================================ xuất file
def dashboard(title, uid, board, variables, order):
    return {
        "__inputs": [{"name": "DS_PROMETHEUS", "label": "Prometheus", "description": "Prometheus đang scrape /metrics của VM Monitor Portal",
                      "type": "datasource", "pluginId": "prometheus", "pluginName": "Prometheus"}],
        "__requires": [{"type": "grafana", "id": "grafana", "name": "Grafana", "version": "9.0.0"},
                       {"type": "datasource", "id": "prometheus", "name": "Prometheus", "version": "1.0.0"}],
        "title": f"VM Monitor · {order}. {title}", "uid": uid, "tags": [TAG], "timezone": "browser",
        "schemaVersion": 39, "version": 1, "editable": True, "refresh": "30s", "time": {"from": "now-6h", "to": "now"},
        "links": [{"title": "VM Monitor", "type": "dashboards", "tags": [TAG], "asDropdown": False, "includeVars": True, "keepTime": True, "targetBlank": False}],
        "templating": {"list": variables}, "annotations": {"list": []}, "panels": board.panels,
    }


def main():
    out_import = os.path.join(HERE, "dashboards")
    out_stack = os.path.join(HERE, "stack", "grafana", "dashboards")
    os.makedirs(out_import, exist_ok=True)
    os.makedirs(out_stack, exist_ok=True)
    for f in os.listdir(out_stack):  # bỏ bản cũ (1 dashboard gộp)
        if f.endswith(".json"):
            os.remove(os.path.join(out_stack, f))
    for order, fn in enumerate([overview, vms, customers, hosts, datastores], 1):
        title, uid, board, variables = fn()
        d = dashboard(title, uid, board, variables, order)
        name = f"{order}-{uid.replace('vm-monitor-', '')}.json"
        with open(os.path.join(out_import, name), "w", encoding="utf-8") as fh:
            json.dump(d, fh, ensure_ascii=False, indent=2)
        st = json.loads(json.dumps(d))
        st.pop("__inputs"), st.pop("__requires")
        txt = json.dumps(st, ensure_ascii=False, indent=2).replace("${DS_PROMETHEUS}", "prometheus")
        with open(os.path.join(out_stack, name), "w", encoding="utf-8") as fh:
            fh.write(txt)
        print(f"{name}: {len([p for p in d['panels'] if p['type'] != 'row'])} panel, biến: {', '.join(v['name'] for v in variables)}")


if __name__ == "__main__":
    main()
