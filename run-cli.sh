#!/usr/bin/env bash
# PCL Java Edition — CLI launcher (Linux / macOS), prefers Java 21
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$DIR"/target/*-cli.jar 2>/dev/null | head -1)
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "Not found: $JAR — run 'mvn package' first."; exit 1; }

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
