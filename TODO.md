# TODO — 交互型 CLI(JLine REPL)

> 决策背景:不做全屏 TUI(Lanterna)、不引入 picocli,保留一次性命令现状,
> 把 `interactive()` 的 Scanner 数字菜单升级为 JLine 3 驱动的交互 REPL。
> 分层不变:**逻辑在 `craftport.minecraft`,本任务全部改动只进 `craftport.cli` 展示层。**

## 红线(每阶段完成都要回归)

- [ ] 一次性命令路径零改动:`dispatch` → `cmdXxx` 行为字节级等价
- [ ] `status` 输出必须包含 "Data directory"(Updater 自检 `verifyBuiltJar` 依赖)
- [ ] `--version` 输出格式不变(自更新提交比对依赖)
- [ ] 无参数 + 非 TTY(管道/脚本/CI)时不挂起:检测哑终端 → 退回旧菜单或打印 help 退出
- [ ] 约定不动:`Log.setConsoleEnabled(false)`、CLI 终端输出纯英文、`System.exit(code)` 语义
- [ ] `parseFlags` 语法保持 `--key value` / `--key`(不做 `--key=value`)

## 技术选型

- `org.jline:jline:3.30.17`(3.x 维护线最新,含 2026-09 安全补丁;不追 4.x)
- shade 兼容:纯 Java、包名 `org.jline.*` 无冲突;JLine 用 ServiceLoader 注册终端提供者,
  pom 已有 `ServicesResourceTransformer`,shade 配置不用改
- 终端提供者:Java 21 下 FFM 不可用(需 22+),自动落 JNI/exec(stty)/dumb;
  Windows conhost(VT)与 Git Bash(mintty)都支持
- 体积预算:+~1MB,单 jar 分发可接受

---

## P0 — REPL 骨架(独立可交付)

- [ ] pom.xml 添加 jline 依赖;`mvn -q package -DskipTests` 确认 shade 正常、jar 可启动
- [ ] 新建 `cli/Repl.java`:Terminal/LineReader 接入、主循环、行解析
      (解析结果复用现有 dispatch 思路:命令 + rest args,不复制 cmdXxx 逻辑)
- [ ] 哑终端探测:TTY 正常 → REPL;dumb/管道 → 保留现有 `interactive()` 数字菜单分支
- [ ] 移除 `CliMain.IN`(Scanner 与 JLine raw 模式抢 System.in 会吞字节),
      `interactive()` 内所有 `IN.nextLine()` 一并迁移
- [ ] REPL 命令 = 现有 10 个菜单项等价物(versions/install/fabric/forge/mods/launch/
      dir/runtimes/server deploy/server start),直接调 cmdXxx
- [ ] `exit`/`quit` 命令,Ctrl+D 视为 exit;`help` 复用 printHelp
- [ ] 补全器:命令名 + server/mods/modpack 子子命令 + 按命令的 `--flag` 静态表
- [ ] 补全器数据(已装版本/已部署服务器)首次 Tab 惰性计算并缓存,**绝不做网络请求**
- [ ] 历史持久化:`Os.dataDir()/repl-history`(JLine History)
- [ ] 长任务沿用现有 `progress()`(`\r[42%]` 在行式 REPL 下可用),Ctrl+C 语义维持现状
- [ ] 冒烟(双环境各一遍:cmd/PowerShell + Git Bash):
      Tab 补全、上下历史、箭头/中文路径显示、`server web` 后 REPL 继续可用、
      Ctrl+C 后回到提示符

## P1 — 选择器与缺参降级

- [ ] `cli/SelectList.java`:箭头键 + 回车确认 + Esc 取消;dumb 模式退化为数字输入
- [ ] `launch` 缺版本参数 → 已装版本列表选择
- [ ] `resolveServerDir` 多服务器:交互上下文 → 箭头选择;一次性命令保持现有报错(红线)
- [ ] `mods search` 结果选择:交互模式弹列表替代 `--pick N`(`--pick` 保留给脚本)
- [ ] y/N 确认(EULA、server delete 等)统一走 LineReader
- [ ] 版本过滤提示:releases/install 流程里支持输入前缀过滤

## P2 — `server console`(交互 CLI 相对面板的核心增量)

- [ ] `server console [dir]`:无托管实例时经 `ServerManager.start` 托管启动(端口预检复用)
- [ ] 日志流:后台线程轮询 `Instance.tail(since)` 打印;输入行 → `Instance.sendCommand`
- [ ] `detach`(Ctrl+D)回 REPL 不杀进程;进程退出显示 exitCode
- [ ] 外部实例(端口占用但非面板/CLI 托管):提示不可 attach,引导 `server start`
- [ ] 冒烟:console 启动 fabric 服务器 → Done → 控制台发命令 → stop → 回 REPL

---

## 验证清单(每阶段收尾跑)

- [ ] `"$MVN" -q package -DskipTests`(注意 mvn 不在 PATH,见 AGENTS.md)
- [ ] 一次性命令回归:`help` / `status` / `versions` / `--version` 输出与改前一致
- [ ] 管道场景:`echo status | java -jar target/CraftPort.jar` 不挂起、退出码正确
- [ ] REPL 手动脚本:P0/P1/P2 各自冒烟项
- [ ] 后端未动,`python test/webpanel_test.py` 预期不受影响(改到 ServerManager 时必跑)

## 明确不做(本期)

- picocli 接管参数解析(parseFlags 已工作,避免扩大爆炸半径)
- Lanterna 全屏 TUI 仪表盘
- `web.html` / WebPanel / 领域层改动
- Windows GBK 之外的控制台编码问题(CLI 纯英文约定已规避)
