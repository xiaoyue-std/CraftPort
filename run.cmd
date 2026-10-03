@echo off
rem PCL Java Edition 运行脚本（Windows）
rem 用法: run.cmd [jar路径]
setlocal

set JAR=%~dp0target\PCLJ.jar
if not "%~1"=="" set JAR=%~1

if not exist "%JAR%" (
    echo 未找到 %JAR%，请先执行: mvn package
    exit /b 1
)

java -jar "%JAR%" %*
