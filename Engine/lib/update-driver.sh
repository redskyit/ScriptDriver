#!/bin/bash

SELENIUM_VERSION=3.141
SELENIUM_REVISION=0
CHROMEDRIVER_VERSION=2.43

echo ROOT=$PWD 
cd ../lib || exit 1
rm selenium-server-standalone*.jar
curl -L https://selenium-release.storage.googleapis.com/$SELENIUM_VERSION/selenium-server-standalone-${SELENIUM_VERSION}.${SELENIUM_REVISION}.jar > selenium-server-standalone-${SELENIUM_VERSION}.${SELENIUM_REVISION}.jar
curl -L http://chromedriver.storage.googleapis.com/$CHROMEDRIVER_VERSION/chromedriver_mac64.zip >chromedriver_mac64.zip
unzip -o chromedriver_mac64.zip
curl -L http://chromedriver.storage.googleapis.com/$CHROMEDRIVER_VERSION/chromedriver_win32.zip >chromedriver_win32.zip
unzip -o chromedriver_win32.zip
curl -L http://chromedriver.storage.googleapis.com/$CHROMEDRIVER_VERSION/notes.txt >chromedriver_release_notes-$CHROMEDRIVER_VERSION.txt
ls -l

rm commons-io*.jar
curl -L http://mirrors.ukfast.co.uk/sites/ftp.apache.org//commons/io/binaries/commons-io-2.6-bin.zip >commons-io-2.6-bin.zip
unzip -o -j commons-io-2.6-bin.zip commons-io-2.6/commons-io-2.6.jar 

rm *.zip
