@echo off
setlocal

if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    if not exist "%JAVA%" (
        echo JAVA_HOME does not contain bin\java.exe: %JAVA_HOME% 1>&2
        exit /b 1
    )
) else (
    where java.exe >nul 2>&1
    if errorlevel 1 (
        echo Java was not found. Set JAVA_HOME or add java to PATH. 1>&2
        exit /b 1
    )
    set "JAVA=java.exe"
)

"%JAVA%" -jar "%~dp0piplex-init.jar" %*
exit /b %ERRORLEVEL%
