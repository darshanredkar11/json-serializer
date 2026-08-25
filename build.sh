#!/bin/sh
# Compile everything and run the test suite. No dependencies, no build tool.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac -Xlint:all -d out $(find src -name '*.java')
mkdir -p out/META-INF/services
cp -r src/main/resources/META-INF/services/. out/META-INF/services/
java -cp out json.JsonTest
java -cp out json.CodecsTest
java -cp out json.ConcurrencyTest
java -cp out json.processor.JsonRecordProcessorTest
[ "$1" = "bench" ] && java -cp out json.Bench
exit 0
