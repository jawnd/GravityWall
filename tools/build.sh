#!/usr/bin/env bash
export JAVA_HOME='/c/Users/98687/Documents/Codex/2026-10-03/wei/work/jdk17/jdk-17.0.20.1+1'
cd /c/Users/98687/Documents/GravityWall || exit 1
"/c/Users/98687/tools/gradle/gradle-9.6.1/bin/gradle.bat" "${@:-assembleDebug}" 2>&1 | tail -30
