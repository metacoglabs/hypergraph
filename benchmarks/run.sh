#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")"
scale="${SCALE:-1}"
durability="${DURABILITY:-async}"
threads="${THREADS:-$(getconf _NPROCESSORS_ONLN)}"
heap="${HEAP:-4g}"
history="${HISTORY:-64}"
results="results/scale-$scale-$durability-history-$history"

../mvnw -q -f ../pom.xml -Pbenchmarks -DskipTests install
classpath="target/classes:$(cat target/classpath.txt)"
jvm=(-Xms"$heap" -Xmx"$heap" -XX:+UseParallelGC
	--add-opens java.base/java.lang=ALL-UNNAMED
	--add-opens java.base/java.lang.ref=ALL-UNNAMED
	--add-opens java.base/java.util=ALL-UNNAMED
	-cp "$classpath")

mkdir -p "$results"
for store in hstore hypergraphdb; do
	java "${jvm[@]}" io.hstore.bench.Comparison run --store "$store" --scale "$scale" --durability "$durability" \
		--threads "$threads" --history "$history" --out "$results/$store.json"
done
java "${jvm[@]}" io.hstore.bench.Comparison report "$results/hstore.json" "$results/hypergraphdb.json" | tee "$results/report.md"
