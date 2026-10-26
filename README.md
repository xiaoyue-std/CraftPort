# CraftPort

Minecraft 客户端与服务端一体化命令行工具。**纯 CLI、单 jar（约 0.5MB）、除 Gson 外零运行时依赖**。
下载走 BMCLAPI / MCIMirror 镜像，国内网络环境开箱即用。

- **客户端**：版本安装、Fabric / Forge / OptiFine、整合包、模组 / 资源包 / 光影 / 数据包（CurseForge + Modrinth 双源）、离线 / 微软登录、一键启动
- **服务端（主方向）**：vanilla / Fabric / Forge / NeoForge / Paper 一键部署、整合包服务端化、配套客户端包分发、systemd 常驻、Web 管理面板、server.properties 编辑、SLP 在线人数查询、下载限速
- **跨平台**：Windows / Linux / macOS，无头环境可运行

---

## 构建与运行

环境要求：**JDK 21+**、Maven 3.8+（运行时同样需要 JRE 21）。

```bash
mvn package                # 产物: target/CraftPort.jar
./run.sh                   # Linux / macOS（自动优先 Java 21）
run.cmd                    # Windows
# 或直接: java -jar target/CraftPort.jar <command>
```

不带参数运行进入交互式菜单。CLI 输出为英文；核心模块日志为中文，写入数据目录。

### 更新

```bash
update --check        # 只检查 GitHub 主分支是否有新提交
update                # 拉取源码 → 本地编译 → 安装新 jar
update --no-apply     # 只编译并暂存 CraftPort.jar.new，不自动替换
```

`update` 不做二进制分发：从 GitHub 下载源码，用**本机 JDK 的 javac/jar** 编译出新的
`CraftPort.jar`（不需要 Maven），自检通过后安装。**不需要运行脚本**，直接
`java -jar CraftPort.jar update` 即可：

- Linux / macOS：当场替换文件，下次启动就是新版本。
- Windows：运行中的 jar 被锁定，`update` 会调度一个一次性小脚本，**进程退出后自动完成替换**
  （下一次启动即新版本）。
- 两种情况下旧 jar 都保留为 `CraftPort.jar.bak`，回滚时改回原名即可。

其他行为：

- **用户数据不在更新范围内**：`config.json`、日志、游戏目录、服务端目录、`servers/` 全部保留。
- 源码解压在数据目录的 `update/`，应用成功后自动清理；`.bak` 保留供回滚。
- 来源固定为本仓库 `main` 分支；`--ref <tag|branch>` 可指定，`--install-dir <dir>` 可指定 jar 位置。
- 启动时后台检查一次更新：成功后缓存 6 小时；**检查超时或失败则 30 分钟内不再尝试**，
  且单次启动最多等待 2 秒，不会拖慢启动。`update --check` 不受缓存影响，总是实时查询。
- 从源码 `mvn package` 构建的 jar 无提交信息，启动时不提示更新；`update` 构建的 jar 才会做版本比对。

---

## 命令行

### 客户端

```bash
# 安装游戏本体与加载器
install 1.20.6
fabric 1.20.6 latest
forge 1.20.6 50.2.10

# 模组 / 资源包 / 光影 / 数据包（双源：CurseForge 默认，--source modrinth 切换）
mods install sodium --mc 1.20.6 --loader fabric
mods install complementary --type shader --mc 1.20.6
mods install "faithful 32x" --type resourcepack --source modrinth --mc 1.20.6
mods install terralith --type datapack --source modrinth --mc 1.20.6
mods install <query> --dir <目标目录>          # 任意位置（如服务器 mods/）

# 整合包（CurseForge）：自动装基础版本 + 加载器 + 全部成员 mod + overrides
modpack install "Fabulously Optimized" --mc 1.20.6 --pick 0
modpack search <query> --mc 1.20.6            # 先搜索再选 --pick N

# 整合包一键服务端部署：自动推断版本与加载器 → 部署服务器 → 装入全部内容
# → 生成配套客户端包（--start 可选立即启动）
modpack deploy "Fabulously Optimized" --mc 1.20.6 --accept-eula

# 启动：默认使用缓存的微软会话，没有则离线登录
login Steve                  # 离线登录（写入 UUID 缓存）
login --microsoft            # 微软登录（设备码流）
launch 1.20.6 --server play.example.com --no-wait
launch 1.20.6 --offline      # 强制离线登录
```

微软登录需要自备 Azure 应用 ID：设置环境变量 `CRAFTPORT_MS_CLIENT_ID`
（或 `config.json` 的 `CacheClientId`），`login --microsoft` 会打印验证链接与
一次性代码，完成后刷新令牌保存在 `config.json`，`launch` 自动刷新访问令牌。

`mods/modpack` 的 `--pick N` / `--file N` 用于选择列表中的第 N 项（0 起）；
`--type mod|resourcepack|shader|datapack` 决定内容落位到
`mods/ resourcepacks/ shaderpacks/ datapacks/`。

### 服务端部署（主方向）

```bash
# 1. 一键部署：服务端 jar + eula.txt + server.properties + start.sh/bat + systemd 单元
server deploy 1.20.6 --type paper --accept-eula

# 2. 给服务器装整合包
modpack install "Fabulously Optimized" --mc 1.20.6 --pick 0 \
    --server --dir "$HOME/.minecraft/servers/1.20.6-paper"

# 3. 启动（继承控制台，可直接输入 stop / op 等命令；启动前自动做端口占用预检）
server start --dir ~/.minecraft/servers/1.20.6-paper --memory 4096

# 或注册为 systemd 服务常驻
server service install --dir ~/.minecraft/servers/1.20.6-paper
sudo systemctl daemon-reload && sudo systemctl enable --now craftport-1.20.6-paper.service
```

支持类型：`vanilla`（Mojang 官方）/ `fabric`（官方 meta）/ `forge`（官方安装器
`--installServer` + BMCLAPI 加速）/ `neoforge`（NeoForge maven 安装器
`--installServer` + BMCLAPI 加速，1.20.2+）/ `paper`（PaperMC Fill API，SHA-256 校验）。
所需 Java 不满足时自动下载 Mojang 运行时（Linux 自动补执行权限）；
EULA 必须显式 `--accept-eula` 或交互确认，不会静默代受。

### 其他命令

```bash
versions          # 已安装版本        releases [n]   # 可安装的正式版
dir [path]        # 查看/设置游戏目录  runtimes       # 已检测的 Java
status            # 当前配置          server list    # 已部署的服务端
update [--check]  # 检查并安装更新
```

---

## Web 管理面板

```bash
server web            # 默认 http://127.0.0.1:8765/ （Ctrl+C 结束面板）
```

单页管理面板，仅监听 127.0.0.1：

- **部署**：版本级联菜单（先选系列再选版本，全部正式版自动拉取，1.10 以前归入「远古版本」）、
  服务端类型与 EULA、进度条；也支持输入 CurseForge 整合包名一键部署。
- **服务器**：列表与状态（运行中 / 已退出 / 端口被外部占用）、启动、优雅停止（向控制台发 `stop`）、
  强制终止、删除（运行中拒绝、越界拒绝、二次确认）。
- **控制台**：实时日志（可收起小窗）、命令输入。
- **Mod 与内容**：CurseForge / Modrinth 双源搜索，**支持中文搜 mod**（经 mc百科 桥接英文名，
  如「钠」→ Sodium）；空关键词显示按下载量排序的热门榜；结果带图标与 mc百科直达链接；
  可选具体版本安装；按类型（mod / 资源包 / 光影 / 数据包）装到服务器对应目录；
  **自动依赖补全**——扫描 jar 内声明（`fabric.mod.json` depends / `mods.toml` 依赖块）递归装入
  required 依赖，重复文件跳过。
- **服务器详情**：端口、PID、MOTD、服务端版本、EULA 状态；在线人数通过 Server List Ping 直查端口，
  即使服务器由 CLI / systemd 启动也能查到；每 3 秒刷新。
- **属性编辑**：`server.properties` 键值行内编辑（布尔项下拉、键名筛选），运行中拒绝保存。
- **设置**：服务端内存、额外 JVM 参数、下载限速（KB/s，全局令牌桶）、服务端部署根目录。
- **主机资源**：页头常驻 CPU / 内存 / 磁盘占用，每 3 秒刷新。
- **界面**：中英文一键切换、明暗主题切换，选择保存在浏览器 localStorage。

面板只接管由它启动的实例；CLI / systemd 启动的服务器会标记为 "running elsewhere"。

---

## 典型场景：Linux 服务器 + 整合包 + 玩家客户端包

```bash
# Ubuntu 无头服务器（JRE 21）
./run.sh server deploy 1.20.6 --type fabric --accept-eula
./run.sh modpack install "Fabulously Optimized" --mc 1.20.6 --pick 0 \
    --server --dir ~/.minecraft/servers/1.20.6-fabric
./run.sh server service install --dir ~/.minecraft/servers/1.20.6-fabric
sudo systemctl daemon-reload && sudo systemctl enable --now craftport-1.20.6-fabric.service

# 把生成的 clientpacks/fabulously-optimized-client.zip 发给玩家，
# 解压到其客户端实例目录（mods + config 即配即用）
```

启动脚本 `start.sh` 中写的是部署时解析好的 Java 绝对路径（规避服务器 PATH 上
Java 版本不对的常见坑）；以 root 运行会有专用账户提示；缺 Java 时给出
apt / dnf / pacman 安装命令；CLI 输出带 ANSI 颜色（遵循 `NO_COLOR`）。

---

## 配置与数据

| 内容 | Windows | Linux / macOS |
|---|---|---|
| 配置 `config.json`、日志 | `%APPDATA%\CraftPort` | `~/.local/share/CraftPort`（XDG） |
| 游戏目录（默认） | `%APPDATA%\.minecraft` | `~/.minecraft` |
| 服务端 / 客户端包 | `<游戏目录>\servers\` `clientpacks\` | `<游戏目录>/servers/` `clientpacks/` |
| 自动下载的 Java 运行时 | `<游戏目录>\runtime\` | `<游戏目录>/runtime/` |

下载源策略：BMCLAPI 镜像优先、失败自动回退官方（`status` 可查看，
`config.json` 中 `ToolDownloadSource` 可改：0 镜像优先 / 1 自动 / 2 官方优先）。

---

## 项目结构

```
src/main/java/craftport/
├── cli/        CliMain（子命令 + 交互菜单）、WebPanel（面板 HTTP API 与路由）
├── base/       日志 / 平台适配(Os) / 配置(Config) / 任务体系(Task) / JSON
├── net/        重试策略 + BMCLAPI 镜像换算(Net) / 多线程分片下载与限速(Downloader)
└── minecraft/  McFolder·McVersion·Library（版本解析与隔离）
                JavaManager·JavaRuntimeDownloader（Java 21 优先 + 运行时下载）
                LoginService（离线 UUID / 微软 Device Code）
                InstallService·FileCompleter（本体安装与文件补全）
                ModLoader（Fabric / Forge / OptiFine）
                CurseForge·Modrinth·ModsService（双源内容 + 依赖补全）
                ModpackInstaller（整合包客户端/服务端双模式 + 客户端包）
                ServerDeployer·ServerManager·ServerProperties·ServerPing（服务端）
                ArgsBuilder·LaunchPipeline·GameProcess（参数与启动）
src/main/resources/craftport/web.html   面板单页（内嵌 JS）
```

---

## 许可

本项目仅供学习交流使用；免费分发，禁止商用。
