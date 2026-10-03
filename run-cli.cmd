@echo off
rem PCL Java Edition - CLI launcher (Windows)
setlocal
set JAR=
for /f "delims=" %%f in ('dir /b "%~dp0target\*-cli.jar" 2^>nul') do set JAR=%~dp0target\%%f
if "%JAR%"=="" (
    echo Not found: target\*-cli.jar - run "mvn package" first.
    exit /b 1
)
java -jar "%JAR%" %*
