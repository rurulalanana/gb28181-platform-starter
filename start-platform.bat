@echo off
rem ============================================================
rem �������� GB28181 ƽ̨��������ע�� + �Զ��ɹܣ�
rem
rem Ϊʲô�� .bat + �ƻ������� agent �ĺ�̨���������� java��
rem ����һ�������̾ͱ����գ�����Ϊ�����ܼ����Ӿ���ʧ����
rem �ƻ��������ڶ�������������ܻỰ����Ӱ�졣
rem ============================================================

cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-21"

rem 【口令一律走环境变量】本脚本要入库，写死等于公开发布。
rem 本机临时用：先在当前窗口执行  set GB_DB_PASSWORD=...  和  set GB_DAHUA_PASSWORD=...
rem 长期用：写进 src/main/resources/application-local.yml（已 gitignore）或系统环境变量。
if not defined GB_DB_PASSWORD (
  echo [ERROR] 未设置 GB_DB_PASSWORD 环境变量（数据库口令）
  pause
  goto :eof
)
if not defined GB_DAHUA_PASSWORD (
  echo [WARN] 未设置 GB_DAHUA_PASSWORD，大华「注册即登录/自动纳管」会失效
)

rem WorkBuddy ע��Ķ˿ڱ������ Spring �� --server.port ��ƫ�������
set "SERVER__PORT="
set "SERVER_PORT="
set "HTTP_PROXY="
set "HTTPS_PROXY="

if not exist "target\gb28181-platform-starter-1.0.2.jar" goto nojar

echo [%date% %time%] starting platform >> ..\gb28181-local.log
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar target\gb28181-platform-starter-1.0.2.jar --server.port=48088 --spring.datasource.password=%GB_DB_PASSWORD% --dahua.server-mode.enabled=true --gb28181.api-docs.protect=false --gb28181.rtc-extern-ip=auto >> ..\gb28181-local.log 2>&1
echo [%date% %time%] platform exited with code %errorlevel% >> ..\gb28181-local.log
echo.
echo �������˳�����������رմ��ڡ�
pause >nul
goto :eof

:nojar
echo [ERROR] û�ҵ� target\gb28181-platform-starter-1.0.2.jar
echo ����ִ��: mvn -o package -DskipTests -Dgpg.skip=true
pause
goto :eof
