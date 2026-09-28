# 🖥️ Development Environment

Goal: the machine is never the reason a result is in doubt. This project runs **six JVMs and seventeen
containers**, and where that runs changes what can be measured.

## Why this file exists

Phase 21 was developed on a **MacBook Air M2, 8 GB RAM**, with Docker Desktop holding 3.82 GiB of it.
Steady state was fine — all seventeen containers idle at **1387 MiB of 3.82 GiB**. Peaks were not, and
they failed in ways that look like code defects:

| Symptom | Actual cause |
|---|---|
| Three containers "failed to start" | `ecomdemo-app` took **>120 s** to initialise cold and exhausted its healthcheck retries. `gateway-service`, `prometheus` and `grafana` all wait on `app: service_healthy`. |
| `OrderApiIT` hung for **780 s** then timed out | Seventeen containers competing for the Docker daemon. The same class runs in **11.95 s** on an idle machine — a **66×** swing. |
| BuildKit died: `frontend grpc server closed unexpectedly` | An image build runs a full Maven reactor build *inside a container* — a seventh JVM — while six were already running. |
| Three commands killed mid-run | Host memory exhausted; 1.5 GB of swap already in use. |

Each of those cost real time to prove environmental rather than a bug. **That is the expense this file
is trying to prevent.** Since Phase 22 the project is developed and tested on a larger machine.

## The two machines

| | Mac (Phases 0–21) | Workstation (Phase 22 →) |
|---|---|---|
| CPU / RAM | M2, 8 cores / **8 GB** | i9 / **64 GB** |
| Docker allocation | 3.82 GiB (≈half the host) | 32 GB via WSL2 |
| GPU | — | RTX 5070 Ti, 16 GB VRAM |
| Verdict | steady state fine, peaks blocked | comfortable |

The GPU matters later, not now: **Phases 27–29** (Spring AI, semantic search, an AI assistant) can use
16 GB of VRAM for local embeddings or inference. Phases 22–26 do not touch it.

## Windows setup (WSL2)

The workstation runs Windows, so everything happens **inside WSL2**. Docker, the Maven wrapper and
`scripts/smoke-test.sh` are all Linux-native; none of them is run from PowerShell.

1. **WSL2 with Ubuntu.**
2. **Clone inside the Linux filesystem** — `~/projects/ecomdemo`, *never* `/mnt/c/...`. Two reasons, both
   real: Maven and Git across the `/mnt/c` boundary are several times slower, and it is where CRLF line
   endings appear and break `#!/usr/bin/env bash` in the smoke script. A clone made inside WSL2 with
   default Git config avoids both.
3. **Docker Desktop with the WSL2 backend**, with integration enabled for the Ubuntu distro. Published
   ports are then reachable at `localhost` from **both** WSL2 and Windows, so the gateway on `:8080` and
   Grafana on `:3000` open in a Windows browser unchanged.
4. **`%UserProfile%\.wslconfig`**, set explicitly rather than left to the default half-the-host:

       [wsl2]
       memory=32GB
       processors=12

   On the WSL2 backend Docker's resources come **from WSL2** — the Docker Desktop memory slider of the
   Hyper-V days does not apply.
5. **IntelliJ**: point the terminal at WSL and open the project from the WSL path. Editing through
   `\\wsl$\...` works but inherits the slow-path and line-ending problems above.

## What must be installed inside WSL2

Four things, and the list is shorter than it looks:

- **JDK 21** (Temurin, matching `.github/workflows` CI)
- **Git**
- **`gh` CLI**, authenticated — the Git protocol in `git-workflow.md` uses `gh pr create` and
  `gh pr merge`
- **`curl`** and **`python3`** — the smoke script's assertion helpers are Python one-liners

Maven comes from the wrapper (`./mvnw`, 3.9.16). **No local `psql`, `redis-cli` or Kafka CLI is
required**: every helper in `scripts/smoke-test.sh` prefers a binary on `PATH` and falls back to
`docker exec` into the relevant container, so on a clean Ubuntu it takes the container path for all of
them.

## Secrets

`.env` is **gitignored and stays that way**. Copy `.env.example` and set the values yourself:

- **`JWT_SECRET`** — at least 32 characters. HS256 signs with a 256-bit key, and `JwtKeyConfig`
  refuses a shorter one rather than padding it. Unset, every service generates its own random key and a
  token issued by one is rejected by the next — which looks like a bug, not like missing configuration.
- Since Phase 21: **`APP_PORT=8084`** and **`GATEWAY_PORT=8080`**. The gateway owns the client port;
  `APP_PORT` is only for white-box checks. Leaving `APP_PORT=8080` makes the gateway container die with
  *"port is already allocated"* — a configuration mistake reported as a Docker networking error.

Never paste a secret into a commit, a PR, or the conversation.

## Running the build and the stack

    ./mvnw clean verify          # the whole reactor: unit + integration tests
    docker compose up --build --wait
    ./scripts/smoke-test.sh      # ~315 checks against the live stack
    docker compose --profile tools down   # --profile tools, or the network survives the stop

## No resource rationing on the workstation

**Decided by the user on 2026-09-27: on the workstation, memory and CPU are not constraints.** Tests
may run with the full stack up, builds may run beside it, containers are sized for the workload rather
than to squeeze into a small VM, and a suspicion can be checked by running something ten times. The
Mac-era rules — *bring the stack down before `verify`*, *never build while the stack is up*, and limits
chosen so five JVMs fit into 3.8 GB — no longer apply, and were re-measured rather than inherited:

| Measured on the workstation | Result |
|---|---|
| `./mvnw clean verify`, stack down | 2:03 – 2:28 |
| `./mvnw clean verify`, **full stack up** | passes since the pre-Phase-23 hardening (it did not before — see below) |
| `docker compose up --build --wait`, cold | 25 s – 2:14 (the long one pulls base images) |
| `scripts/smoke-test.sh`, cold stack | about 5 minutes |
| JVM non-heap per service | 107 – 179 MiB — a fixed cost, which is why the heap share is 50%, not 75% |

**Running with the stack up found a real test defect.** Every build until the workstation had the stack
down, so nothing listened on `localhost:8081-8084`. `EdgeSecurityIT` depended on that silently: its
routes pointed there, and with the stack up the "empty" upstream was catalog-service answering 401.
Rationing hid it. The test now arranges a closed port itself.

**What still holds is the difference between a cold and a warm stack.** Verification uses a COLD stack
(`down`, then `up --build --wait`), because a warm one has already paid the costs a cold start pays —
topic creation, JIT, connection pools — and the Kafka partition race fixed before Phase 23 appeared
only on cold starts. A measurement should say which it was.

## Quirks of this machine

Each of these cost time to prove environmental; they are written down so the next one does not.

- **The WSL2 VM can be paused by the host** (Windows sleeping). One `verify` took **86 minutes** with
  the CPU time of a 2-minute one: the Docker daemon took 30 minutes to start a container and the JVM
  sat silent for 54. `dmesg | grep TimeSync` showed Hyper-V re-syncing the clock at the second the JVM
  resumed. If a run is absurdly slow, check that and rerun before diagnosing code.
- **Windows reserves port ranges for Hyper-V/WinNAT**, and they move — on 2026-09-27 the range
  9022-9121 blocked Prometheus' 9090 (*"ports are not available … /forwards/expose returned
  unexpected status: 500"*) and a few hours later did not. Check with
  `/mnt/c/Windows/System32/netsh.exe interface ipv4 show excludedportrange protocol=tcp`. On
  2026-09-28 it was 9014-9113 and the user moved Prometheus' HOST port to **19090** for good:
  `"${PROMETHEUS_PORT:-19090}:9090"` in compose, the same default in the smoke test's
  `PROMETHEUS_URL`, and in `.env.example`. Open Prometheus at http://localhost:19090. Only the host
  side moved — inside the network it is still `prometheus:9090`. If 19090 is ever reserved, check
  the ranges again and set `PROMETHEUS_PORT` in `.env`.
- **A stopped container's name takes ~12 s to fail to resolve** inside the Compose network (Docker's
  DNS forwards the unknown name upstream). It is why Phase 22's retry budget is built on the read
  timeout, not the connect timeout.

## Reporting results

Rule 8 of `CLAUDE.md` — *never report a check as passed without running it* — applies per machine. When
a suite runs somewhere Claude Code cannot see, the report says **whose run it was** and shows the
output. "Verified on the workstation, output below" and "I ran this" are different claims, and the
difference is the whole value of the rule.

## Moving between machines

State lives in files and Git, which is what makes the switch cheap. On a new machine, follow the
session-start sequence in `execution-protocol.md` §1 — git state → `docs/progress/CURRENT.md` →
`RECENT.md` → the current phase file. `CURRENT.md`'s "Next action" is written so that a session with
**zero conversation history** can continue.

One caveat worth stating: `CURRENT.md` sometimes carries notes that were true of the machine that wrote
them — Phase 21 left *"never build while the stack is up"* and *"the app's healthcheck budget is tight
on a cold start"*. Both were measurements of the Mac, and both were re-measured on the workstation
(above). **Re-measure rather than inherit them.**
