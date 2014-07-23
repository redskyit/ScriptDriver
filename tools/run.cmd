@echo off
rem Set this variable to point at your java instance.
set JAVA="C:\Program Files (x86)\Java\jre7"
set JAVAEXE=%JAVA%\bin\java.exe
%JAVAEXE% -Dwebdriver.chrome.driver=./lib/chromedriver.exe -classpath ./lib/testEngine.jar;./lib/selenium-server-standalone-2.42.2.jar com.redskyit.scriptDriver.RunTests "%1"
