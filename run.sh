#!/usr/bin/env bash
# CraftPort CLI 运行脚本 (Linux / macOS)，优先使用 Java 21
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/target/CraftPort.jar"
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
