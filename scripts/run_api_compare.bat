@echo off
chcp 65001 >NUL
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_api_compare.ps1"
echo.
echo 완료. 이 창을 닫아도 됩니다.
pause
