#!/bin/zsh
# Standalone invariant test for EconomyModel.
#
# EconomyModel is deliberately free of Minecraft imports, so it compiles and runs on
# its own in about a second — a far faster loop than booting a server to find out that
# conservation stopped closing. It checks births, deaths, the open-economy flows
# (mining, looting, foraging) and the snapshot round trip.
#
#   tools/econ-selftest.sh
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/Users/jessehagy/jdks/jdk-25.0.4+7/Contents/Home}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/io/github/lilkuzcodev/warfront/civilization"
cp "$DIR/src/main/java/io/github/lilkuzcodev/warfront/civilization/EconomyModel.java" \
   "$OUT/io/github/lilkuzcodev/warfront/civilization/"
cp "$DIR/tools/econ-selftest/Harness.java" "$OUT/"
cd "$OUT"
"$JAVA_HOME/bin/javac" -nowarn -d . Harness.java \
   io/github/lilkuzcodev/warfront/civilization/EconomyModel.java
"$JAVA_HOME/bin/java" Harness
