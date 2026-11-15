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

**The 503s came in bursts, and the cause is not established.**
- Hot accounts: 94 of the 126 came between 04:19:28 and 04:19:30 UTC.
- Hot pair: 101 of the 116 came at 04:30:58-59 UTC, plus 14 at 04:31:03.
- A pool timeout here means the transaction holding the row lock stalled for about 2-3s, so every queued transfer ran out of time together. What I checked:
  - Postgres checkpoints: they run continuously, paced over about 270s, with sync phases under 1s. No spike lines up with either burst.
  - Redis snapshots: they ran every 60s, but the 04:18:48 and 04:19:51 snapshots miss the 04:19:29 burst. Redis failures were 0.
  - JVM GC: 575 young-generation pauses, mean 8.9 ms, no full GC. GC logging was not on, so one long pause can't be ruled out completely.
  - Scheduled reconciliation: the run that finished at 04:31:03 started at about 04:30:56, right at the hot-pair burst. The hot-accounts burst doesn't line up with any reconciliation run.
- Other candidates: a stall of the WSL2 VM (Windows had little free memory), or the reconciliation scan competing with a fully serialized workload.
- Pinning it down needs a rerun of the hot-pair scenario with GC logging, the scheduled reconciliation off, and per-second sampling. That would be a different configuration from the runs reported here.

## Caveats

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
```

Each run writes `loadtest/report/<name>/`: `results.jtl`, `summary.json`, `reconciliation.json`, `docker-stats.csv`, and the HTML dashboard. That folder is gitignored.
