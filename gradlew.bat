@rem Orange Gradle launcher (Windows).
@rem
@rem Slim version. Run `gradle wrapper` once on Linux to regenerate the
@rem full wrapper if a future contributor needs deeper Windows tooling.

@if "%DEBUG%"=="" @echo off

setlocal

set DIR=%~dp0
set WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" (
    echo gradle-wrapper.jar missing. Run: gradle wrapper >&2
    exit /b 1
)

set JAVA_BIN=java
if defined JAVA_HOME set JAVA_BIN=%JAVA_HOME%\bin\java

"%JAVA_BIN%" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*

endlocal
