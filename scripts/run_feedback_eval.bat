@echo off
set PYTHONIOENCODING=utf-8
cd /d "%~dp0.."
set "PY=C:\Users\DELL\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
if not exist "%PY%" goto nopython
if not exist "data\private\feedback-eval" mkdir "data\private\feedback-eval"
echo [1/4] Create scenarios
"%PY%" scripts\eval_feedback_llm.py scenarios
echo [2/4] Gemma on PC GPU (falls back to CPU if GPU fails)
"%PY%" scripts\eval_feedback_llm.py gemma gpu
echo [3/4] OpenAI
"%PY%" scripts\eval_feedback_llm.py api
echo [4/4] Score
"%PY%" scripts\eval_feedback_llm.py score
echo.
echo Done. Results: data\private\feedback-eval
pause
exit /b 0
:nopython
echo Python not found: %PY%
pause
exit /b 1
