@REM Maven Wrapper for Windows
@echo off
setlocal

set MAVEN_PROJECTBASEDIR=%~dp0

@REM Check if Maven is installed locally
set MAVEN_HOME=%MAVEN_PROJECTBASEDIR%.mvn\maven
set MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd

if exist "%MAVEN_CMD%" (
    "%MAVEN_CMD%" %*
    goto end
)

@REM Download and extract Maven if not present
echo Maven not found locally. Downloading Apache Maven 3.9.6...
set MAVEN_URL=https://dlcdn.apache.org/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip
set MAVEN_ZIP=%MAVEN_PROJECTBASEDIR%.mvn\maven.zip

if not exist "%MAVEN_PROJECTBASEDIR%.mvn" mkdir "%MAVEN_PROJECTBASEDIR%.mvn"

powershell -Command "& { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%MAVEN_URL%' -OutFile '%MAVEN_ZIP%' }"

if not exist "%MAVEN_ZIP%" (
    echo ERROR: Failed to download Maven.
    exit /b 1
)

echo Extracting Maven...
powershell -Command "& { Expand-Archive -Path '%MAVEN_ZIP%' -DestinationPath '%MAVEN_PROJECTBASEDIR%.mvn' -Force }"

@REM Rename the extracted folder
if exist "%MAVEN_PROJECTBASEDIR%.mvn\apache-maven-3.9.6" (
    rename "%MAVEN_PROJECTBASEDIR%.mvn\apache-maven-3.9.6" maven
)

del "%MAVEN_ZIP%" 2>nul

if exist "%MAVEN_CMD%" (
    echo Maven installed successfully.
    "%MAVEN_CMD%" %*
) else (
    echo ERROR: Maven installation failed.
    exit /b 1
)

:end
endlocal
