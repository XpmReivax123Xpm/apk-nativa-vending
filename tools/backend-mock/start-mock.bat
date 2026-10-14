@echo off
setlocal
cd /d "%~dp0"
title Backend simulado de vending - BoxiPago
echo ============================================================
echo  Backend simulado (datos de prueba, sin pasarelas reales)
echo ============================================================
echo.
echo  Escucha en http://127.0.0.1:9200
echo  La APK del emulador lo alcanza en http://10.0.2.2:9200
echo.
echo  Maquina: MAQ-SIM-01   Equipo: 02:SIM:00:00:00:01   PIN: 1234
echo  Control:  curl "http://127.0.0.1:9200/__control?payment=fail"
echo  Estado:   curl "http://127.0.0.1:9200/__status"
echo ------------------------------------------------------------
echo.
python mock_backend.py %*
set EXITCODE=%ERRORLEVEL%
if not "%EXITCODE%"=="0" (
  echo.
  echo Termino con codigo %EXITCODE%. Verifica Python en el PATH: python --version
  pause
)
endlocal
