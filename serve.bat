@echo off
REM Serve the JShell web app on http://localhost:8000
REM Uses serve.py (stdlib http.server + Range support) — CheerpJ fetches
REM JARs with Range requests, which the plain `python -m http.server` rejects.
cd /d "%~dp0"
py -3 serve.py 8000 || python serve.py 8000
