# CraftPort

[Plain Craft Launcher 2](https://github.com/Hex-Dragon/PCL2)（VB.NET / WPF）的 Java 重构版。
一套核心、两个面孔：**PCL2 风格的图形界面** + **面向 Linux 服务器的纯英文命令行**，
下载走 BMCLAPI / MCIMirror 镜像，国内网络开箱即用。

- **启动客户端**：版本安装、Fabric / Forge / OptiFine、整合包、模组/资源包/光影/数据包
- **部署服务器**：vanilla / Fabric / Forge / NeoForge / Paper 一键部署，systemd 常驻，配套客户端包分发
- **跨平台**：Windows / Linux / macOS，同一个 jar；CLI 产物无 JavaFX，无头服务器可直接跑

---

## 构建与运行

环境要求：**JDK 21+**、Maven 3.8+（运行时若用发行版 JRE，同样需要 21）。

```bash
mvn package
```

产物（`target/`）：

| 产物 | 用途 |
|---|---|
| `CraftPort.jar`（约 11MB） | 图形界面；内含全平台 JavaFX |
| `CraftPort-cli.jar`（约 0.7MB） | 纯英文命令行，无 JavaFX，无头环境可跑 |

```bash
java -jar target/CraftPort.jar          # GUI（Windows / Linux / macOS）
java -jar target/pcl-java-1.0.0-cli.jar # CLI（或 ./run-cli.sh、run-cli.cmd、java -jar target/CraftPort.jar cli ...）
```

---

## 图形界面

左侧雪山导航三页：**启动游戏**（版本选择 / 离线登录 / 启动进度 / 实时日志）、
**下载游戏**（正式版 / Fabric / Forge / CurseForge 磁贴入口）、**启动器设置**
（游戏目录 / 下载源 / 内存 / Java 管理 / GC / 版本隔离）。登录为离线模式，
微软登录需自备 Azure 应用 ID（环境变量 `PCL_MS_CLIENT_ID`）。

---

## 命令行

不带参数运行进入交互菜单；以下所有命令均可直接调用。

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

# 启动（离线登录）
login Steve
launch 1.20.6 --username Steve --server play.example.com --no-wait
```

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
sudo systemctl daemon-reload && sudo systemctl enable --now pclj-1.20.6-paper
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
```

---

### Web 管理面板

```bash
server web            # 默认 http://127.0.0.1:8765/ （Ctrl+C 结束面板）
```

单页管理面板：部署新服务器（**版本级联菜单**——先选系列再选版本,全部正式版
自动拉取,1.10 以前归入「远古版本」；类型 / EULA,带进度条）、服务器列表与状态
（运行中 / 已退出 / 端口被外部占用）、从浏览器启动与优雅停止（向服务端控制台
发送 `stop`）、实时日志（可收起的小窗）、控制台命令输入。仅监听 127.0.0.1；
面板只接管由它启动的实例，CLI 手动启动的服务器会标记为 "running elsewhere"。
**中英文切换**：右上角按钮一键切换，选择保存在浏览器 localStorage。
**Mod 与内容下载**：CurseForge / Modrinth 双源搜索，**支持中文搜 mod**（自动经
mc百科 桥接英文名，如「钠」→ Sodium、「物品管理」→ JEI）；搜索栏下自动推荐
当前类型热门榜单（按下载量，切换类型/来源即刷新）；结果带图标与 mc百科
（mcmod.cn）直达链接（后台解析词条页,失败自动退回站内搜索）；按类型
（mod / 资源包 / 光影 / 数据包）装到服务器对应目录；**自动依赖补全**——安装后
扫描 jar 内声明（`fabric.mod.json` 的 depends / `mods.toml` 的依赖块）递归装入
required 依赖（如装 Sodium 自动带上 Fabric API），重复文件跳过。安装前可
**选择版本**：点 mod 行的下载图标弹出该 mod 全部兼容版本（含日期/大小/适配
版本列表，默认最新），选中哪个装哪个，依赖补全照常工作。
**明暗主题**：右上角 ☀️/🌙 一键切换，选择保存在浏览器 localStorage。
**服务器详情与在线人数**：服务器卡片右上角 ⓘ 打开详情（游戏/类型、端口、状态、
PID、MOTD、服务端版本），在线人数通过 Server List Ping 直查端口——即使服务器由
CLI/systemd 启动也能查到；详情数据每 3 秒自动刷新。
**设置**：右上角 ⚙ 打开——服务端内存（MB）、额外 JVM 参数（追加到启动命令，
argfile 内同名项可覆盖）、下载限速（KB/s，全局令牌桶，单文件与分片下载都生效）、
服务端部署根目录（默认 {游戏目录}/servers）。
**主机资源**：页头常驻显示 CPU / 内存 / 磁盘占用，每 3 秒刷新。
**server.properties 编辑**：选中服务器后点击「属性配置」，键值行内编辑
（布尔项为下拉选择，支持键名筛选），服务器运行中拒绝保存，避免被停服回写覆盖。
部署表单还支持 **CurseForge 整合包一键部署**：输入整合包名，自动按 manifest
推断版本与加载器部署服务器并装入全部内容。

---

## 典型场景：Linux 服务器 + 整合包 + 玩家客户端包

```bash
# Ubuntu 无头服务器（JRE 21）
./run-cli.sh server deploy 1.20.6 --type fabric --accept-eula
./run-cli.sh modpack install "Fabulously Optimized" --mc 1.20.6 --pick 0 \
    --server --dir ~/.minecraft/servers/1.20.6-fabric
./run-cli.sh server service install --dir ~/.minecraft/servers/1.20.6-fabric
sudo systemctl daemon-reload && sudo systemctl enable --now pclj-1.20.6-fabric

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

下载源策略与 PCL2 一致：BMCLAPI 镜像优先、失败自动回退官方
（`status` 可查看，`config.json` 中 `ToolDownloadSource` 可改）。

---

## 项目结构

```
pcl/
├── Launcher.java            入口分发（GUI / cli）
├── cli/CliMain.java         纯英文命令行（子命令 + 交互菜单）
├── base/                    日志 / 平台适配(Os) / 配置(Config) / 任务体系(Task) / JSON
├── net/                     重试策略 + BMCLAPI 镜像换算(Net) / 多线程下载(Downloader)
├── minecraft/               McFolder·McVersion·Library（版本解析与隔离）
│                            JavaManager·JavaRuntimeDownloader（Java 21 优先 + 运行时下载）
│                            LoginService（离线 UUID / 微软六步）
│                            InstallService·ModLoader（本体与加载器安装）
│                            CurseForge·Modrinth（双模组源）
│                            ModsService（面板 Mod 下载 + jar 声明依赖补全）
│                            ModpackInstaller（整合包客户端/服务端双模式 + 客户端包）
│                            ServerDeployer（服务端一键部署 + systemd）
│                            ArgsBuilder·LaunchPipeline·GameProcess（参数与启动）
└── ui/                      JavaFX 界面（雪山侧栏 / 动画 / 磁贴下载页）
```

## 与 PCL2 的关系

命令行输出、配置键名、启动参数规则、镜像策略尽量与 PCL2 保持一致，便于对照迁移；
原始项目与 PCL 名称版权归 [Hex-Dragon / PCL2](https://github.com/Hex-Dragon/PCL2) 所有，
本项目为其 Java 重构练习，遵循原项目 LICENCE（免费使用、禁止商用）。
