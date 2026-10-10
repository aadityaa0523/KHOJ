#!/usr/bin/env python3
"""
KHOJ Automated Bench Orchestrator (Phase 1 & Phase 2)
Discovers connected test phones, configures and launches BenchActivity runs via ADB,
pulls radio_log.csv telemetry files, and invokes analyze.py to compute metrics.

Usage:
  python tools/bench/run_bench.py --list-devices
  python tools/bench/run_bench.py --install
  python tools/bench/run_bench.py --run R1
  python tools/bench/run_bench.py --run R5
"""

import sys
import os
import time
import subprocess
import argparse
from typing import List, Dict, Optional, Tuple, Any

DEFAULT_ADB_PATHS = [
    "adb",
    r"C:\Users\Aadityaa\AppData\Local\Android\Sdk\platform-tools\adb.exe",
]

PHONE_A_SERIAL = "10BFAX1BU80010U"

# Run configurations from Phase 2 table in MESH_IMPROVEMENT_PLAN.md
RUN_SPECS: Dict[str, Dict[str, Any]] = {
    "R1": {
        "desc": "TX only (A) -> RX only (B), 1M, fixed payload (clean baseline)",
        "A": {"role": "tx", "sets": "1m", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "rx", "sets": "1m", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R2": {
        "desc": "RX only (A) <- TX only (B), 1M, fixed payload (handset asymmetry)",
        "A": {"role": "rx", "sets": "1m", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "tx", "sets": "1m", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R3": {
        "desc": "Both (A) <-> Both (B), 1M each, fixed payload (own-ad blinding)",
        "A": {"role": "both", "sets": "1m", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "both", "sets": "1m", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R4": {
        "desc": "Both (A) <-> Both (B), 1M + Coded, fixed payload (second set / Coded airtime)",
        "A": {"role": "both", "sets": "1m_coded", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "both", "sets": "1m_coded", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R5": {
        "desc": "TX only (A) -> RX only (B), 1M, updateMs=2000",
        "A": {"role": "tx", "sets": "1m", "updateMs": 2000, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "rx", "sets": "1m", "updateMs": 2000, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R6": {
        "desc": "TX only (A) -> RX only (B), 1M, updateMs=1000",
        "A": {"role": "tx", "sets": "1m", "updateMs": 1000, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "rx", "sets": "1m", "updateMs": 1000, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R7": {
        "desc": "TX only (A) -> RX only (B), 1M, updateMs=500",
        "A": {"role": "tx", "sets": "1m", "updateMs": 500, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "rx", "sets": "1m", "updateMs": 500, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R8": {
        "desc": "TX only (A) -> RX only (B), 1M, updateMs=250 (current app cadence)",
        "A": {"role": "tx", "sets": "1m", "updateMs": 250, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "rx", "sets": "1m", "updateMs": 250, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R9": {
        "desc": "TX only (A) -> RX only (B), 1M, updateMs=100 (stress cadence)",
        "A": {"role": "tx", "sets": "1m", "updateMs": 100, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "rx", "sets": "1m", "updateMs": 100, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R10": {
        "desc": "Both (A) <-> Both (B), 1M + Coded, updateMs=250 (today's configuration)",
        "A": {"role": "both", "sets": "1m_coded", "updateMs": 250, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "both", "sets": "1m_coded", "updateMs": 250, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R11_EXT31": {
        "desc": "TX only (A) -> RX only (B), 1M, fixed 31-byte extended payload",
        "A": {"role": "tx", "sets": "1m", "updateMs": 0, "bytes": 31, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "rx", "sets": "1m", "updateMs": 0, "bytes": 31, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R11_LEGACY": {
        "desc": "TX only (A) -> RX only (B), 1M, fixed 31-byte legacy advertisement",
        "A": {"role": "tx", "sets": "1m", "updateMs": 0, "bytes": 31, "legacy": True, "nearby": "off", "gatt": "off"},
        "B": {"role": "rx", "sets": "1m", "updateMs": 0, "bytes": 31, "legacy": True, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
    "R12": {
        "desc": "Both (A) <-> Both (B), 1M fixed, Nearby discovering on B (inquiry contention)",
        "A": {"role": "both", "sets": "1m", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "both", "sets": "1m", "updateMs": 0, "bytes": 200, "legacy": False, "nearby": "discover", "gatt": "off"},
        "seconds": 240,
    },
    "P3_BIDIR": {
        "desc": "Phase 3 Radio Fix: Both (A) <-> Both (B), 1M only, updateMs=1000 (1s frame dwell)",
        "A": {"role": "both", "sets": "1m", "updateMs": 1000, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "B": {"role": "both", "sets": "1m", "updateMs": 1000, "bytes": 200, "legacy": False, "nearby": "off", "gatt": "off"},
        "seconds": 240,
    },
}


def find_adb() -> str:
    for path in DEFAULT_ADB_PATHS:
        try:
            res = subprocess.run([path, "version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            if res.returncode == 0:
                return path
        except FileNotFoundError:
            continue
    print("Error: adb not found in PATH or standard Android SDK directories.", file=sys.stderr)
    sys.exit(1)


def get_connected_devices(adb: str) -> List[Dict[str, str]]:
    res = subprocess.run([adb, "devices", "-l"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    lines = res.stdout.strip().splitlines()
    devices = []
    for line in lines[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        serial = parts[0]
        status = parts[1]
        model = "unknown"
        for p in parts[2:]:
            if p.startswith("model:"):
                model = p.split(":", 1)[1]
        devices.append({"serial": serial, "status": status, "model": model, "raw": line})
    return devices


def install_apk(adb: str, apk_path: str, devices: List[Dict[str, str]]):
    if not os.path.exists(apk_path):
        print(f"Error: APK not found at {apk_path}. Run ./gradlew :app:assembleDebug first.", file=sys.stderr)
        return False

    print(f"Installing APK ({apk_path}) on {len(devices)} device(s)...")
    for d in devices:
        serial = d["serial"]
        print(f"  -> Installing on {serial} ({d['model']})...")
        cmd = [adb, "-s", serial, "install", "-r", apk_path]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode == 0 and "Success" in res.stdout:
            print(f"     [OK] {serial} installed successfully.")
        else:
            print(f"     [ERROR] Failed to install on {serial}: {res.stderr or res.stdout}")
    return True


def launch_bench_activity(adb: str, serial: str, role_spec: Dict[str, Any], seconds: int, run_id: str):
    # adb shell am start -n app.linkfill/.BenchActivity --es role ... --es sets ... --ei updateMs ... --ei seconds ...
    cmd = [
        adb, "-s", serial, "shell", "am", "start", "-n", "app.linkfill/.BenchActivity",
        "--es", "role", str(role_spec["role"]),
        "--es", "sets", str(role_spec["sets"]),
        "--ei", "updateMs", str(role_spec["updateMs"]),
        "--ei", "bytes", str(role_spec["bytes"]),
        "--ei", "seconds", str(seconds),
        "--ez", "legacy", str(role_spec["legacy"]).lower(),
        "--es", "nearby", str(role_spec["nearby"]),
        "--es", "gatt", str(role_spec["gatt"]),
        "--es", "runId", run_id,
    ]
    subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def pull_log(adb: str, serial: str, dest_path: str) -> bool:
    env = os.environ.copy()
    env["MSYS_NO_PATHCONV"] = "1"
    remote_path = "/sdcard/Android/data/app.linkfill/files/radio_log.csv"
    cmd = [adb, "-s", serial, "pull", remote_path, dest_path]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
    return res.returncode == 0 and os.path.exists(dest_path)


def run_experiment(adb: str, run_key: str, devices: List[Dict[str, str]]):
    spec = RUN_SPECS.get(run_key)
    if not spec:
        print(f"Error: Unknown run key '{run_key}'. Available: {list(RUN_SPECS.keys())}")
        return

    if len(devices) < 2:
        print(f"Error: Run {run_key} requires at least 2 connected devices, found {len(devices)}.")
        return

    # Assign Phone A and Phone B
    dev_a = next((d for d in devices if d["serial"] == PHONE_A_SERIAL), devices[0])
    dev_b = next((d for d in devices if d["serial"] != dev_a["serial"]), devices[1])

    seconds = spec["seconds"]
    print(f"\n================================================================================")
    print(f" STARTING EXPERIMENT {run_key}: {spec['desc']}")
    print(f" Phone A (TX-config): {dev_a['serial']} ({dev_a['model']})")
    print(f" Phone B (RX-config): {dev_b['serial']} ({dev_b['model']})")
    print(f" Duration:            {seconds} seconds (~{seconds/60:.1f} minutes)")
    print(f"================================================================================\n")

    # Force-stop app and clear previous radio_log.csv on both phones
    env = os.environ.copy()
    env["MSYS_NO_PATHCONV"] = "1"
    remote_path = "/sdcard/Android/data/app.linkfill/files/radio_log.csv"
    for d in (dev_a, dev_b):
        subprocess.run([adb, "-s", d["serial"], "shell", "am", "force-stop", "app.linkfill"])
        subprocess.run([adb, "-s", d["serial"], "shell", f"rm -f {remote_path}"], env=env)

    # Launch BenchActivity on both phones
    run_id_a = f"{run_key}_A_{dev_a['serial']}"
    run_id_b = f"{run_key}_B_{dev_b['serial']}"
    print("Launching BenchActivity on Phone B (receiver)...")
    launch_bench_activity(adb, dev_b["serial"], spec["B"], seconds, run_id_b)
    time.sleep(1.0)
    print("Launching BenchActivity on Phone A (transmitter)...")
    launch_bench_activity(adb, dev_a["serial"], spec["A"], seconds, run_id_a)

    print(f"Benchmark running for {seconds} seconds. Please wait...")
    for elapsed in range(0, seconds + 5, 10):
        remaining = max(0, seconds - elapsed)
        sys.stdout.write(f"\r  Elapsed: {elapsed}s / {seconds}s ({remaining}s remaining)... ")
        sys.stdout.flush()
        time.sleep(10 if elapsed + 10 <= seconds + 5 else (seconds + 5 - elapsed))
    print("\nBenchmark completed. Pulling logs...")

    os.makedirs("tools/bench/logs", exist_ok=True)
    log_a_path = f"tools/bench/logs/{run_key}_{dev_a['serial']}.csv"
    log_b_path = f"tools/bench/logs/{run_key}_{dev_b['serial']}.csv"

    ok_a = pull_log(adb, dev_a["serial"], log_a_path)
    ok_b = pull_log(adb, dev_b["serial"], log_b_path)

    if not ok_a or not ok_b:
        print(f"Error pulling logs: Phone A pulled={ok_a}, Phone B pulled={ok_b}")
        return

    print("Logs retrieved successfully. Running analyze.py...")
    # Determine who is TX and who is RX based on spec
    is_a_tx = spec["A"]["role"] in ("tx", "both")
    tx_log = log_a_path if is_a_tx else log_b_path
    rx_log = log_b_path if is_a_tx else log_a_path

    subprocess.run([sys.executable, "tools/bench/analyze.py", "--tx", tx_log, "--rx", rx_log])


def main():
    parser = argparse.ArgumentParser(description="KHOJ BLE Mesh Bench Orchestrator")
    parser.add_argument("--list-devices", action="store_true", help="List connected ADB devices")
    parser.add_argument("--install", action="store_true", help="Install debug APK on all connected devices")
    parser.add_argument("--run", help=f"Run specific benchmark: {list(RUN_SPECS.keys())}")
    parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk", help="Path to debug APK")

    args = parser.parse_args()
    adb = find_adb()
    devices = get_connected_devices(adb)

    if args.list_devices or len(sys.argv) == 1:
        print(f"ADB: {adb}")
        print(f"Connected devices ({len(devices)}):")
        for d in devices:
            is_phone_a = " (PHONE A)" if d["serial"] == PHONE_A_SERIAL else ""
            print(f"  - {d['serial']}: {d['status']} [model: {d['model']}]{is_phone_a}")
        if not devices:
            print("  (No devices connected)")
        return

    if args.install:
        install_apk(adb, args.apk, devices)
        return

    if args.run:
        run_experiment(adb, args.run.upper(), devices)


if __name__ == "__main__":
    main()
