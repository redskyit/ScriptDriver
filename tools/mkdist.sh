#!/bin/bash
test ! -d dist -o ! -d tools && { echo "Please run tools/mkdist.sh from root folder" ; exit 1; }

# make sure its built
cd Engine/TestEngine
ant || exit $?
cd ../..

# Build distribution package
cd dist
VERSION=0.1
DIST=ScriptDriver-$VERSION
rm -rf $DIST $DIST.tgz
mkdir $DIST $DIST/lib $DIST/examples

cp ../tools/run.sh $DIST
cp ../examples/* $DIST/examples
cp ../Engine/lib/sel*.jar $DIST/lib
cp ../Engine/lib/chromedriver* $DIST/lib
cp ../Engine/TestEngine/testEngine.jar $DIST/lib

tar cvfz $DIST.tgz $DIST
