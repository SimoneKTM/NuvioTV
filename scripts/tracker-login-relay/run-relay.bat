@echo off
rem Avvia il relay locale di login QR.
rem 1) Copia .env.example in .env e compila i campi (PUBLIC_BASE_URL, credenziali provider).
rem 2) Esegui questo file. Il relay resta in ascolto su http://localhost:8000.
cd /d "%~dp0"

set DENO_EXE=deno
where deno >nul 2>nul
if errorlevel 1 set "DENO_EXE=%USERPROFILE%\.deno\bin\deno.exe"

if not exist ".env.example" goto :noenv
if not exist ".env" (
    copy ".env.example" ".env" >nul
    echo Creato .env da .env.example. Imposta le credenziali necessarie e riavvia.
)

"%DENO_EXE%" run --allow-env --allow-net --env-file=.env index.ts
exit /b 0

:noenv
echo File .env.example mancante! Esegui questo script dalla cartella scripts/tracker-login-relay.
exit /b 1