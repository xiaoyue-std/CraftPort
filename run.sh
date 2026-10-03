#!/usr/bin/env bash
# PCL Java Edition 运行脚本（Linux / macOS）
# 优先使用 Java 21 运行启动器；找不到时退而求其次（JavaFX 最低要求 Java 17）。
# 用法: ./run.sh [jar路径]
set -e

JAR="${1:-$(dirname "$0")/target/PCLJ.jar}"

if [ ! -f "$JAR" ]; then
    echo "未找到 $JAR，请先执行: mvn package"
    exit 1
fi

# 提取 java 主版本号（如 21.0.10 -> 21，1.8.0_392 -> 8）
java_major() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

# 收集候选 java
CANDIDATES=()
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] && CANDIDATES+=("$JAVA_HOME/bin/java")
for d in /usr/lib/jvm/* /opt/*jdk* /opt/*java* /usr/java/* \
         "$HOME/.sdkman/candidates/java"/* "$HOME/.jdks"/* "$HOME/.asdf/installs/java"/*; do
    [ -x "$d/bin/java" ] && CANDIDATES+=("$d/bin/java")
done
command -v java >/dev/null 2>&1 && CANDIDATES+=("java")

# 1) 精确的 Java 21 最优先
for c in "${CANDIDATES[@]}"; do
    [ "$(java_major "$c")" = "21" ] && exec "$c" -jar "$JAR"
done
# 2) 其次任何 >= 17（JavaFX 21 的最低要求）
for c in "${CANDIDATES[@]}"; do
    m="$(java_major "$c")"
    [ -n "$m" ] && [ "$m" -ge 17 ] 2>/dev/null && exec "$c" -jar "$JAR"
done
# 3) 最后 PATH 上的 java（版本不足时给出提示）
if [ ${#CANDIDATES[@]} -gt 0 ]; then
    echo "警告: 未找到 Java 21（最低要求 17），使用当前默认 java 运行。" >&2
    exec java -jar "$JAR"
fi
echo "错误: 未找到任何 Java，请先安装 JDK 21（如: sudo apt install openjdk-21-jdk）" >&2
exit 1
