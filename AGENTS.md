# AGENTS.md — CraftPort 项目指南

面向在此仓库工作的 AI 会话 / 新成员。**先读这份再动手。**

## 项目是什么

Plain Craft Launcher 2（VB.NET/WPF，源码在 `../PCL/`）的 Java 21 重构版。
**一套核心（`pcl.base/net/minecraft`）、两个前端**：JavaFX 图形界面（`pcl.ui`）
+ 纯英文 CLI（`pcl.cli`，产物无 JavaFX）。**主发展方向：Linux 服务端部署与管理。**

## 当前进度（2026-10 快照）

### 已完成且实测通过
- 客户端全链路：版本安装 → 库/资源补全 → 参数构建 → 真实启动（1.20.6 原版/Fabric/Forge 实测到渲染）
- 加载器安装：Fabric（官方 meta）、Forge 1.13+（官方安装器 `--installServer` 无头 + BMCLAPI）、OptiFine（官方 `doInstall` 无头）、NeoForge 服务端（maven 安装器 + BMCLAPI，1.20.2+，实机 1.21.1 启动到 Done）
- 双模组源：CurseForge（MCIMirror 代理 + forgecdn 直链）+ Modrinth（官方 API）；四种内容类型（mod/resourcepack/shader/datapack）分类落位
- 整合包：CurseForge zip 解析 → 基础版本+加载器+成员 mod 批量下载+overrides；客户端/服务端双模式；自动生成配套客户端包 zip
- 服务端：vanilla/Fabric/Forge/NeoForge/Paper 一键部署（ServerDeployer）、端口预检、start.sh 写 Java 绝对路径、systemd 单元生成与 `server service install`
- Web 面板：`server web`（127.0.0.1:8765），部署/启动/停止/实时日志（小窗可收起）/
  控制台命令/server.properties 编辑/Mods 下载（双源搜索 + 自动依赖补全 + 热门榜 +
  mc百科直达）/设置（内存/JVM 参数/下载限速/部署目录）/服务器详情（SLP 在线人数）/
  主机资源监控（CPU/内存/磁盘），API 全流程实测
- Java 管理：21 优先策略、Mojang 运行时自动下载（`ensureComponent`）、Linux 补执行权限
- UI：雪山侧栏、自绘标题栏、磁贴下载页、动画（Animate.java），快照验证通过

### Linux 实机验证（2026-10-03，Ubuntu 26.04）

- 构建产物两个 jar 正常；CLI 冒烟（help/status/versions/server list）通过，XDG 数据目录正确
- Fabric 1.20.6 一键部署：start.sh 执行权限 ✓、Java 绝对路径 ✓、systemd 单元生成 ✓
- 面板 API 全流程（启动→Done→控制台→优雅停止 exit 0）`ALL-PASS`，属性编辑 `PROPS-ALL-PASS`
- systemd 实装（`systemctl enable --now`）与 root 警告、ANSI 颜色仍未在实机跑过

### 未完成 / 待验证
- systemd 服务实装实测（单元文件已生成,未 `enable --now` 过）；root 运行提示未实测
- 微软登录需自备 Azure client_id（`PCL_MS_CLIENT_ID`），未实测
- 未移植：崩溃分析、皮肤站、统一通行证/authlib-injector、Forge 1.12-、NeoForge 客户端启动（服务端已支持）
- CurseForge 大模组文件列表首拉慢（镜像限速 ~30KB/s，770KB 的 search 响应要 25s，
  与客户端无关——wget 同样慢；热门榜单已把 pageSize 降到 15，有内存缓存）

## 环境（本机 Windows，注意 mvn 不在 PATH！）

```bash
export JAVA_HOME="D:/Environment/JDK/jdk-21.0.10+7"
MVN="D:/Dev_Project/PCL/tools/apache-maven-3.9.9/bin/mvn"   # tools/ 下，未装全局
"$MVN" -q package -DskipTests        # 产物: target/CraftPort.jar + pcl-java-1.0.0-cli.jar
```

## 验证手段（改完必须验）

- **编译**：上面的 mvn 命令；产物两个 jar
- **UI 快照**（免桌面）：`java -Dpclj.snapshot=target/shots -jar target/CraftPort.jar`
  自动截四个页面 PNG（MainApp.runSnapshots，页面间停顿已调好）
- **CLI 冒烟**：`java -jar target/CraftPort-cli.jar help` / `versions` / `status`
- **Web 面板 API**：`python test/webpanel_test.py`（需先 `server web` + 部署一个 fabric 服务端）
- **属性编辑 API**：`python test/props_test.py`（同样需 `server web`；覆盖 unicode/反斜杠往返、运行中拒绝、恢复原值）
- **Mods 下载/依赖补全**：`python test/mods_test.py`（需 `server web` + fabric 服务端；
  实测 CF 装 sodium 自动补 fabric-api、MR 装 lithium、重安装去重跳过）
- **端到端启动**：`test/LaunchTest.java`、`test/LoaderTest.java`（javac 编到 test/ 后跑，
  会真实下载约 600MB，慎用；历史上已验证过，非启动链路改动不必重跑）

## 模块地图（改哪里）

| 文件 | 职责 |
|---|---|
| `base/Os.java` | 平台分支的总开关（路径/分类器/分隔符）——**Linux 适配先看这里** |
| `base/Config.java` | 全部设置项 + 键名常量（JSON 存储，键名对齐 PCL2） |
| `base/Log.java` | 控制台开关 `setConsoleEnabled(false)`（CLI 静音核心中文日志） |
| `net/Net.java` | 重试梯子(10s→30s→4s) + BMCLAPI URL 换算 `mirrorUrl`（**注意：本文件 JsonObject 一律全限定名**） |
| `net/Downloader.java` | 分片下载 + SHA1 校验 + 镜像切换；`request()` 已设 60s 头超时 |
| `minecraft/McVersion.java` | inheritsFrom 合并、`rootName`（客户端 jar 定位）、Java 要求 |
| `minecraft/Library.java` | 坐标第 4 段 classifier、rules 过滤（**语义：有 os 规则默认禁用**）、`hasExplicitUrl` |
| `minecraft/CurseForge.java` | `cdnUrl` 用 `encodePathSegment`（**URL 编码是坑，见下**）、`filesBatch` POST |
| `minecraft/ModpackInstaller.java` | 整合包双模式；worker 捕获 `IOException | RuntimeException`；结果按磁盘文件数统计 |
| `minecraft/ServerDeployer.java` | 部署/startCommand（脚本·unit·进程三处共用）/端口预检/systemd 单元 |
| `minecraft/ServerManager.java` | 面板托管进程注册表（捕获输出、stdin 命令） |
| `minecraft/ServerProperties.java` | server.properties 读写（Properties 转义往返；**注释里别写 `\u` 字面量**——词法层就报 illegal unicode escape） |
| `minecraft/ServerPing.java` | Server List Ping 手写 varint 帧（查询在线人数/MOTD，外部启动的实例也有效）；失败返回 null 由调用方兜底 |
| `minecraft/ModsService.java` | 面板 Mod 下载 + 依赖补全。**MCIMirror 返回的 CF 元数据 dependencies 全为空**，
依赖主来源是 jar 内声明（fabric.mod.json depends / mods.toml），modId 经 Modrinth 解析；
`fabric-*` 模块 id 回落到 fabric-api。mcmod 直链：解析 search.mcmod.cn 服务端渲染 HTML 取首条
class/modpack 链接（内存缓存），失败回退搜索页。中文搜索：关键词含 CJK 时经 mcmod 搜索页
桥接英文名（标题「钠 (Sodium)」），前 3 个英文名到目标源检索融合，镜像限速下总时长可控 |
| `cli/WebPanel.java` | HttpServer 路由；`server web` 需主线程 `CountDownLatch.await()` 保活（**System.exit 会杀面板**） |
| `ui/Animate.java` | 全部 UI 动画（CSS effect 与 Java setEffect 冲突：启动按钮/磁贴的阴影由 Java 管） |

## 已踩过的坑（别再踩）

1. **forgecdn URL 必须按 RFC 3986 路径段编码**：`+`→`%2B`、空格→`%20`，否则 403/URI 非法。
   `URLEncoder` 是表单编码（空格→+），不能用于路径（`CurseForge.encodePathSegment` 是正确实现）。
2. **非受检异常穿透 worker 线程**：只 catch IOException 会让线程带着 IllegalArgumentException
   静默死亡 → 整合包少装文件还"成功"。worker 一律 `catch (IOException | RuntimeException)`。
3. **`FileVisitor` 方法返回 `FileVisitResult`**，不是 Path；`BasicFileAttributes` 在
   `java.nio.file.attribute` 包。
4. **`Path.resolve` 没有多参重载**，多段要链式或 `Path.of(a, b, c)`。
5. **CG 参数 `UnlockExperimentalVMOptions` 必须在 G1 细分参数之前**。
6. **rules 过滤语义**：有 os 规则时默认禁用，最后一条命中的 allow/disallow 决定结果
   （LWJGL 多平台条目会重复出现，过滤错就重复加载）。
7. **继承版本（Fabric/Forge）的客户端 jar 在根版本文件夹**（`McVersion.rootName`）。
8. **JavaFX 从 classpath 跑**：主类必须是不继承 Application 的 `pcl.Launcher`。
9. **CLI 模式先 `Log.setConsoleEnabled(false)`**，终端输出全部由 CLI 自己用英文打印。
10. **BMCLAPI 对 java-runtime all.json 返回空**，必须保留官方回退；`gameVersion` 服务端过滤
    无效，文件列表要客户端过滤 + 内存缓存。

## 约定

- CLI 终端输出**纯英文**（`CliMain`），核心模块日志中文（只进文件）
- 面板/部署类新功能：逻辑放 `pcl.minecraft`（GUI 将来可复用），CLI 只做展示层
- 新设置项：键名沿用 PCL2 命名（如 `LaunchArgumentIndieV2`）
- git：master 分支；提交信息中文一行式（现状 `89a9cd0` 面板、`ecbfe8a` 初始）
- 测试脚本/临时代码放 `test/`，**不要**把 target/、日志、截图提交进 git

## 下一步（按优先级）

1. systemd 服务实装实测（`server service install` → `systemctl enable --now`）+ root 警告/ANSI 颜色实机确认
2. 微软登录实测（注册 Azure 应用 → `PCL_MS_CLIENT_ID`）
3. 服务端向迭代候选：Paper 插件源（Hangar API 免密钥）、world 备份/恢复、多实例内存配额
