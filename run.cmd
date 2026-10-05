@echo off
rem CraftPort CLI launcher (Windows)
setlocal
set JAR=%~dp0target\CraftPort.jar
if not exist "%JAR%" (
    echo Not found: %JAR% - run "mvn package" first.
    exit /b 1
)
java -jar "%JAR%" %*
