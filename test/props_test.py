"""server.properties 面板编辑 API 测试（需先 `server web` 并至少部署一台服务器）。"""
import json, time, urllib.request, urllib.parse, urllib.error

BASE = "http://127.0.0.1:8765"
def api(path, body=None):
    if body is not None:
        req = urllib.request.Request(BASE + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    else:
        req = urllib.request.Request(BASE + path)
    try:
        return json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e:
        # 错误响应体也是 JSON（如 409 EDIT_RUNNING）
        return json.load(e)

def props(d):
    return {p["k"]: p["v"] for p in api(f"/api/props?dir={urllib.parse.quote(d)}")["props"]}

def save(d, m):
    return api("/api/props", {"dir": d, "props": m})

servers = api("/api/servers")["servers"]
assert servers, "no deployed servers"
d = servers[0]["dir"]
print("target:", d)

def wait_stopped():
    # 刚启动就 stop 时,命令要等启动完成才被消费,放宽到 180s
    for _ in range(90):
        s = [x for x in api("/api/servers")["servers"] if x["dir"] == d][0]
        if not s["running"]:
            return
        time.sleep(2)

# 前置:确保服务器处于停止状态(编辑只允许在停止时进行)
if servers[0].get("running"):
    api("/api/stop", {"dir": d})
    wait_stopped()

orig = props(d)
assert "motd" in orig, "motd missing"
print("== loaded", len(orig), "keys ==")

# 1. 修改 + 新增键 + unicode/反斜杠往返
mod = dict(orig)
mod["motd"] = "CraftPort 测试 §aHello \\ path"
mod["craftport-test-key"] = "中文值"
assert save(d, mod)["ok"]
now = props(d)
assert now["motd"] == mod["motd"], now["motd"]
assert now["craftport-test-key"] == "中文值"
print("== save/load round-trip OK (unicode + backslash + new key) ==")

# 2. 运行中拒绝写回
r = api("/api/start", {"dir": d, "memory": 2048})
assert r.get("ok") or r.get("code") == "ALREADY_RUNNING", r
time.sleep(3)
r = save(d, mod)
assert r.get("code") == "EDIT_RUNNING", r
print("== running reject OK ==")

api("/api/stop", {"dir": d})
wait_stopped()

# 3. 清理测试键,恢复原值并校验
final = dict(orig)
final.pop("craftport-test-key", None)
assert save(d, final)["ok"]
assert props(d) == final
print("== restore OK ==")
print("PROPS-ALL-PASS")
