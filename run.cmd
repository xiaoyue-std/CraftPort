@echo off
rem CraftPort CLI launcher (Windows)
setlocal
set JAR=%~dp0target\CraftPort.jar
set NEW=%JAR%.new
set BAK=%JAR%.bak

rem 应用 update 暂存的新版本（运行中的 jar 无法覆盖，所以替换放在启动前）
if exist "%NEW%" (
    if exist "%JAR%" move /y "%JAR%" "%BAK%" >nul
    move /y "%NEW%" "%JAR%" >nul && echo Applied update: CraftPort.jar ^(previous version saved as CraftPort.jar.bak^)
)

if not exist "%JAR%" (
    echo Not found: "%JAR%" - run "mvn package" first.
    exit /b 1
)
java -jar "%JAR%" %*
