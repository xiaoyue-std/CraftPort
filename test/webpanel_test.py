import json, time, urllib.request

BASE = "http://127.0.0.1:8765"
def api(path, body=None):
    if body is not None:
        req = urllib.request.Request(BASE + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    else:
        req = urllib.request.Request(BASE + path)
    return json.load(urllib.request.urlopen(req))

servers = api("/api/servers")["servers"]
assert servers, "no deployed servers"
d = servers[0]["dir"]
print("target:", d)

print("== start ==", api("/api/start", {"dir": d, "memory": 2048}))

lines, since, done = [], 0, False
for _ in range(60):
    r = api(f"/api/logs?dir={urllib.parse.quote(d)}&since={since}")
    lines += r["lines"]; since = r["next"]
    if any("Done (" in l for l in r["lines"]): done = True; break
    if not r["alive"]: break
    time.sleep(2)
assert done, "server did not reach Done"
print("== server reached Done ==")

api("/api/command", {"dir": d, "cmd": "list"})
time.sleep(2)
r = api(f"/api/logs?dir={urllib.parse.quote(d)}&since={since}")
lines += r["lines"]; since = r["next"]
assert any("players online" in l for l in lines), "list command failed"
print("== console command works ==")

api("/api/stop", {"dir": d})
for _ in range(30):
    s = [x for x in api("/api/servers")["servers"] if x["dir"] == d][0]
    if s.get("exitCode") is not None and not s["running"]: break
    time.sleep(2)
print("== graceful stop, exit code:", s.get("exitCode"), "==")
print("ALL-PASS")
