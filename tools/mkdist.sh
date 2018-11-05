#!/bin/bash
test ! -d dist -o ! -d tools && { echo "Please run tools/mkdist.sh from root folder" ; exit 1; }

VERSION=`cat Engine/TestEngine/VERSION`
case "$VERSION" in
0.?|0.?.?) ;;
*) echo "Can't determine version, aborting" 1>&2 ; exit 1 ;;
esac
DIST=ScriptDriver-$VERSION

build() {
	# make sure its built
	cd Engine/TestEngine
	ant || return $?
	cd ../..
	return 0
}

dist() {
	# Build distribution package
	cd dist
	rm -rf $DIST $DIST.tgz
	mkdir $DIST $DIST/lib $DIST/examples

	cp ../tools/run.sh $DIST
	cp ../examples/* $DIST/examples
	cp ../Engine/lib/sel*.jar $DIST/lib
	cp ../Engine/lib/common-io*.jar $DIST/lib
	cp ../Engine/lib/chromedriver ../Engine/lib/chromedriver.exe $DIST/lib
	cp ../Engine/TestEngine/testEngine.jar $DIST/lib

	tar cvfz $DIST.tgz $DIST
	cd ..
}

pub() {
	cp -v dist/$DIST.tgz ~/Dropbox*Personal*/GitHub/ScriptDriver
}

tasks="$@"
case "$tasks" in 
all) tasks="build dist pub" ;;
"") tasks="build dist" ;; 
esac

# Run tasks
for arg in $tasks ; do
	case "$arg" in
	build) build || exit $? ;;
	dist) dist || exit $? ;;
	pub) pub || exit $? ;;
	esac
done
