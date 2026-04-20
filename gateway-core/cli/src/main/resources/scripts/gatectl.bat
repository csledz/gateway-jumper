@echo off
rem SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
rem
rem SPDX-License-Identifier: Apache-2.0
rem
rem gatectl Windows launcher.

setlocal enabledelayedexpansion

if "%GATECTL_HOME%"=="" set "GATECTL_HOME=%~dp0"
if "%GATECTL_HOME:~-1%"=="\" set "GATECTL_HOME=%GATECTL_HOME:~0,-1%"

if defined GATECTL_JAVA (
    set "JAVA_BIN=%GATECTL_JAVA%"
) else if defined JAVA_HOME (
    set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_BIN=java"
)

set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "%GATECTL_HOME%\gatectl-*-all.jar" 2^>nul') do (
    if not defined JAR set "JAR=%GATECTL_HOME%\%%f"
)
if not defined JAR if exist "%GATECTL_HOME%\gatectl.jar" set "JAR=%GATECTL_HOME%\gatectl.jar"

if not defined JAR (
    echo gatectl: unable to locate gatectl jar under %GATECTL_HOME% 1>&2
    exit /b 1
)

"%JAVA_BIN%" %GATECTL_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
