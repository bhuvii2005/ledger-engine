#!/usr/bin/env bash
# Runs one JMeter scenario against the docker compose stack, then checks the ledger.
#
#   loadtest/run.sh <name> <plan.jmx> [-Jprop=value ...]
#
# Environment: JMETER (path to jmeter or jmeter.bat), APP (default http://127.0.0.1:8080),
# WARMUP (seconds left out of the latency/throughput numbers, default 30).
# Output goes to loadtest/report/<name>/ (gitignored): results.jtl, summary.json,
# reconciliation.json and an HTML dashboard over the same measured window.
set -euo pipefail
cd "$(dirname "$0")/.."

NAME=$1
PLAN=$2
shift 2
JMETER=${JMETER:-jmeter}
APP=${APP:-http://127.0.0.1:8080}
WARMUP=${WARMUP:-30}
OUT=loadtest/report/$NAME
rm -rf "$OUT"
mkdir -p "$OUT"

sql() { docker compose exec -T postgres psql -U "${DB_USER:-ledger}" -d "${DB_NAME:-ledger}" -tAc "$1" | tr -d '\r'; }
counter() {
    curl -s "$APP/actuator/metrics/$1" | python -I -c \
        "import json,sys; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']))" 2>/dev/null || echo 0
}

# server-side count and total seconds for the transfer endpoint, as Spring measures them inside the app
server_time() {
    curl -s "$APP/actuator/metrics/http.server.requests?tag=uri:/api/v1/transactions/transfer" | python -I -c \
        "import json,sys; m={x['statistic']:x['value'] for x in json.load(sys.stdin)['measurements']}; print(int(m['COUNT']), m['TOTAL_TIME'])" \
        2>/dev/null || echo "0 0"
}

before=$(sql "select count(*) from transactions")
retries_before=$(counter ledger.lock.retries)
read -r srv_count_before srv_time_before <<< "$(server_time)"
echo "== $NAME: $PLAN $* (transactions before: $before)"

# container CPU and memory every 5s while JMeter runs (docker stats CPU% is per core: 100% = one core)
( while true; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' >> "$OUT/docker-stats.csv"; sleep 5; done ) &
stats_pid=$!
trap 'kill $stats_pid 2>/dev/null || true' EXIT

"$JMETER" -n -t "$PLAN" -l "$OUT/results.jtl" -j "$OUT/jmeter.log" \
    -Jsample_variables=idemKey,replayed "$@"

kill $stats_pid 2>/dev/null || true
after=$(sql "select count(*) from transactions")
retries_after=$(counter ledger.lock.retries)
read -r srv_count_after srv_time_after <<< "$(server_time)"
echo "== transactions after: $after (created during the run: $((after - before))) ;" \
     "ledger.lock.retries during the run: $((retries_after - retries_before))"
python -I -c "
import sys
n = int(sys.argv[2]) - int(sys.argv[1]); t = float(sys.argv[4]) - float(sys.argv[3])
print('== server-side (inside the app) for the whole run: %d requests, mean %.1f ms' % (n, 1000 * t / n) if n else '== no server-side samples')
" "$srv_count_before" "$srv_count_after" "$srv_time_before" "$srv_time_after"
python -I -c "
import csv, sys, collections
cpu = collections.defaultdict(list)
for name, c, _ in csv.reader(open(sys.argv[1], encoding='utf-8')):
    if name.startswith(sys.argv[2]):
        cpu[name[len(sys.argv[2]):]].append(float(c.rstrip('%')))
for name, v in sorted(cpu.items()):
    print('== cpu %-10s avg %6.1f%%  max %6.1f%%  (%d samples, 100%% = one core)' % (name, sum(v) / len(v), max(v), len(v)))
" "$OUT/docker-stats.csv" "${COMPOSE_PROJECT_NAME:-ledger-engine}-"

echo "== reconciliation"
curl -s "$APP/api/v1/admin/reconciliation" | tee "$OUT/reconciliation.json"
echo

echo "== summary"
python -I loadtest/summarize.py "$OUT/results.jtl" --warmup "$WARMUP" --txn-delta $((after - before)) \
    --json "$OUT/summary.json"

# the dashboard filters by wall-clock second, so start it at the first full second after the warm-up
start=$(python -I -c "
import csv,datetime,sys
ts=min(int(r['timeStamp']) for r in csv.DictReader(open(sys.argv[1],encoding='utf-8')))
print(datetime.datetime.fromtimestamp((ts+int(float(sys.argv[2])*1000))/1000+1).strftime('%Y%m%d%H%M%S'))
" "$OUT/results.jtl" "$WARMUP")
# statistic_window=-1: percentiles over every measured sample. The default (20000) computes them over
# only the last 20000 samples, which disagrees with summarize.py on any run longer than that.
"$JMETER" -g "$OUT/results.jtl" -o "$OUT/dashboard" -j "$OUT/dashboard-jmeter.log" \
    -Jjmeter.reportgenerator.start_date="$start" \
    -Jjmeter.reportgenerator.statistic_window=-1 \
    > "$OUT/dashboard.log" 2>&1 || echo "(dashboard generation failed, see $OUT/dashboard.log)"
echo "== dashboard: $OUT/dashboard/index.html (from $start)"
