#!/bin/bash
SCRIPT_DIR=$( cd $(dirname "$0") ; pwd )
cd "$SCRIPT_DIR/../Engine" 2>/dev/null || exit 2
echo ROOT=$PWD 
cd lib
curl http://chromedriver.storage.googleapis.com/2.25/chromedriver_mac64.zip >chromedriver_mac64.zip
unzip -o chromedriver_mac64.zip
curl http://chromedriver.storage.googleapis.com/2.25/chromedriver_win32.zip >chromedriver_win32.zip
unzip -o chromedriver_win32.zip
ls -l
