#!/usr/bin/env bash
# Compiles the chatbot. Uses the project-local JDK under .tooling/ when present,
# otherwise falls back to whatever java/javac is on PATH.
set -euo pipefail
cd "$(dirname "$0")"

JDK="$(ls -d .tooling/jdk-*/ 2>/dev/null | head -1 || true)"
if [ -n "$JDK" ]; then
  JAVAC="${JDK}bin/javac"
  JAVA="${JDK}bin/java"
else
  JAVAC="javac"
  JAVA="java"
fi

rm -rf out
mkdir -p out
find src -name '*.java' > out/sources.txt
"$JAVAC" -encoding UTF-8 -d out @out/sources.txt
echo "Build OK -> out/"
echo "Run:  $JAVA -cp out chatbot.Main --train"
echo "      $JAVA -cp out chatbot.Main --port 8080"
