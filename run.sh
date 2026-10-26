#!/usr/bin/env bash
# CraftPort CLI 运行脚本 (Linux / macOS)，优先使用 Java 21
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/target/CraftPort.jar"
NEW="$JAR.new"
BAK="$JAR.bak"

# 应用 update 暂存的新版本（运行中的 jar 无法覆盖，所以替换放在启动前）
if [ -f "$NEW" ]; then
    if mv -f "$JAR" "$BAK" 2>/dev/null && mv -f "$NEW" "$JAR" 2>/dev/null; then
        echo "Applied update: CraftPort.jar (previous version saved as CraftPort.jar.bak)"
    else
        echo "Warning: could not apply $NEW (is another instance running?) — launching the current jar."
    fi
fi

[ -f "$JAR" ] || { echo "Not found: $JAR — run 'mvn package' first."; exit 1; }

java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }

CANDIDATES=()
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] && CANDIDATES+=("$JAVA_HOME/bin/java")
for d in /usr/lib/jvm/* "$HOME/.sdkman/candidates/java"/* "$HOME/.jdks"/*; do
    [ -x "$d/bin/java" ] && CANDIDATES+=("$d/bin/java")
done
command -v java >/dev/null 2>&1 && CANDIDATES+=("java")

for c in "${CANDIDATES[@]}"; do
    [ "$(java_major "$c")" = "21" ] && exec "$c" -jar "$JAR" "$@"
done
exec java -jar "$JAR" "$@"
