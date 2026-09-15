# PHMP Lab Device Checklist (Phase 1)

Use this checklist before the first real-device run. Replace every `YOUR_*` / `<...>` placeholder with lab values. Do **not** commit secrets.

---

## 1) Fill these placeholders

### Host / tools

| Item | Placeholder / command | Your value |
|------|------------------------|------------|
| JDK 21+ | `java -version` | _______________ |
| Maven 3.9+ | `mvn -version` | _______________ |
| ADB | `adb version` | _______________ |
| Lab machine hostname | `YOUR_LAB_HOST` | _______________ |

### Devices (ADB serials)

| Region | Env var | Placeholder | Your value |
|--------|---------|-------------|------------|
| US | `PHMP_US_SERIAL` | `YOUR_US_ADB_SERIAL` | e.g. `P6U123456789` |
| EU | `PHMP_EU_SERIAL` | `YOUR_EU_ADB_SERIAL` | e.g. `P6U987654321` |

Find serials:

```bash
adb devices -l
```

### Cloud / Mothership auth

| Item | Env / property | Placeholder |
|------|----------------|-------------|
| Bearer token | `PHMP_CLOUD_TOKEN` / `phmp.cloud.token` | `YOUR_LAB_CLOUD_BEARER_TOKEN` |

Token is sent as: `Authorization: Bearer <token>`

### Endpoints (edit `src/main/resources/application.yaml`)

| Region | Setting | Placeholder in YAML today | Replace with |
|--------|---------|---------------------------|--------------|
| US WebSocket | `phmp.websocket.us.endpoint` | `wss://pcm-us.lab.poynt.net/v1/socket` | `wss://YOUR_US_PCM_HOST/...` |
| US Discovery | `phmp.websocket.us.discovery` | `https://discovery-us.lab.poynt.net` | `https://YOUR_US_DISCOVERY_HOST` |
| EU WebSocket | `phmp.websocket.eu.endpoint` | `wss://pcm-eu.lab.poynt.net/v1/socket` | `wss://YOUR_EU_PCM_HOST/...` |
| EU Discovery | `phmp.websocket.eu.discovery` | `https://discovery-eu.lab.poynt.net` | `https://YOUR_EU_DISCOVERY_HOST` |
| US Cloud API | `phmp.cloud.us.baseUrl` | `https://api-us.lab.poynt.net` | `https://YOUR_US_API_HOST` |
| US Inject path | `phmp.cloud.us.injectPath` | `/v1/pcm/messages` | `YOUR_INJECT_PATH` |
| US Mothership | `phmp.cloud.us.mothershipPath` | `/v1/mothership/events` | `YOUR_MOTHERSHIP_PATH` |
| EU Cloud API | `phmp.cloud.eu.baseUrl` | `https://api-eu.lab.poynt.net` | `https://YOUR_EU_API_HOST` |
| EU Inject path | `phmp.cloud.eu.injectPath` | `/v1/pcm/messages` | `YOUR_INJECT_PATH` |
| EU Mothership | `phmp.cloud.eu.mothershipPath` | `/v1/mothership/events` | `YOUR_MOTHERSHIP_PATH` |

### Package / image expectations

| Setting | Default placeholder | Notes |
|---------|---------------------|-------|
| `phmp.build.packageName` | `co.poynt.services.pcm` | Must match installed APK |
| `phmp.build.sharedUid` | `android.uid.system` | Adjust if lab differs |
| `phmp.build.expectedAbi` | `arm64-v8a` | Must appear in device ABI list |
| `phmp.build.minVersionCode` | `1` | Raise to nightly minimum if needed |
| `phmp.deployment.expectedImageChannel` | `nightly-vendor` | Align with flash/OTA channel naming |

### Device system properties PHMP reads

Set / verify on each terminal (values must match YAML for that region):

| Property | Purpose |
|----------|---------|
| `persist.poynt.srvc.url.pcm` (or `persist.poynt.pcm.endpoint`) | Selected PCM WebSocket endpoint |
| `persist.poynt.pcm.discovery` | Selected discovery URL |
| `sys.boot_completed` | Must be `1` |
| `ro.build.fingerprint` | Recorded in Flash/OTA details |
| `persist.sys.ota.status` (if used) | Soft OTA health signal |

Example (US device):

```bash
adb -s YOUR_US_ADB_SERIAL shell getprop persist.poynt.srvc.url.pcm
adb -s YOUR_US_ADB_SERIAL shell getprop persist.poynt.pcm.discovery
adb -s YOUR_US_ADB_SERIAL shell getprop sys.boot_completed
adb -s YOUR_US_ADB_SERIAL shell pm path co.poynt.services.pcm
```

---

## 2) Create `local.properties` (recommended)

```bash
cd ~/Projects/phmp-pcm-health-monitor
cp local.properties.example local.properties
```

Fill:

```properties
phmp.mode=device
phmp.env=lab
phmp.region=both
phmp.us.serial=YOUR_US_ADB_SERIAL
phmp.eu.serial=YOUR_EU_ADB_SERIAL
phmp.cloud.token=YOUR_LAB_CLOUD_BEARER_TOKEN
```

Or export instead of file:

```bash
export PHMP_US_SERIAL="YOUR_US_ADB_SERIAL"
export PHMP_EU_SERIAL="YOUR_EU_ADB_SERIAL"
export PHMP_CLOUD_TOKEN="YOUR_LAB_CLOUD_BEARER_TOKEN"
export BUILD_ID="lab-nightly-YYYYMMDD"
```

---

## 3) Pre-run checklist (tick before execute)

- [ ] USB debugging authorized on both terminals (`adb devices` shows `device`)
- [ ] Nightly image flashed / OTA applied on both devices
- [ ] PCM package installed and privileged as expected
- [ ] US device has US endpoint + discovery props
- [ ] EU device has EU endpoint + discovery props
- [ ] PCM authenticated and WebSocket can come up on each device
- [ ] Host can reach US/EU cloud inject URLs (VPN/lab network if required)
- [ ] Real cloud token set (not `replace-me`)
- [ ] YAML endpoints updated to real lab hosts
- [ ] Optional: device handles reconnect broadcast `co.poynt.pcm.ACTION_FORCE_DISCONNECT`

---

## 3b) Qualify the terminal (mandatory before the first run)

The log-analysis gates key on specific PCM log strings. If this build emits different strings, those
gates fail for reasons unrelated to PCM health, so qualify before running the suite:

```bash
./scripts/qualify-device.sh ST3SL512NY000341

# Also prove the force-disconnect broadcast is handled (drops the socket, ~45s)
./scripts/qualify-device.sh ST3SL512NY000341 --exercise-disconnect
```

It writes `reports/qualification/` with a full logcat capture, the device properties to copy verbatim
into `application.yaml`, and a per-gate marker table. Proceed only on **READY**.

## 3c) Recover the terminal's access token

PCM logs its WebSocket URL and the access token rides on that URL as a `?token=` parameter, so the
token in force can be read back from logcat rather than requested from another team:

```bash
./scripts/fetch-device-token.sh ST3SL512NY000341 --env dev
```

It writes `phmp.cloud.token` into `local.properties` (gitignored, mode 600) and prints only a
fingerprint plus the JWT claims. Run it immediately before a suite run, since tokens are short lived.

The claims are worth reading. A token that parses cleanly and is unexpired is still refused with
`FAILED HANDSHAKE status 401` when its `iss` belongs to a different environment than the one the
terminal is pointed at — the signature of an environment switch that left the cached token behind. The
script warns on this, and the Authentication gate fails with the same diagnosis.

## 3d) Watchable run (recommended)

PHMP observes rather than drives, so the terminal's screen stays idle during a run. To watch the run
instead of waiting on it:

```bash
./scripts/run-live.sh US
```

It preflights ADB and boot state, recovers the token, then streams the terminal's own PCM log lines
into your console as `[device]` alongside `[phmp]` gate verdicts, with every adb command traced
(`-Dphmp.trace=true`). The full device capture is kept under `logs/live-<serial>-<timestamp>.log` with
tokens redacted, and the HTML report opens at the end.

It stops early when the terminal cannot authenticate in the target environment, because every socket
gate would then fail for that single upstream reason. Add `--force` to run anyway and capture evidence.

## 4) Execute (with ADB preflight)

```bash
cd ~/Projects/phmp-pcm-health-monitor
chmod +x scripts/*.sh

# Validates adb + serials + token, then runs Maven device mode
./scripts/run-device.sh both

# Single region
./scripts/run-device.sh US
./scripts/run-device.sh EU

# Skip token presence check (not recommended for full E2E)
./scripts/run-device.sh both --skip-token-check
```

What `run-device.sh` checks before Maven:

1. `adb` installed  
2. At least one device in `device` state  
3. Required region serial(s) present and online  
4. Cloud token set and not a known placeholder  
5. Soft warning if PCM package path is missing  

Then it calls `./scripts/run-local.sh device <region>`.

---

## 5) After-run verification

- [ ] `reports/phmp-execution-report-us.html` → Result PASS, Release Ready YES  
- [ ] `reports/phmp-execution-report-eu.html` → Result PASS, Release Ready YES  
- [ ] `reports/history/summary.jsonl` appended  
- [ ] `logs/logcat-US-*.txt` / `logs/logcat-EU-*.txt` captured  
- [ ] Optional: `./scripts/allure-report.sh`

---

## 6) Quick troubleshooting map

| Preflight / gate failure | Action |
|--------------------------|--------|
| No ADB devices | Cable/auth; `adb kill-server && adb start-server` |
| Serial not online | Fix `PHMP_*_SERIAL`; confirm with `adb devices` |
| Token placeholder | Set real `PHMP_CLOUD_TOKEN` |
| Package WARN / Install FAIL | Install PCM or fix `packageName` |
| Environment FAIL | Align device props with YAML region endpoints |
| Auth / WebSocket FAIL | Ensure PCM logged in and socket logs appear |
| Cloud FAIL | Token, baseUrl/injectPath, network, device receipt logs |
| Reconnect FAIL | Confirm force-disconnect broadcast + recovery logs |

---

## 7) Example filled sheet (sample only)

```text
US serial:     P6U123456789
EU serial:     P6U987654321
Token:         <redacted>
US WS:         wss://pcm-us.lab.example.com/v1/socket
US discovery:  https://discovery-us.lab.example.com
US API:        https://api-us.lab.example.com
EU WS:         wss://pcm-eu.lab.example.com/v1/socket
EU discovery:  https://discovery-eu.lab.example.com
EU API:        https://api-eu.lab.example.com
Package:       co.poynt.services.pcm
```

Copy the table in section 1 into your notes and replace placeholders once; reuse the same `local.properties` for nightly lab runs.
