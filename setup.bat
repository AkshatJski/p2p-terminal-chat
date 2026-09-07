@REM ----------------------------------------------------------------------------
@REM p2p-terminal-chat one-command setup (Windows)
@REM
@REM Usage:
@REM   setup.bat          build + run
@REM   setup.bat --build  build only
@REM
@REM Prerequisites: JDK 26 must be installed and on PATH.
@REM ----------------------------------------------------------------------------

@ECHO OFF
SETLOCAL EnableDelayedExpansion

ECHO.
ECHO  == p2p-terminal-chat setup ==
ECHO.

SET "BUILD_ONLY=0"
IF "%~1"=="--build" SET "BUILD_ONLY=1"

SET "SCRIPT_DIR=%~dp0"
SET "JAR_NAME=java-p2p-terminal-chat-1.2.0.jar"

REM ---- Check Java ----
WHERE java >NUL 2>NUL
IF %ERRORLEVEL% NEQ 0 (
    ECHO [X] Java is not installed or not on PATH.
    ECHO.
    ECHO   Install JDK 26 from:
    ECHO     winget install EclipseAdoptium.Temurin.26.JDK
    ECHO     Or visit: https://adoptium.net
    EXIT /B 1
)

FOR /F "tokens=3" %%A IN ('java -version 2^>^&1 ^| findstr /i "version"') DO (
    SET "JAVA_VER=%%~A"
)
SET "JAVA_VER=%JAVA_VER:"=%"
FOR /F "delims=." %%A IN ("%JAVA_VER%") DO SET "JAVA_MAJOR=%%A"

IF %JAVA_MAJOR% LSS 26 (
    ECHO [X] Java %JAVA_MAJOR% detected, but JDK 26 is required.
    EXIT /B 1
)
ECHO [OK] Java %JAVA_MAJOR% detected

REM ---- Decide build tool ----
CD /D "%SCRIPT_DIR%"

IF EXIST "mvnw.cmd" (
    SET "MVN_CMD=mvnw.cmd"
    ECHO [==] Using Maven Wrapper (mvnw.cmd^)
) ELSE (
    WHERE mvn >NUL 2>NUL
    IF %ERRORLEVEL% NEQ 0 (
        ECHO [X] Maven is not installed and mvnw.cmd was not found.
        ECHO   Install Maven: https://maven.apache.org/download.cgi
        EXIT /B 1
    )
    SET "MVN_CMD=mvn"
    ECHO [==] Using system Maven
)

REM ---- Build ----
ECHO [==] Building project...
CALL "%MVN_CMD%" -q package -DskipTests
IF %ERRORLEVEL% NEQ 0 (
    ECHO [X] Build failed.
    EXIT /B 1
)
ECHO [OK] Build complete - target\%JAR_NAME%

REM ---- Run ----
IF "%BUILD_ONLY%"=="1" (
    ECHO.
    ECHO Build-only mode. To run:
    ECHO   java -jar target\%JAR_NAME%
    EXIT /B 0
)

ECHO [==] Starting p2p-terminal-chat...
ECHO.
java -jar "target\%JAR_NAME%"
