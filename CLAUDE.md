# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

CraftPort 是 Minecraft 客户端 + 服务端一体化 CLI：**纯 CLI、单 jar（shade 打包，仅依赖 Gson）、零框架**。主发展方向是 Linux 服务端部署与管理（`server deploy/start/service/web`）。

部分核心逻辑在源码注释中标注了原始实现来源（如 `ModNet.vb`、`ModLaunch.vb`）——排查移植相关逻辑时以这些注释为参照。文档（README/AGENTS）不写来源项目。

## 构建与运行

要求 JDK 21+ / Maven 3.8+。

```bash
mvn package                                  # 产物 target/CraftPort.jar，主类 craftport.cli.CliMain
./run.sh                                     # Linux/macOS，自动挑选 Java 21
run.cmd                                      # Windows
java -jar target/CraftPort.jar <command>     # 不带参数进入交互菜单
```

**本机（Windows）注意**：`mvn` 不在 PATH。已验证可用的 Maven：

```bash
export JAVA_HOME="D:/Environment/JDK/jdk-21.0.10+7"
MVN="/c/Users/Ash/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
"$MVN" -q package -DskipTests
```

（`JAVA_HOME` 同上；该 Maven 来自全局 `~/.m2/wrapper/dists`，非项目自带。）

## 测试与验证

仓库**没有单元测试框架/CI**；`pom.xml` 无 JUnit，`src/test` 不存在。验证靠三层手动手段，改完对应链路后跑相应的：

```bash
# 1. CLI 冒烟（无网络也能跑前两项）
java -jar target/CraftPort.jar help
java -jar target/CraftPort.jar status
java -jar target/CraftPort.jar versions
java -jar target/CraftPort.jar server list

# 2. Web 面板 API 回归（先在另一终端跑 server web，且已部署至少一台服务端）
java -jar target/CraftPort.jar server web
python test/webpanel_test.py   # 启动→Done→控制台命令→优雅停止
python test/props_test.py      # server.properties 编辑：unicode/反斜杠往返、运行中拒绝、恢复
python test/mods_test.py       # 双源搜索 + CF 装 sodium 自动补 fabric-api + 去重

# 3. 端到端启动/加载器（会真实下载约 600MB，非启动链路改动不必重跑）
javac -cp target/CraftPort.jar -d test test/LaunchTest.java
java -cp "target/CraftPort.jar;test" LaunchTest <gameDir> <version>
javac -cp target/CraftPort.jar -d test test/LoaderTest.java
java -cp "target/CraftPort.jar;test" LoaderTest <gameDir> <mc> <fabric|forge|optifine|all>
```

## 架构

```
craftport.cli      CliMain（入口/子命令/交互菜单）、WebPanel（JDK HttpServer + JSON API）、UpdateNotice（启动更新提示）
craftport.minecraft  全部领域逻辑：安装/加载器/启动/整合包/模组/服务端/登录
craftport.net      Net（重试梯子 + BMCLAPI 镜像换算）、Downloader（分片下载）
craftport.base     Os（平台适配）、Config（全局+版本级设置）、Log、Task（线程池+取消异常）、Json
                   Updater（拉源码+javac 编译）、BuildInfo（jar 内构建信息）
src/main/resources/craftport/web.html   面板单文件 SPA（约 1.1k 行，内嵌 JS）
```

关键分层约束：**领域逻辑一律放 `craftport.minecraft`，`CliMain`/`WebPanel` 只做展示与参数解析**。新增 CLI 命令 = `CliMain.dispatch` 加 case + `printHelp` 补一行；新增面板 API = `WebPanel.handle` 加路由 + 前端 `web.html` 对接。

### 登录

- 离线：`login <name>` 写缓存；UUID 算法与 PCL2 的 `McLoginLegacyUuid` 兼容（同一用户名在不同启动器下 UUID 一致）。
- 微软：`login --microsoft` 走 Device Code 流（需 `CRAFTPORT_MS_CLIENT_ID`）；成功后的刷新令牌存 `config.json`。
- `launch` 的默认策略：能刷新缓存的微软会话就用微软，否则退化到离线；`--offline` / `--microsoft` 可强制。
- 用户可见的登录错误信息用英文（与 CLI 输出约定一致）；内部日志保持中文。

### 自更新（无二进制分发）

`update` 命令走「拉源码 → 本地编译」：`Updater` 从 GitHub 下载 `main` 源码 zip，用本机
JDK 的 `javac`/`jar` 编译（不需要 Maven），自检通过后安装。**不依赖 run 脚本**：
Linux/macOS 当场替换；Windows 运行中的 jar 被锁定（实测连 rename 都会 AccessDenied），
`Updater.scheduleApply` 写一个一次性 cmd/sh，进程退出后完成替换并自删。

必须知道的约束：

- **javac 的 `-cp` 只用于解析符号，不会打包依赖**——`Updater.compile` 会把运行时 classpath 里的
  jar（当前 jar 内含 Gson）解压并入新 jar，否则新包启动时 `NoClassDefFoundError`。
- 更新检查用 GitHub 提交 Atom feed（`commits/<ref>.atom`，**不限流**；API 未认证只有 60 次/小时），
  可精确到 sha：`/archive/{sha}.zip`。feed 不可达时退回 codeload 的 `Last-Modified` 头。
- 新 jar 必须通过自检（跑 `status`，含 Gson 加载路径）才会落位；**未捕获异常时 JVM 仍可能返回 0**，
  所以退出码和输出都要检查。
- 启动检查（`UpdateNotice`）：成功缓存 6 小时；失败/超时缓存 30 分钟（`ok=false`），
  主线程最多等 2 秒，超时就写失败缓存，避免连续执行命令时每次都卡在同一个超时。
  `update --check` 绕过缓存、实时查询（不受抑制）。
- `update-journal.txt` 记录目标提交；替换后首次启动比对 `build.properties` 提交来确认，
  清理源码工作目录，但**保留 `CraftPort.jar.bak` 供回滚**。
- 用户数据不参与更新：`config.json`、日志、游戏目录、`servers/` 全部在更新范围之外。
- `--version` 是自检用的隐藏命令（输出 `BuildInfo.label()`），源码构建显示 `dev`。

### 四条核心数据流

1. **客户端安装**：`InstallService` 拉 Mojang 版本清单写 `versions/{v}/{v}.json` → 首次启动时 `FileCompleter` 补全 client jar / libraries / assets / natives（经 `Downloader`，镜像优先）→ `ArgsBuilder` 生成 JVM+游戏参数 → `LaunchPipeline` 选 Java、解压 natives、老版本 lang 修复、起进程。
2. **加载器**：`ModLoader.install(FABRIC|FORGE|OPTIFINE)`。Fabric 直接下官方 profile json；Forge 解析安装器内 version.json + maven 库 + `data/client.lzma` 注入 classpath；OptiFine 无头子进程调官方 `doInstall`。
3. **服务端部署**：`ServerDeployer.deploy(kind, mc, dir, …)` 下载/无头安装服务端 → `writeCommonFiles` 生成 `eula.txt`/`server.properties`/`start.sh`/`start.bat`/`craftport-server.service` → `startCommand()` 是**脚本、systemd 单元、实际进程三处共用的唯一命令来源**（Forge/NeoForge 走 `@argfiles`，其余 `-jar`）。
4. **整合包**：`ModpackInstaller` 解 zip 读 `manifest.json` 推断 MC 版本与加载器 → 客户端模式装基础版本+加载器+mods/overrides；`deployServer` 一键部署服务端并打包配套客户端 zip。

### 运行期数据

- 配置/日志：`Os.dataDir()`（Win `%APPDATA%\CraftPort`，Linux XDG `~/.local/share/CraftPort`）下 `config.json` 与 `Logs/latest.log`；版本级设置在各版本 `CraftPort/Setup.json`。
- 服务端默认部署到 `{游戏目录}/servers/{mc}-{type}`，客户端包到 `clientpacks/`，自动下载的 Java 到 `runtime/`。
- Web 面板只登记**由它自己启动**的进程（`ServerManager` 注册表）；CLI/systemd 启动的实例靠端口探测标记为 running elsewhere。

## 跨文件才能看出的关键约束

- **平台差异只走 `base/Os`**（路径、可执行名、classpath 分隔符、natives classifier），**网络只走 `net/Net` + `net/Downloader`**。不要手写 HttpClient 或路径拼接绕过镜像/重试/限速逻辑。
- **镜像策略**：`Net.mirrorUrl()` 返回 null 表示无法换算，`Downloader` 据此回退官方源。BMCLAPI 有已知残缺（`java-runtime` 的 `all.json` 返回空），官方回退不能删。
- **CurseForge 路径段必须 RFC 3986 编码**（`CurseForge.encodePathSegment`：`+`→`%2B`、空格→`%20`）。`URLEncoder` 是表单编码，用在 URL path 上会 403。
- **worker 线程只 catch IOException 会让非受检异常静默杀死线程**（曾导致整合包少装文件却报成功）。并发步骤一律 `catch (IOException | RuntimeException)`。
- **`Library` 的 rules 语义**：存在 os 规则时默认禁用，最后一条命中的 allow/disallow 决定结果；继承版本（Fabric/Forge）的客户端 jar 在**根版本**文件夹（`McVersion.rootName`）。
- **CLI 模式启动即 `Log.setConsoleEnabled(false)`**：核心模块日志中文、只进文件；终端输出全部由 `CliMain` 用英文打印（ANSI 颜色需 Linux + tty + 无 `NO_COLOR`）。
- **`server web` 启动后主线程必须 `CountDownLatch.await()`**，否则 `CliMain` 末尾的 `System.exit` 立刻杀掉面板。
- **EULA 绝不代用户默认接受**：只有显式 `--accept-eula`/交互确认才写 `eula=true`。
- **文件系统的破坏性操作要设边界**：删除服务端只允许 `servers/` 下且带部署标记的目录，启动前做端口占用预检，属性编辑在服务器运行中拒绝写回（防停服回写覆盖）。
- **Windows 路径预检**：游戏目录含 `!` 或 `;` 会在启动前直接拒绝（classpath 特殊字符）。
- **依赖补全的主来源是 jar 内声明**（`fabric.mod.json` 的 depends / `mods.toml` 依赖块）——MCIMirror 代理的 CurseForge 元数据 dependencies 全为空；modId 经 Modrinth 解析，`fabric-*` 模块回落到 fabric-api。

更完整的踩坑清单见 `AGENTS.md` 的「已踩过的坑」一节。

## 约定

- 新功能：逻辑进 `craftport.minecraft`，展示层只调 API；面板新增设置项要同步 `Config` 常量（沿用现有键名风格）。
- CLI 与面板输出：CLI 终端英文；日志中文（仅文件）。面板 API 错误用错误码（如 `EDIT_RUNNING`），前端负责翻译。
- 临时脚本/测试代码放 `test/`；不要提交 `target/`、日志、截图（`.gitignore` 已覆盖）。
- Git：分支 `main`；提交信息中文一行式（参考历史 `db014c3`）。
- 代码风格：Java 21 record/switch 表达式，注释与 Javadoc 中文，4 空格缩进。

## 文档现状

README（2026-10 重写）已同步当前实现：单 jar、纯 CLI、`craftport-*` 服务名，不再提及来源项目。AGENTS.md 已同步包名与路径，但「当前进度」一节仍是旧快照，可能落后于代码。
- README 中 `server service install` 后的服务名以 `craftport-{部署目录名}.service` 为准。
