@echo off
set JAVA_HOME=D:\Android\Android Studio\jbr
set ANDROID_SDK_ROOT=D:\Android\sdk
cd /d D:\Project\zero\apps\android
call gradlew.bat assembleDebug
echo EXIT_CODE=%ERRORLEVEL%
