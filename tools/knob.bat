@echo off
rem knob.bat - TacLight tuning knob launcher (double-click friendly).
rem   Double-click          : opens a dedicated interactive tuning window (knob: prompt).
rem   With arguments        : sends one command, e.g.  tools\knob.bat knee 8
rem   Bare knob names OK    : "knee 8" and "!knee 8" both work (auto bang-prepend).
rem   Keep this file ASCII-only: cmd reads .bat in the system codepage, not UTF-8.
cd /d "%~dp0.."
if "%~1"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\knob.ps1
  echo.
  pause
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\knob.ps1 -Text "%*"
)
