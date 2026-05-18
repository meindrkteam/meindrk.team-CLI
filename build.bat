@echo off
setlocal enabledelayedexpansion

set "CLI_DIR=%~dp0"
set "SRC_DIR=%CLI_DIR%src"
set "LIB_DIR=%CLI_DIR%lib\jackson"
set "BUILD_DIR=%CLI_DIR%build"
set "TMP_DIR=%CLI_DIR%tmp"
set "GRAALVM_DIR=%CLI_DIR%graalvm-win"
set "JAR_FILE=%TMP_DIR%\meindrk-cli.jar"
set "GRAALVM_VERSION=21.0.2"
set "GRAALVM_URL=https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-%GRAALVM_VERSION%/graalvm-community-jdk-%GRAALVM_VERSION%_windows-x64_bin.zip"
set "GRAALVM_ZIP=%CLI_DIR%graalvm-dl\graalvm-win.zip"
set "VS_INSTALL_PATH=d:\Program Files\Microsoft Visual Studio\2022\BuildTools"
set "VCVARSALL="

echo.
echo === meinDRK CLI Build ===
echo.

:: ===========================================================================
:: A) GraalVM finden oder herunterladen
:: ===========================================================================
set "NATIVE_IMAGE="

if defined GRAALVM_HOME (
    if exist "%GRAALVM_HOME%\bin\native-image.cmd" (
        set "NATIVE_IMAGE=%GRAALVM_HOME%\bin\native-image.cmd"
        echo [GraalVM] Via GRAALVM_HOME
        goto :graalvm_found
    )
)

where native-image.cmd >nul 2>&1
if not errorlevel 1 (
    for /f "delims=" %%i in ('where native-image.cmd') do set "NATIVE_IMAGE=%%i"
    echo [GraalVM] Im PATH gefunden
    goto :graalvm_found
)

if exist "%GRAALVM_DIR%\bin\native-image.cmd" (
    set "NATIVE_IMAGE=%GRAALVM_DIR%\bin\native-image.cmd"
    echo [GraalVM] Lokal gecacht
    goto :graalvm_found
)

echo [GraalVM] Lade GraalVM CE %GRAALVM_VERSION% herunter (ca. 450 MB)...
mkdir "%CLI_DIR%graalvm-dl" 2>nul
powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol='Tls12';$p='SilentlyContinue';$ProgressPreference=$p;Invoke-WebRequest '%GRAALVM_URL%' -OutFile '%GRAALVM_ZIP%'"
if errorlevel 1 ( echo FEHLER: Download gescheitert. & exit /b 1 )

echo [GraalVM] Entpacke...
powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue';Expand-Archive '%GRAALVM_ZIP%' '%CLI_DIR%graalvm-dl\extracted' -Force"
for /d %%d in ("%CLI_DIR%graalvm-dl\extracted\*") do (
    move "%%d" "%GRAALVM_DIR%" >nul 2>&1
    goto :moved
)
:moved

set "NATIVE_IMAGE=%GRAALVM_DIR%\bin\native-image.cmd"
if not exist "%NATIVE_IMAGE%" ( echo FEHLER: native-image.cmd nicht gefunden. & exit /b 1 )
echo [GraalVM] OK

:graalvm_found
for %%x in ("%NATIVE_IMAGE%") do set "GRAALVM_BIN=%%~dpx"
set "JAVAC=%GRAALVM_BIN%javac.exe"
set "JAR_CMD=%GRAALVM_BIN%jar.exe"

:: ===========================================================================
:: B) MSVC (vcvarsall.bat) finden oder installieren
:: ===========================================================================
for %%v in ("BuildTools" "Community" "Professional" "Enterprise") do (
    if exist "d:\Program Files\Microsoft Visual Studio\2022\%%~v\VC\Auxiliary\Build\vcvarsall.bat" (
        set "VCVARSALL=d:\Program Files\Microsoft Visual Studio\2022\%%~v\VC\Auxiliary\Build\vcvarsall.bat"
        goto :vcvars_found
    )
)
for %%v in ("BuildTools" "Community" "Professional" "Enterprise") do (
    if exist "C:\Program Files\Microsoft Visual Studio\2022\%%~v\VC\Auxiliary\Build\vcvarsall.bat" (
        set "VCVARSALL=C:\Program Files\Microsoft Visual Studio\2022\%%~v\VC\Auxiliary\Build\vcvarsall.bat"
        goto :vcvars_found
    )
)
if exist "%LOCALAPPDATA%\Programs\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" (
    set "VCVARSALL=%LOCALAPPDATA%\Programs\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat"
    goto :vcvars_found
)

echo.
echo [VS] Visual Studio Build Tools 2022 nicht gefunden.
echo      Benoetigt fuer GraalVM native-image (MSVC-Linker).
echo      Installpfad: %VS_INSTALL_PATH%
echo      Komponenten: VC.Tools.x86.x64 + Windows11SDK.22621
echo.
set /p "VS_CONFIRM=[VS] Jetzt via winget installieren? (Administratorrechte benoetigt) [j/N]: "
if /i not "%VS_CONFIRM%"=="j" (
    echo Abgebrochen. Bitte Visual Studio Build Tools manuell installieren.
    exit /b 1
)
winget install --id Microsoft.VisualStudio.2022.BuildTools --silent --accept-package-agreements --accept-source-agreements --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Component.VC.Tools.x86.x64 --add Microsoft.VisualStudio.Component.Windows11SDK.22621 --installPath ""%VS_INSTALL_PATH%"""
if errorlevel 1 ( echo FEHLER: VS Build Tools Installation fehlgeschlagen. & exit /b 1 )

if exist "%VS_INSTALL_PATH%\VC\Auxiliary\Build\vcvarsall.bat" (
    set "VCVARSALL=%VS_INSTALL_PATH%\VC\Auxiliary\Build\vcvarsall.bat"
    goto :vcvars_found
)
echo FEHLER: vcvarsall.bat nach Installation nicht gefunden.
exit /b 1

:vcvars_found
echo [VS] vcvarsall.bat: %VCVARSALL%
call "%VCVARSALL%" amd64

:: ===========================================================================
:: 1) Quellcode kompilieren
:: ===========================================================================
echo.
echo [1/4] Kompiliere Java-Quellen...
powershell -NoProfile -Command "Remove-Item '%TMP_DIR%' -Recurse -Force -ErrorAction SilentlyContinue"
mkdir "%TMP_DIR%\classes"

"%JAVAC%" --release 21 -cp "%LIB_DIR%\*" -d "%TMP_DIR%\classes" "%SRC_DIR%\de\kreisalarm\cli\Config.java" "%SRC_DIR%\de\kreisalarm\cli\RestClient.java" "%SRC_DIR%\de\kreisalarm\cli\TablePrinter.java" "%SRC_DIR%\de\kreisalarm\cli\CLI.java"
if errorlevel 1 ( echo FEHLER beim Kompilieren. & exit /b 1 )
echo     OK

:: ===========================================================================
:: 2) Fat-JAR
:: ===========================================================================
echo.
echo [2/4] Baue Fat-JAR...
mkdir "%TMP_DIR%\fat"

pushd "%TMP_DIR%\fat"
for %%f in ("%LIB_DIR%\jackson-core-*.jar") do "%JAR_CMD%" xf "%%f"
for %%f in ("%LIB_DIR%\jackson-annotations-*.jar") do "%JAR_CMD%" xf "%%f"
for %%f in ("%LIB_DIR%\jackson-databind-*.jar") do "%JAR_CMD%" xf "%%f"
popd

xcopy /s /e /q "%TMP_DIR%\classes\*" "%TMP_DIR%\fat\" >nul
(echo Main-Class: de.kreisalarm.cli.CLI& echo.) > "%TMP_DIR%\MANIFEST.MF"
"%JAR_CMD%" cfm "%JAR_FILE%" "%TMP_DIR%\MANIFEST.MF" -C "%TMP_DIR%\fat" .
if errorlevel 1 ( echo FEHLER beim JAR-Bau. & exit /b 1 )
echo     OK

:: ===========================================================================
:: 3) Windows x64 Binary
:: ===========================================================================
echo.
echo [3/4] Baue Windows x64 Binary...
mkdir "%BUILD_DIR%" 2>nul

"%NATIVE_IMAGE%" -jar "%JAR_FILE%" --no-fallback --enable-url-protocols=https -H:+UnlockExperimentalVMOptions -H:Name=meindrk-cli-windows-x64 -H:Path="%BUILD_DIR%"
if errorlevel 1 ( echo FEHLER beim Windows-Build. & exit /b 1 )
echo     OK

:: ===========================================================================
:: 4) Linux x64 (via Docker)
:: ===========================================================================
echo.
echo [4/4] Linux x64 Binary...
where docker >nul 2>&1
if errorlevel 1 (
    echo     Docker nicht verfuegbar - Linux-Build uebersprungen.
    echo     Alternativen: build-linux.sh auf Linux oder GitHub Actions pushen.
    goto :done
)
docker run --rm -v "%TMP_DIR%:/work/tmp" -v "%BUILD_DIR%:/work/build" -w /work/build ghcr.io/graalvm/native-image:21 --no-fallback --enable-url-protocols=https -jar /work/tmp/meindrk-cli.jar -H:Name=meindrk-cli-linux-x64 -H:Path=/work/build
if errorlevel 1 ( echo FEHLER beim Linux-Docker-Build. ) else ( echo     OK )

:done
echo.
echo === Build abgeschlossen ===
dir /b "%BUILD_DIR%" 2>nul
echo.
endlocal