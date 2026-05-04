@rem Gradle wrapper for Windows - calls cached Gradle 9.4.1
@if "%DEBUG%"=="" @echo off

set GRADLE_BIN=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat

if not exist "%GRADLE_BIN%" (
    echo ERROR: Gradle 9.4.1 not found at expected location.
    exit /b 1
)

"%GRADLE_BIN%" %*
