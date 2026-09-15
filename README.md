# PCM Health Monitoring Platform (PHMP) – Phase 1 MVP

Production-shaped **local POC** that validates the PCM communication lifecycle for nightly vendor builds across **US** and **EU** profiles.

Default mode is **simulate** so the suite runs on your Mac with no device or cloud dependency. Switch to **device** mode for lab ADB + Rest Assured execution.

## Quick start (recommended)

```bash
cd ~/Projects/phmp-pcm-health-monitor
chmod +x scripts/*.sh
./scripts/verify-poc.sh
```

That runs the full US + EU simulate lifecycle, checks artifacts, and opens:

- `reports/phmp-release-gate.html` — combined US/EU release decision
- `reports/phmp-execution-report-us.html`
- `reports/phmp-execution-report-eu.html`

Or run without auto-open:

```bash
./scripts/run-local.sh simulate both
```

## What Phase 1 covers

| Area | Status |
|------|--------|
| Java 21 + Maven + JUnit 5 + Rest Assured + Allure | Yes |
| Config + logging + local.properties | Yes |
| ADB device utilities + simulator | Yes |
| Flash / OTA deploy verification | Yes |
| Installation / Startup / Authentication / WebSocket / Environment | Yes |
| Health monitor + Ping analyzer + Disconnect/Reconnect | Yes |
| Cloud / Mothership inject + correlation | Yes |
| HTML + Allure + history JSONL + combined release gate | Yes |
| GitHub Actions nightly workflow | Yes |
| Jenkins pipeline (optional) | Yes |

## Phase 1 validation pipeline (12 gates)

1. Flash / OTA Deployment  
2. Installation  
3. Startup  
4. Authentication  
5. WebSocket  
6. Environment (US/EU)  
7. Socket Health  
8. Ping / Pong  
9. Disconnect / Reconnect  
10. Cloud Messaging  
11. IoT Companion Gate  
12. Release Gate Decision  

**Release rule:** any critical failure is release-blocking until triage or explicit waiver. IoT companion is simulated in `phmp.mode=simulate` and runs `RELEASE_GATE` on a lab terminal in device mode.

## Project layout

```text
src/main/java/com/poynt/phmp/
  config/        # YAML + local.properties loader
  device/        # ADB + simulated device clients
  validation/    # Day 1–2 validators (deploy/install/startup/auth/ws/env/reconnect)
  monitoring/    # Day 3 health + ping
  cloud/         # Day 3 messaging + correlation
  logs/          # Device log signal helpers (device mode)
  reporting/     # HTML + console + history + combined gate dashboard
  engine/        # Lifecycle orchestrator + release gate
src/test/java/   # US/EU JUnit suites
.github/workflows/phmp-nightly.yml
Jenkinsfile
scripts/         # Local runners + verify-poc
docs/            # Day 1–4 implementation + deployment guide
```

## Common commands

```bash
# Leadership demo: green simulate run, then the real terminal run (see docs/THURSDAY_DEMO_SCRIPT.md)
./scripts/demo.sh            # both acts
./scripts/demo.sh simulate   # ~5s, all-green US + EU
./scripts/demo.sh device     # ~5.5min against the physical PST3

# Full local MVP (US + EU simulate) + artifact checks
./scripts/verify-poc.sh

# Full local MVP without open/verify extras
./scripts/run-local.sh simulate both

# Single region
./scripts/run-us.sh
./scripts/run-eu.sh

# Device / lab mode (ADB preflight + Maven)
cp local.properties.example local.properties   # fill serials

# Live, watchable run against one terminal: streams the terminal's own PCM log lines next to each
# gate verdict, traces every adb command, and refuses to start if the terminal cannot authenticate.
./scripts/run-live.sh US

# Individual steps, if you prefer them separately
./scripts/qualify-device.sh <SERIAL>            # proves the gates' log markers exist on this build
./scripts/fetch-device-token.sh <SERIAL> --env dev   # reads the terminal's token from logcat
./scripts/run-device.sh both                    # validates adb devices first
# or: ./scripts/run-device.sh US

# Allure HTML (open target/site/allure-maven-plugin/index.html)
./scripts/allure-report.sh --open
```

## Documentation

- Leadership one-pager: [`docs/LEADERSHIP_ONE_PAGER.md`](docs/LEADERSHIP_ONE_PAGER.md)
- PRR draft (go / no-go): [`docs/PRR_PHASE1_DRAFT.md`](docs/PRR_PHASE1_DRAFT.md)
- Team status update: [`docs/TEAM_STATUS_UPDATE.md`](docs/TEAM_STATUS_UPDATE.md)
- Sequential Day 1–4 implementation + deployment: [`docs/PHASE1_IMPLEMENTATION_AND_DEPLOYMENT_GUIDE.md`](docs/PHASE1_IMPLEMENTATION_AND_DEPLOYMENT_GUIDE.md)
- Lab device checklist (serials / endpoints / token): [`docs/LAB_DEVICE_CHECKLIST.md`](docs/LAB_DEVICE_CHECKLIST.md)
- Leadership demo script: [`docs/LEADERSHIP_DEMO_AND_RUN_GUIDE.md`](docs/LEADERSHIP_DEMO_AND_RUN_GUIDE.md)
- 8-minute demo script (real PST3 + simulate): [`docs/THURSDAY_DEMO_SCRIPT.md`](docs/THURSDAY_DEMO_SCRIPT.md) — driver: `./scripts/demo.sh`

## Technology foundation (Phase 1)

| Layer | Choice |
|-------|--------|
| Language | Java 21 |
| Test Framework | JUnit 5 |
| API Automation | Rest Assured |
| Device Communication | ADB (+ simulator) |
| Build | Maven |
| CI/CD | GitHub Actions (+ optional Jenkins) |
| Reporting | HTML + Allure + history JSONL + combined gate |
| Logging | SLF4J / Logback |
| Configuration | YAML + Properties |

> Spring Boot is deferred to Phase 2 platform modularization; Phase 1 keeps a lean Maven/JUnit automation core for fast local and CI execution.

## Scope boundary

This repository implements **Phase 1 only** from the Engineering Design Proposal. Phase 2–4 (Environment Manager service, Log Intelligence Engine, Grafana/Prometheus, protocol adapters, etc.) are intentionally out of scope here.
