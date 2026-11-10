@echo off
REM Recompila ServerEvents y arma el jar en esta misma carpeta.
setlocal
cd /d "%~dp0"

set CP=libs\paper-api.jar;libs\Vault.jar;libs\sqlite-jdbc.jar;libs\adventure-api.jar;libs\adventure-key.jar;libs\bungeecord-chat.jar;libs\annotations.jar;libs\guava.jar;libs\examination-api.jar;libs\QualityArmory.jar;libs\SmartSpawner.jar

echo [1/3] Compilando...
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 -classpath "%CP%" -d out src\com\dogfriends\serverevents\*.java
if errorlevel 1 ( echo ERROR de compilacion & pause & exit /b 1 )

echo [2/3] Armando jarstage (con sqlite shadeado)...
if exist jarstage rmdir /s /q jarstage
mkdir jarstage
cd jarstage
jar xf ..\libs\sqlite-jdbc.jar
if exist META-INF\maven rmdir /s /q META-INF\maven
xcopy /e /i /y ..\out\com com >nul
copy /y ..\resources\plugin.yml . >nul
copy /y ..\resources\config.yml . >nul

echo [3/3] Empaquetando jar...
jar cf ..\ServerEvents-1.4.0.jar .
cd ..
rmdir /s /q jarstage
rmdir /s /q out

echo.
echo LISTO: ServerEvents-1.4.0.jar
pause
