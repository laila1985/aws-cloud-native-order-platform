@echo off
REM Manages the DynamoDB Local container for the Task Management API.
REM
REM Usage:
REM   run-dynamodb.bat            -> start (create if missing, start if stopped)
REM   run-dynamodb.bat start      -> start
REM   run-dynamodb.bat stop       -> stop and remove the container
REM   run-dynamodb.bat status     -> report RUNNING / STOPPED / MISSING
REM   run-dynamodb.bat restart    -> stop then start
setlocal

where docker >nul 2>nul
if errorlevel 1 (
    echo ERROR: Docker is not installed or not on PATH.
    echo Install Docker Desktop from https://www.docker.com/products/docker-desktop/ and try again.
    exit /b 1
)

set "CONTAINER=taskmgmt-dynamodb"
set "ACTION=%~1"

if "%ACTION%"=="" set "ACTION=start"

REM ---------------------------------------------------------------------
REM status: report the container state (running / stopped / missing)
REM ---------------------------------------------------------------------
if "%ACTION%"=="status" goto :status

REM ---------------------------------------------------------------------
REM stop
REM ---------------------------------------------------------------------
if "%ACTION%"=="stop" goto :stop

REM ---------------------------------------------------------------------
REM restart
REM ---------------------------------------------------------------------
if "%ACTION%"=="restart" goto :stop

REM ---------------------------------------------------------------------
REM start (default)
REM ---------------------------------------------------------------------
docker inspect -f "{{.State.Running}}" %CONTAINER% >nul 2>nul
if errorlevel 1 (
    echo Container '%CONTAINER%' does not exist. Creating it with docker compose...
    docker compose up -d
    goto :done
)

for /f "delims=" %%i in ('docker inspect -f "{{.State.Running}}" %CONTAINER% 2^>nul') do set "RUNNING=%%i"
if "%RUNNING%"=="true" (
    echo DynamoDB Local is already running ^(container '%CONTAINER%'^).
    goto :eof
)

echo Container '%CONTAINER%' exists but is stopped. Starting it...
docker start %CONTAINER%

:done
echo DynamoDB Local is running on http://localhost:8000
goto :eof

REM ---------------------------------------------------------------------
:status
docker inspect -f "{{.State.Running}}" %CONTAINER% >nul 2>nul
if errorlevel 1 (
    echo Status: MISSING  ^(container does not exist yet^)
    goto :eof
)
for /f "delims=" %%i in ('docker inspect -f "{{.State.Running}}" %CONTAINER% 2^>nul') do set "RUNNING=%%i"
if "%RUNNING%"=="true" (
    echo Status: RUNNING  ^(container '%CONTAINER%', port 8000^)
) else (
    echo Status: STOPPED  ^(container exists but is not running^)
)
goto :eof

REM ---------------------------------------------------------------------
:stop
docker inspect -f "{{.State.Running}}" %CONTAINER% >nul 2>nul
if errorlevel 1 (
    echo Container '%CONTAINER%' does not exist. Nothing to stop.
    goto :eof
)
echo Stopping and removing '%CONTAINER%'...
docker compose down >nul 2>nul

REM Fallback: remove it directly if it was created via `docker run`.
docker inspect -f "{{.State.Running}}" %CONTAINER% >nul 2>nul
if not errorlevel 1 (
    docker rm -f %CONTAINER% >nul 2>nul
)

echo DynamoDB Local stopped and removed.
if "%ACTION%"=="restart" goto :start_after_stop
goto :eof

REM ---------------------------------------------------------------------
:start_after_stop
echo.
echo Starting '%CONTAINER%' again...
docker compose up -d
echo DynamoDB Local is running on http://localhost:8000
goto :eof

endlocal

