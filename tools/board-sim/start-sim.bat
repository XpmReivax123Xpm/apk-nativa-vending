@echo off
setlocal
cd /d "%~dp0"
title Simulador de placa de vending - BoxiPago
echo ============================================================
echo  Simulador de placa de maquina expendedora (BoxiPago)
echo ============================================================
echo.
echo  Escucha la "linea serie" en 127.0.0.1:9100
echo  La APK del emulador la alcanza en 10.0.2.2:9100
echo  Canal de control en 127.0.0.1:9101
echo.
echo  Escribe "help" + Enter para ver los escenarios disponibles.
echo  Ejemplos: scenario stuck | scenario crushed | status | reset
echo ------------------------------------------------------------
echo.
python board_sim.py %*
set EXITCODE=%ERRORLEVEL%
if not "%EXITCODE%"=="0" (
  echo.
  echo El simulador termino con codigo %EXITCODE%.
  echo Verifica que Python este instalado y en el PATH:  python --version
  pause
)
endlocal
