# Load test results

Every number here comes from the runs on 2026-11-15 described below. Nothing was tuned between or during runs; every run used the same app configuration.

## Environment

| | |
|---|---|
| Machine | Laptop, AMD Ryzen 5 5600H (6 cores / 12 threads, 3.3 GHz base), 15.4 GB RAM, on AC power, Windows "Balanced" power plan |
| OS | Windows 11 Home 10.0.26200 |
| Docker | Docker Desktop 29.6.2 on WSL2; the VM sees 12 CPUs and 7.46 GiB (WSL2 defaults, no custom limits) |
| Where things ran | App, Postgres 17 and Redis 7 in `docker compose` (the app reaches Postgres and Redis over the compose network). JMeter 5.6.3 on the Windows host, hitting `127.0.0.1:8080` through Docker Desktop's port forward |
| App settings | Java 21 (Eclipse Temurin JRE), default heap (max 2.0 GB), Tomcat's default 200 worker threads, HikariCP pool **20**, `connection-timeout` 3s, `lock_timeout` 2s, `statement_timeout` 5s, 3 posting attempts on lock errors. Scheduled reconciliation on, every 5 minutes |
| Background load | Three idle containers from another project were running (about 0.13 cores between them). Windows had 1.8 GB of RAM free at the start |
| Load generator | JMeter shares the same 12 logical CPUs as the Docker VM. Its CPU use was not measured |

## Method

1. Fresh compose stack under its own project name (`ledger-loadtest`), so it starts with an empty database.
2. **Seed** through the real services: `java -jar target/ledger-engine-0.1.0.jar --spring.profiles.active=seed` (with `DB_PORT` pointing at the compose Postgres).
   - It created 1,000 accounts with `AccountService` and deposited 1,00,000.00 into each with `TransactionFacade`, each deposit with its own idempotency key. That took 43.3s.
   - Reconciliation after seeding was healthy: 1,001 accounts, 1,000 transactions, 2,000 entries.
3. **Runs:** `loadtest/run.sh <name> <plan.jmx> -Jthreads=... -Jrampup=30 -Jduration=...`, in non-GUI mode.
   - Every run is duration-based: a 30s ramp-up, then the measured period.
   - **The first 30s of every run (the ramp-up) is the warm-up and is left out of the latency and throughput numbers.** It is still counted in the ledger checks, because those must account for every request sent.
4. **Numbers:**
   - Percentiles use the nearest-rank method over every sample in the measured window (`loadtest/summarize.py`).
   - The JMeter HTML dashboard is generated over the same window and agrees, within 2 ms on one p99.
   - The dashboard has to be built with `jmeter.reportgenerator.statistic_window=-1`. With the default (20,000), its percentiles cover only the last 20,000 samples. On these runs that made the transfer p95 read 872 ms instead of 796 ms.
5. **After every run:**
   - `GET /api/v1/admin/reconciliation`;
   - transactions created in the database (count after minus count before) compared with JMeter's 201 responses that were not replays;
   - for the retry storm, also compared with the number of unique keys sent.

Before the measured runs, a few 20-second smoke runs with 10 threads checked the tooling. Those are not reported. Their transactions are part of the "before" counts.

## Results

Measured window only (warm-up excluded). Latency is what JMeter saw, end to end through the port forward.

| Run | Threads | Measured | Requests | Throughput | p50 | p95 | p99 | Max | Status codes |
|---|---|---|---|---|---|---|---|---|---|
| Transfer, 1,000 accounts | 200 | 296.9 s | 140,961 | **474.7 req/s** | 405 ms | 796 ms | 1,089 ms | 5,779 ms | 201: 99.999%, 503: 0.001% (1) |
| Hot accounts, 10 accounts | 200 | 298.4 s | 57,011 | **191.0 req/s** | 931 ms | 1,749 ms | 2,503 ms | 5,487 ms | 201: 99.779%, 503: 0.221% (126) |
| Retry storm, 3 sends per key | 100 | 177.6 s | 205,766 | **1,158.5 req/s** | 25 ms | 306 ms | 445 ms | 1,057 ms | 201: 100% |
|   first send (`retry-1`) | | | 68,593 | 386.2 req/s | 201 ms | 399 ms | 571 ms | 1,057 ms | 201: 100% |
|   replays (`retry-2`, `retry-3`) | | | 137,173 | 772.4 req/s | 16-17 ms | 46-47 ms | 69-71 ms | 159 ms | 201: 100% |
| Hot pair, 2 accounts (extra run) | 200 | 299.2 s | 31,941 | **106.7 req/s** | 1,830 ms | 2,027 ms | 3,005 ms | 3,905 ms | 201: 99.64%, 503: 0.36% (115) |

Every 503 was `SYSTEM_BUSY` from HikariCP: `Connection is not available, request timed out after 3000ms`. There were no 409s, no 422s and no 5xx other than those. `ledger.lock.retries` stayed at 0 in every run, so there were no deadlocks and no lock timeouts.

### Ledger checks after each run

| Run | Transactions created (DB) | JMeter 201s, not replays (whole run) | Match | Reconciliation |
|---|---|---|---|---|
| Transfer | 150,993 | 150,993 | yes | healthy: 160,976 transactions, 321,952 entries, total 0.00 |
| Hot accounts | 63,190 | 63,190 | yes | healthy: 224,166 transactions, 448,332 entries, total 0.00 |
| Retry storm | 76,761 | 76,761; unique keys sent 76,761 | yes, and equals unique keys | healthy: 300,927 transactions, 601,854 entries, total 0.00 |
| Hot pair | 34,783 | 34,783 | yes | healthy: 335,710 transactions, 671,420 entries, total 0.00 |

- All seven reconciliation checks had 0 failures after every run.
- The scheduled reconciliation also ran six times during the load (every 5 minutes, about 4-10s each), and every run was healthy.
- In the runs with 503s, the unique keys sent exceed the transactions created by exactly the number of 503s: those requests created nothing and can be retried with the same key.

**Zero data loss** here means exactly this: after every run, every balance equalled the sum of its ledger entries, every transaction was balanced, all balances summed to 0.00, and every successful response matched exactly one transaction in the database.

### Inside the app, for context

| Run | Server-side mean (Spring `http.server.requests`) | App CPU avg / max | Postgres CPU avg / max | Redis CPU avg / max |
|---|---|---|---|---|
| Transfer | 409.8 ms | 3.5 / 5.6 cores | 4.2 / 4.8 cores | 0.2 / 0.4 cores |
| Hot accounts | 994.5 ms | 1.3 / 2.0 | 1.6 / 4.1 | 0.1 / 1.0 |
| Retry storm | 74.1 ms | 3.6 / 4.4 | 3.6 / 4.8 | 0.3 / 0.6 |
| Hot pair | 1,809.1 ms | 0.6 / 1.1 | 0.8 / 1.5 | 0.1 / 1.1 |

CPU comes from `docker stats` every 5s; 1 core = 100%. The server-side means cover the whole run, including warm-up.

## What the numbers mean

**Spread transfers: 475 req/s, latency is queueing for the connection pool.**
- With 200 threads and nothing else limiting them, Little's law gives the time in the system as 200 / 474.7 per second = 421 ms. The app's own mean was 410 ms, almost all of the client-side 420 ms.
- The time is spent inside the app, waiting for one of the 20 database connections. 20 connections at 475 transactions per second means each connection is busy about 42 ms per transfer.
- Unloaded, a single transfer takes about 11 ms inside the app (24 ms end to end, against 13 ms for a plain GET).
- The machine was busy: the app and Postgres used about 7.7 cores between them, and JMeter ran on the same 12 logical CPUs. CPU contention likely accounts for part of the gap between 11 ms and 42 ms. I did not measure JMeter's share.
- A bigger pool would probably not help much while Postgres already uses over 4 cores; that would have to be measured.

**Hot accounts: 191 req/s with 10 accounts, limited by row locks, not CPU.**
- Every transfer locks two of the ten accounts, so transfers that share an account run one after another.
- CPU fell to about 1.3 cores for the app and 1.6 for Postgres while latency more than doubled.
- Each waiting transfer holds a pool connection while it waits for the lock, so the pool fills with waiters. Some requests then wait past the 3s `connection-timeout` and get 503 (0.22%).

**Per-pair ceiling: 106.7 transfers/s on a single pair.**
- With every transfer on the same two accounts, everything runs strictly one at a time: 1 / 106.7 = **9.4 ms of lock hold per transfer**.
- The 10-account run reached 191 req/s, only 1.8 times one pair. Random pairs among ten accounts overlap often, so few transfers can run truly in parallel.

**Compared with Phase 3 (about 53/s).**
- The Phase 3 figure came from the deadlock-bait test: 200 transfers on one pair in about 3.5-3.9s (51-57/s). It ran inside the test suite, from a JVM on Windows talking to a Testcontainers Postgres through Docker's port forward. That works out to about 19 ms of lock hold per transfer, against 9.4 ms here. Here the app ran inside compose, next to Postgres.
- Measured round trip for `select 1` (3,000 samples after 500 warm-up):
  - Windows host to `127.0.0.1:55432`: p50 0.73 ms;
  - container to `postgres:5432`: p50 0.23 ms.
- A transfer makes about 9 round trips while it holds the lock. At idle that is roughly 6.6 ms against 2.1 ms, which **explains about 4.5 ms of the roughly 10 ms difference**.
- The rest was not measured directly. Likely contributors:
  - Under load, the host path was slower than at idle; Phase 3's SQL logging showed about 2 ms per round trip during the burst.
  - The Phase 3 burst lasted only about 4 seconds, in a test JVM with Hibernate statistics on, so JIT warm-up was incomplete.
  - Here the code had 30 seconds of warm-up and 5 minutes of steady load.
- In short, the ceiling is about 1 / (lock hold time), and the lock hold time is mostly database round trips.

**Retry storm: replays are cheap; transactions equal unique keys.**
- Each key was sent three times, back to back. The second and third sends were all answered from the Redis idempotency cache: p50 16-17 ms, with no database work.
- Only first sends posted anything: 76,761 transactions for 76,761 unique keys.
- The plan allows 409 here. There were none, because the three sends of a key are sequential in one thread: a later send never starts while the first is still running. The concurrent-duplicate case (409 `REQUEST_IN_PROGRESS`) is covered by concurrency tests 4 and 5, not by this run.
- Over the whole run there were 153,331 replays, 191 fewer than 2 x 76,761. JMeter stopped some threads partway through their last three-send iteration when the duration ended. Every key that was sent still has exactly one transaction.

**The 503s are overload behaviour: the system sheds load cleanly when the pool is saturated.**
- They came in bursts:
  - hot accounts: 94 of the 126 between 04:19:28 and 04:19:30 UTC;
  - hot pair: 101 of the 116 at 04:30:58-59 UTC, plus 14 at 04:31:03.
- In the hot runs, 200 threads compete for 20 connections, so about 180 requests are always waiting. Every transfer also waits for the same few row locks while holding its connection. The queue wait therefore sits within a second or two of the 3s `connection-timeout`.
- Any brief stall then pushes the requests already waiting past 3s together, and they all get 503 at once. The stall can be anything that slows the lock holder for a moment, on a laptop that is also running the load generator.
- Each 503 is a clean refusal:
  - `SYSTEM_BUSY` as `application/problem+json`, with `Retry-After: 1`;
  - nothing is written for that key, so a retry with the same key is safe. The ledger checks show unique keys sent exceed transactions created by exactly the number of 503s.
- **No money impact:** reconciliation was healthy after every run.
- A follow-up run with GC logging on and the scheduled reconciliation off still had bursts (see "Hot-pair diagnostic" below), which rules out both as the cause.

## Additional runs

Two more runs were made after the results above had been reviewed. They are reported separately here, and nothing above was changed.
1. **Transfer at 40 threads:** the transfer scenario again, with the same settings except 40 threads instead of 200.
2. **Hot-pair diagnostic:** the hot-pair scenario with GC logging on and the scheduled reconciliation off, to test the two leading explanations for the 503 bursts.

**These runs did not start from the same point as the ones above:**
- **Larger database:** both reused the database left by the four runs above. It held 335,710 transactions at the start, against about 10,000 when the original transfer run started. At the end it held 466,096 transactions and was 614 MB on disk, against Postgres's 128 MB `shared_buffers`.
- **Fresh JVM:** the app container was restarted before each run.
  - The transfer run started on the stopped stack.
  - The diagnostic run needed the changed configuration, so it got a recreated container.
  - Before the diagnostic run, a 60-second hot-pair warm-up warmed the new JVM. That warm-up is not reported: it produced 4,138 transactions, its ledger counts matched and reconciliation was healthy.
- **Free RAM:** Windows had 2.2 GB of RAM free when these runs started.

| Run | Threads | Measured | Requests | Throughput | p50 | p95 | p99 | Max | Status codes |
|---|---|---|---|---|---|---|---|---|---|
| Transfer, 1,000 accounts | 40 | 297.6 s | 96,172 | **323.2 req/s** | 108 ms | 235 ms | 342 ms | 1,653 ms | 201: 99.999%, 409: 0.001% (1) |
| Hot pair, diagnostic | 200 | 299.6 s | 24,700 | **82.5 req/s** | 2,299 ms | 3,154 ms | 3,477 ms | 3,954 ms | 201: 97.826%, 503: 2.174% (537) |

| Run | Transactions created (DB) | JMeter 201s, not replays (whole run) | Match | Reconciliation |
|---|---|---|---|---|
| Transfer, 40 threads | 99,193 | 99,193 | yes | healthy: 434,903 transactions, 869,806 entries, total 0.00 |
| Hot pair, diagnostic | 27,055 | 27,055 | yes | healthy: 466,096 transactions, 932,192 entries, total 0.00 |

| Run | Server-side mean | App CPU avg / max | Postgres CPU avg / max | Redis CPU avg / max |
|---|---|---|---|---|
| Transfer, 40 threads | 114.8 ms | 4.1 / 7.0 cores | 4.1 / 6.2 cores | 0.3 / 1.6 cores |
| Hot pair, diagnostic | 2,288.8 ms | 0.8 / 2.0 | 0.8 / 1.7 | 0.2 / 1.1 |

After both runs, all seven reconciliation checks had 0 failures, and `ledger.lock.retries` stayed at 0.

### Transfer at 40 threads

**Fewer threads gave lower latency and lower throughput.**
- p50 was 108 ms against 405 ms at 200 threads, and p99 was 342 ms against 1,089 ms. Throughput was 323 req/s against 475.
- Little's law gives 40 / 323.2 per second = 124 ms in the system. The app's own mean was 115 ms.

**This is not a clean comparison with the 200-thread run.**
- Compared with the original transfer run, both the app and Postgres used more CPU for each transfer:
  - app: about 12.7 ms per transfer (4.1 cores / 323 per second), against 7.4 ms;
  - Postgres: about 12.7 ms, against 8.8 ms.
- Having fewer requests in flight should not make Postgres spend more CPU on each transfer, so something else had changed too. Two candidates:
  - the database was about 33 times larger at the start;
  - the app JVM had just restarted.
- This run does not separate those two from the change in thread count. A clean comparison needs both thread counts on a freshly seeded database.

**One request got a 409 `REQUEST_IN_PROGRESS`, which it should not have. Fixed in commit `d17485e` (`fix: claim own marker after a lost SET NX reply`). Every load test in this document ran before that fix.**
- Its key was a fresh UUID, and JMeter sent it only once (JMeter's HTTP retry count is 0).
- Afterwards the key had no idempotency row, no transaction and no Redis entry. Nothing was posted, and the database count still equals JMeter's 201s.
- The request took 1,653 ms, the longest in the run.
- The code has one way to return 409 for a key that was sent once:
  1. The Redis `SET NX` that marks the key IN_PROGRESS reached Redis and was applied, but the app saw it fail. The Redis command timeout is 200 ms.
  2. The app then treated Redis as unavailable for 1s and checked Redis again.
  3. That second check found the request's own IN_PROGRESS marker and answered 409.
- The second check only reaches Redis if the 1-second skip window has passed, which fits a request that stalled for 1.65s.
- **The load-test log can't confirm it.** Recreating the app container for the diagnostic run discarded this run's app log and its `ledger.redis.failures` counter. The mechanism was reproduced in a test instead (below).
- **Impact before the fix:**
  - Money was safe: nothing was posted.
  - The client got a wrong "still in progress" answer. Retrying with the same key got 409 until the 10s IN_PROGRESS marker expired, and then succeeded.
- **The fix (`d17485e`), in the Redis fast path; the ledger itself was not affected:**
  - When `SET NX` errors or times out, the key is read back once.
    - If it holds the marker with this request's own owner token, the request treats the key as claimed and carries on.
    - If Redis can't be read, the request falls through to Postgres as a cache miss.
    - If the marker belongs to another request, the normal lookup answers.
  - The facade no longer looks Redis up a second time on a miss, so a request can never get 409 because of its own marker.
  - A successful read-back also closes the 1s "skip Redis" window. That lets the completed result, or the compare-and-delete cleanup after a failure, reach Redis instead of leaving the marker behind.
- **The test (`LostSetNxReplyTest`)** wraps the Redis client so that `SET NX` is carried out in the real Redis and then reported as a timeout.
  - With the error delayed past the skip window, as in the load test, the old code answered exactly this 409. The fixed code posts exactly one transaction and replays it afterwards.
  - Against the old code the test also caught two related defects:
    - after an immediate timeout, the completed result never replaced the marker;
    - after a failed posting, the marker was never deleted.
  - Both are fixed and covered, as is the case where Redis can't be read back.

### Hot-pair diagnostic

**Configuration.** This run used the same image as the original hot-pair run, with an overlay (`loadtest/compose-diagnostic.yml`):
- GC and safepoint logging on (`-Xlog:gc*,safepoint`);
- scheduled reconciliation off. The app log confirms it: its only reconciliation lines are the two that `run.sh` requested;
- the Hikari pool's active and pending connections recorded about every 2 seconds, from the actuator.

**The bursts did not disappear. There were more of them: 537 503s in 12 bursts, against 115 in 2.**

| Window (30 s) | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Original hot pair: 201/s | 92 | 109 | 105 | 109 | 108 | **94** | 109 | 111 | 108 | 109 | 107 |
| Original hot pair: 503s | 1 | 0 | 0 | 0 | 0 | **115** | 0 | 0 | 0 | 0 | 0 |
| Diagnostic: 201/s | 90 | **71** | 80 | 87 | 83 | **80** | 86 | 89 | 88 | **70** | **78** |
| Diagnostic: 503s | 0 | **156** | 1 | 0 | 0 | **63** | 0 | 0 | 0 | **203** | **114** |

Window 0 is the ramp-up; it is counted here but not in the main results.

**Ruled out as the cause:**
- **Scheduled reconciliation:** it was off, and the bursts still happened.
- **GC:** the log has 220 pauses, the longest 39.8 ms. No safepoint took over 50 ms, and the longest pause in or next to a burst second was 30.6 ms. A 503 needs a request to wait 3 seconds, so pauses of that size don't explain it.

**This is overload behaviour.**
- **The pool was full for the whole run.** In 133 of the 134 samples in the measured window, all 20 connections were active and 179 requests were waiting for one. The other sample showed 7 active and none waiting.
- **The queue wait sits close to the timeout.** With about 180 requests waiting at about 85 per second, a request waits about 2.1s for a connection, against a 3s `connection-timeout`. Little's law agrees: 200 / 82.5 = 2.42s in the system, and the app's mean was 2.29s.
- **So a short dip causes a burst.** Any brief stall pushes the requests already waiting past 3s together, and they get a clean 503 `SYSTEM_BUSY` with `Retry-After: 1`. Every 30-second window with 503s had a throughput dip, in this run and in the original hot-pair run.
- **Margin explains the difference between the runs.** This run had a lower baseline, about 85/s against 108/s, which leaves less margin: about 0.9s against 1.3s. More dips crossed the line, giving 12 bursts against 2.
  - The lower baseline means about 12.4 ms of lock hold per transfer, against 9.4 ms.
  - Likely contributors: the database was about 50% larger, and the JVM had less load behind it.
- **No money impact.** Nothing was written for any 503, the ledger counts matched, and reconciliation was healthy.

If the hot-account 503 rate mattered in production, the options would be:
- limit how many requests can wait on one hot account;
- shorten the lock hold;
- lengthen `connection-timeout`, which trades 503s for longer waits.

None was applied here.

## Caveats

- **JMeter ran on the same machine as the stack and competed with it for CPU.**
  - The Docker VM's 12 CPUs are the laptop's 12 logical CPUs, and JMeter's threads ran on those same CPUs.
  - JMeter's own CPU use was not measured, so every number here includes whatever JMeter took from the app, Postgres and Redis.
- **Memory was tight.**
  - Windows had only 1.8 GB of RAM free at the start of the original runs, and 2.2 GB at the start of the additional runs.
  - The Docker VM was allowed up to 7.46 GiB.
  - Memory pressure on the host was not measured.
- **One laptop runs everything:** load generator, app, database and cache share the same CPUs and memory. On separate machines the numbers would differ, probably with higher throughput and lower latency.
- **Two network hops:** JMeter reaches the app through Docker Desktop's port forward on Windows, which adds latency (about 11 ms on an idle request) that a real deployment wouldn't have.
- **Fixed amounts:** amounts were random from 1.00 to 10.00 against 1,00,000.00 balances, so no transfer was refused for lack of funds. These runs measure throughput and correctness under concurrency, not business rejections.
- **Single runs:** each scenario was run once, so run-to-run variation is not measured.

## Reproducing

```
docker compose up -d --build            # DB_HOST_PORT=55432 in .env if 5432 is taken on the host
DB_PORT=55432 java -jar target/ledger-engine-0.1.0.jar --spring.profiles.active=seed
export JMETER=/path/to/apache-jmeter-5.6.3/bin/jmeter WARMUP=30
loadtest/run.sh transfer     loadtest/ledger-transfer.jmx     -Jthreads=200 -Jrampup=30 -Jduration=330
loadtest/run.sh hot-accounts loadtest/ledger-hot-accounts.jmx -Jthreads=200 -Jrampup=30 -Jduration=330
loadtest/run.sh retry-storm  loadtest/ledger-retry-storm.jmx  -Jthreads=100 -Jrampup=30 -Jduration=210
loadtest/run.sh hot-pair     loadtest/ledger-hot-accounts.jmx -Jthreads=200 -Jrampup=30 -Jduration=330 -JhotAccounts=2

# additional runs
loadtest/run.sh transfer-40  loadtest/ledger-transfer.jmx     -Jthreads=40  -Jrampup=30 -Jduration=330
docker compose -f docker-compose.yml -f loadtest/compose-diagnostic.yml up -d app
loadtest/run.sh hot-pair-diag-warmup loadtest/ledger-hot-accounts.jmx -Jthreads=200 -Jrampup=30 -Jduration=60 -JhotAccounts=2
loadtest/run.sh hot-pair-diag        loadtest/ledger-hot-accounts.jmx -Jthreads=200 -Jrampup=30 -Jduration=330 -JhotAccounts=2
docker compose cp app:/tmp/gc.log loadtest/report/hot-pair-diag/gc.log
```

Each run writes `loadtest/report/<name>/`: `results.jtl`, `summary.json`, `reconciliation.json`, `docker-stats.csv`, and the HTML dashboard. That folder is gitignored.
