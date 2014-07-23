#!/bin/bash
ENGINE_DIR=$( cd $(dirname "$0") ; pwd )
cd "$ENGINE_DIR" 2>/dev/null || exit 2
echo ROOT=$PWD 
java -Dwebdriver.chrome.driver=lib/chromedriver \
	-classpath lib/testEngine.jar:lib/selenium-server-standalone-2.42.2.jar \
	com.redskyit.scriptDriver.RunTests "$1"
status=$?
echo "Test status: $status"
exit $status
