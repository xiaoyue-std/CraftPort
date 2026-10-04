"""面板 Mods 下载 + 自动依赖补全测试（需先 `server web` + 至少一台 fabric 服务端）。

主用例: CurseForge 安装 sodium → 自动补全 required 依赖 fabric-api。
"""
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
        return json.load(e)

servers = api("/api/servers")["servers"]
assert servers, "no deployed servers"
d = servers[0]["dir"]
name = servers[0]["name"]
mods_dir = f"{d}/mods"
import os
os.makedirs(mods_dir, exist_ok=True)
# 开头先清掉本测试的历史产物,保证可重复跑
for f in list(os.listdir(mods_dir)):
    if any(k in f.lower() for k in ("sodium", "lithium", "fabric-api")):
        os.remove(os.path.join(mods_dir, f))
before = set(os.listdir(mods_dir))
print("target:", name)

def wait_install(timeout=900):
    for _ in range(timeout // 2):
        st = api("/api/mods/status")
        if st.get("done"):
            return st
        time.sleep(2)
    raise AssertionError("install timeout")

def mods_with(keyword):
    return sorted(f for f in os.listdir(mods_dir) if keyword in f.lower())

# 1. 双源搜索
r = api("/api/mods/search?query=sodium&source=curseforge&type=mod")
assert r["results"], "CF search empty"
cf_id = next(x["id"] for x in r["results"] if x["name"].lower() == "sodium")
print("== CF search OK, sodium id:", cf_id, "==")

r = api("/api/mods/search?query=sodium&source=modrinth&type=mod")
assert r["results"], "MR search empty"
mr_id = next(x["id"] for x in r["results"] if x["name"].lower() == "sodium")
print("== MR search OK, project:", mr_id, "==")

# 2. CF 安装 sodium → 依赖补全 fabric-api
api("/api/mods/install", {"dir": d, "source": "curseforge", "type": "mod", "id": cf_id, "loader": "fabric"})
st = wait_install()
assert not st.get("error"), st
print("== CF install: installed =", len(st["installed"]), "skipped =", len(st["skipped"]), "failed =", st["failed"], "==")
assert st["installed"], "nothing installed"
assert st["failed"] == [], st["failed"]
fa = mods_with("fabric-api")
assert fa, f"fabric-api not auto-installed: {st}"
print("== dependency fabric-api auto-installed:", fa, "==")

# 3. MR 安装 lithium(文件名与 CF 装过的不同;其 required 依赖 fabric-api 已在→跳过)
r = api("/api/mods/search?query=lithium&source=modrinth&type=mod")
assert r["results"], "MR lithium search empty"
mr_lithium = next(x["id"] for x in r["results"] if x["name"].lower() == "lithium")
api("/api/mods/install", {"dir": d, "source": "modrinth", "type": "mod", "id": mr_lithium, "loader": "fabric"})
st = wait_install()
assert not st.get("error"), st
assert st["installed"], st
assert st["failed"] == [] or all("no " in x or "build" in x for x in st["failed"]), st["failed"]
print("== MR lithium install: installed =", st["installed"], "skipped =", len(st["skipped"]), "==")

# 4. 重复安装 → 全部跳过
api("/api/mods/install", {"dir": d, "source": "curseforge", "type": "mod", "id": cf_id, "loader": "fabric"})
st = wait_install()
assert not st.get("error"), st
assert not st["installed"], st
assert len(st["skipped"]) >= 2, st
print("== reinstall skip OK: skipped =", len(st["skipped"]), "==")

# 5. 清理测试期间新增的文件
added = set(os.listdir(mods_dir)) - before
for f in added:
    os.remove(os.path.join(mods_dir, f))
print("== cleaned", len(added), "test files ==")
print("MODS-ALL-PASS")
