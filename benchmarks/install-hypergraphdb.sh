#!/usr/bin/env bash
# SPDX-FileCopyrightText: Metacog Labs
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
set -Eeuo pipefail

commit=99485a1fa52e532351e8418b4f8153d97c72c959
version=1.4-99485a1
je_version=5.0.73
root="$(cd "$(dirname "$0")/.." && pwd)"
work="${HGDB_WORK:-$root/benchmarks/target/hypergraphdb}"
mvn="$root/mvnw"

if [ ! -d "$work/source/.git" ]; then
	git clone --quiet https://github.com/hypergraphdb/hypergraphdb.git "$work/source"
fi
git -C "$work/source" checkout --quiet "$commit"

"$mvn" -q dependency:get -Dartifact="com.sleepycat:je:$je_version"
je="$HOME/.m2/repository/com/sleepycat/je/$je_version/je-$je_version.jar"

rm -rf "$work/classes" && mkdir -p "$work/classes/core" "$work/classes/bje"
javac --release 11 -nowarn -encoding UTF-8 -d "$work/classes/core" $(find "$work/source/core/src/java" -name '*.java') 2> "$work/core.log" || { cat "$work/core.log"; exit 1; }
cp -R "$work/source/core/src/config/." "$work/classes/core/"
javac --release 11 -nowarn -encoding UTF-8 -cp "$work/classes/core:$je" -d "$work/classes/bje" $(find "$work/source/storage/bdb-je/src/java" -name '*.java') 2> "$work/bje.log" || { cat "$work/bje.log"; exit 1; }

jar --create --file "$work/hgdb-$version.jar" -C "$work/classes/core" .
jar --create --file "$work/hgbdbje-$version.jar" -C "$work/classes/bje" .
"$mvn" -q install:install-file -Dfile="$work/hgdb-$version.jar" -DgroupId=org.hypergraphdb -DartifactId=hgdb -Dversion="$version" -Dpackaging=jar
"$mvn" -q install:install-file -Dfile="$work/hgbdbje-$version.jar" -DgroupId=org.hypergraphdb -DartifactId=hgbdbje -Dversion="$version" -Dpackaging=jar
echo "installed HyperGraphDB $version (commit $commit) into the local Maven repository"
