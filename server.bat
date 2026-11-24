@echo off
rem AntiCheat for Paper 26.3 - launcher for server.ps1
rem Written 2026-11-24 by kaneteulin.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0server.ps1"
pause
