@echo off
cd /d "%~dp0..\mobile"
set "LOG=%~dp0..\data\private\feedback-eval\s23-build.log"
if not exist "%~dp0..\data\private\feedback-eval" mkdir "%~dp0..\data\private\feedback-eval"
set "FLUTTER=flutter"
where flutter >NUL 2>NUL || set "FLUTTER=C:\flutter_windows_3.29.3-stable\flutter\bin\flutter.bat"
set "ADB=C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
echo [1/4] Flutter tests (local model screen) > "%LOG%"
echo [1/4] Flutter tests (local model screen)
call %FLUTTER% test test/local_model_screen_test.dart >> "%LOG%" 2>&1
if errorlevel 1 goto failed
echo [2/4] Build debug APK (several minutes)
echo [2/4] Build debug APK >> "%LOG%"
call %FLUTTER% build apk --debug --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1 >> "%LOG%" 2>&1
if errorlevel 1 goto failed
echo [3/4] Install on S23 (keeps app data)
echo [3/4] Install >> "%LOG%"
"%ADB%" devices >> "%LOG%" 2>&1
"%ADB%" install -r build\app\outputs\flutter-apk\app-debug.apk >> "%LOG%" 2>&1
if errorlevel 1 goto failed
"%ADB%" reverse tcp:8080 tcp:8080 >> "%LOG%" 2>&1
echo [4/4] Done. On S23: Settings - Developer menu - Gemma screen - measure feedback.
echo DONE >> "%LOG%"
pause
exit /b 0
:failed
echo FAILED. See data\private\feedback-eval\s23-build.log
echo FAILED >> "%LOG%"
pause
exit /b 1
