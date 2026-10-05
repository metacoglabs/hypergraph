#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")"
scale="${SCALE:-1}"
durability="${DURABILITY:-async}"
threads="${THREADS:-$(getconf _NPROCESSORS_ONLN)}"
heap="${HEAP:-4g}"
history="${HISTORY:-64}"
runs="${RUNS:-3}"
cache="${CACHE_MB:-0}"
segment="${PAGES_PER_SEGMENT:-0}"
ratio="${COMPACTION_LIVE_RATIO:-0.5}"
results="results/scale-$scale-$durability-history-$history"
if [ "$cache" != 0 ]; then
	results="$results-cache-$cache"
fi
if [ "$segment" != 0 ]; then
	results="$results-segment-$segment"
fi
if [ "$ratio" != 0.5 ]; then
	results="$results-ratio-$ratio"
fi

../mvnw -q -f ../pom.xml -Pbenchmarks -DskipTests install
classpath="target/classes:$(cat target/classpath.txt)"
jvm=(-Xms"$heap" -Xmx"$heap" -XX:+UseParallelGC
	--add-opens java.base/java.lang=ALL-UNNAMED
	--add-opens java.base/java.lang.ref=ALL-UNNAMED
	--add-opens java.base/java.util=ALL-UNNAMED
	-cp "$classpath")

rm -rf "$results" && mkdir -p "$results"
for run in $(seq 1 "$runs"); do
	for store in hstore hypergraphdb; do
		java "${jvm[@]}" io.hstore.bench.Comparison run --store "$store" --scale "$scale" --durability "$durability" \
			--threads "$threads" --history "$history" --cache-mb "$cache" --pages-per-segment "$segment" --compaction-live-ratio "$ratio" --out "$results/$store-$run.json"
	done
done
java "${jvm[@]}" io.hstore.bench.Comparison report "$results"/hstore-*.json "$results"/hypergraphdb-*.json | tee "$results/report.md"
