"""Summarize a JMeter CSV results file (.jtl).

Latency and throughput are computed over the measured window only: everything that started in
the first --warmup seconds (JIT warm-up, connection pools filling, threads still ramping up) is
left out. Status-code counts are reported for both the measured window and the whole run, because
the ledger checks (transactions created vs successful samples) have to count every request sent.

usage: python summarize.py results.jtl --warmup 30 [--txn-delta N] [--json out.json]
"""
import argparse
import csv
import json
import math
import sys
from collections import Counter, defaultdict


def percentile(sorted_values, p):
    # nearest-rank: the smallest value with at least p% of samples at or below it
    if not sorted_values:
        return None
    rank = max(1, math.ceil(p / 100 * len(sorted_values)))
    return sorted_values[rank - 1]


def stats(samples, window_ms):
    elapsed = sorted(s['elapsed'] for s in samples)
    codes = Counter(s['code'] for s in samples)
    n = len(samples)
    return {
        'samples': n,
        'throughput_per_s': round(n / (window_ms / 1000), 1) if window_ms > 0 else None,
        'p50_ms': percentile(elapsed, 50),
        'p95_ms': percentile(elapsed, 95),
        'p99_ms': percentile(elapsed, 99),
        'max_ms': elapsed[-1] if elapsed else None,
        'mean_ms': round(sum(elapsed) / n, 1) if n else None,
        'codes': {c: {'count': k, 'pct': round(100 * k / n, 3)} for c, k in sorted(codes.items())},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('jtl')
    ap.add_argument('--warmup', type=float, default=30)
    ap.add_argument('--txn-delta', type=int, default=None,
                    help='transactions created in the database during the run')
    ap.add_argument('--json', default=None)
    args = ap.parse_args()

    rows = []
    with open(args.jtl, newline='', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            rows.append({
                'ts': int(r['timeStamp']),
                'elapsed': int(r['elapsed']),
                'label': r['label'],
                'code': r['responseCode'],
                'key': r.get('idemKey'),
                'replayed': (r.get('replayed') or '').lower() == 'true',
            })
    if not rows:
        sys.exit('no samples in ' + args.jtl)

    start = min(r['ts'] for r in rows)
    end = max(r['ts'] + r['elapsed'] for r in rows)
    cutoff = start + int(args.warmup * 1000)
    measured = [r for r in rows if r['ts'] >= cutoff]
    window_ms = end - cutoff

    by_label = defaultdict(list)
    for r in measured:
        by_label[r['label']].append(r)

    created = sum(1 for r in rows if r['code'] == '201' and not r['replayed'])
    summary = {
        'run_seconds': round((end - start) / 1000, 1),
        'warmup_seconds_excluded': args.warmup,
        'measured_seconds': round(window_ms / 1000, 1),
        'measured': {'all': stats(measured, window_ms),
                     **{label: stats(s, window_ms) for label, s in sorted(by_label.items())}},
        'whole_run': {
            'samples': len(rows),
            'codes': dict(sorted(Counter(r['code'] for r in rows).items())),
            'created_201_not_replayed': created,
            'replayed_201': sum(1 for r in rows if r['code'] == '201' and r['replayed']),
        },
    }
    keys = {r['key'] for r in rows if r['key']}
    if keys:
        summary['whole_run']['unique_keys_sent'] = len(keys)
        summary['whole_run']['unique_keys_with_a_201'] = len({r['key'] for r in rows
                                                              if r['key'] and r['code'] == '201'})
    if args.txn_delta is not None:
        checks = {'transactions_created_in_db': args.txn_delta,
                  'db_matches_jmeter_created': args.txn_delta == created}
        if keys:
            checks['db_matches_unique_keys_sent'] = args.txn_delta == len(keys)
        summary['checks'] = checks

    print_text(summary)
    if args.json:
        with open(args.json, 'w', encoding='utf-8') as f:
            json.dump(summary, f, indent=2)


def print_text(s):
    print(f"run {s['run_seconds']}s, first {s['warmup_seconds_excluded']:g}s excluded, "
          f"measured {s['measured_seconds']}s")
    print(f"{'label':<10} {'samples':>8} {'req/s':>8} {'p50':>6} {'p95':>6} {'p99':>6} {'max':>6}  codes (% of label)")
    for label, st in s['measured'].items():
        codes = ', '.join(f"{c}={v['count']} ({v['pct']}%)" for c, v in st['codes'].items())
        print(f"{label:<10} {st['samples']:>8} {st['throughput_per_s']:>8} {st['p50_ms']:>6} {st['p95_ms']:>6} "
              f"{st['p99_ms']:>6} {st['max_ms']:>6}  {codes}")
    w = s['whole_run']
    print(f"whole run: {w['samples']} samples, codes {w['codes']}, created (201, not replayed) "
          f"{w['created_201_not_replayed']}, replayed 201 {w['replayed_201']}")
    if 'unique_keys_sent' in w:
        print(f"unique keys sent {w['unique_keys_sent']}, unique keys with a 201 {w['unique_keys_with_a_201']}")
    if 'checks' in s:
        print('checks:', s['checks'])


if __name__ == '__main__':
    main()
