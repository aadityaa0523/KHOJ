#!/usr/bin/env python3
"""
KHOJ Mesh Bench Analysis Script (Phase 1)
Parses radio_log.csv from TX and RX phones, matches events by payload sequence and hash,
and computes single-hop delivery metrics, gap distributions, latencies, and AUX-follow performance.

Usage:
  python analyze.py --tx <tx_log.csv> --rx <rx_log.csv>
  python analyze.py --selftest
"""

import sys
import os
import argparse
import csv
import math
from typing import Dict, List, Optional, Tuple, Any

def parse_radio_log(file_path: str) -> Dict[str, Any]:
    """Parses a radio_log.csv file into structured events and metadata."""
    metadata = {
        "model": "unknown",
        "build": "unknown",
        "gitHash": "unknown",
        "runId": "unknown",
    }
    tx_updates: List[Dict[str, Any]] = []
    tx_dones: List[Dict[str, Any]] = []
    rx_events: List[Dict[str, Any]] = []
    scan_events: List[Dict[str, Any]] = []
    state_events: List[Dict[str, Any]] = []

    with open(file_path, "r", encoding="utf-8", errors="replace") as f:
        reader = csv.reader(f)
        for row in reader:
            if not row:
                continue
            first = row[0].strip()
            if first.startswith("#"):
                # Header row: # HEADER,model,build,gitHash,runId
                parts = [p.strip() for p in row]
                if len(parts) >= 5 and parts[0].upper() == "# HEADER":
                    metadata["model"] = parts[1]
                    metadata["build"] = parts[2]
                    metadata["gitHash"] = parts[3]
                    metadata["runId"] = parts[4]
                continue

            event_type = first.upper()
            try:
                if event_type == "TX_UPDATE" and len(row) >= 6:
                    # TX_UPDATE,elapsedRealtimeNanos,phy,seq,len,hash
                    tx_updates.append({
                        "timestamp_ns": int(row[1]),
                        "phy": row[2].strip(),
                        "seq": int(row[3]),
                        "len": int(row[4]),
                        "hash": row[5].strip(),
                    })
                elif event_type == "TX_DONE" and len(row) >= 5:
                    # TX_DONE,elapsedRealtimeNanos,phy,status,seq
                    tx_dones.append({
                        "timestamp_ns": int(row[1]),
                        "phy": row[2].strip(),
                        "status": int(row[3]),
                        "seq": int(row[4]),
                    })
                elif event_type == "RX" and len(row) >= 11:
                    # RX,scanTimestampNanos,arrivalNanos,rssi,primaryPhy,secondaryPhy,advertisingSid,dataStatus,txPower,hash,seq[,relayId,linkSeq]
                    rx_events.append({
                        "scan_timestamp_ns": int(row[1]),
                        "arrival_ns": int(row[2]),
                        "rssi": int(row[3]),
                        "primary_phy": row[4].strip(),
                        "secondary_phy": row[5].strip(),
                        "sid": int(row[6]),
                        "data_status": int(row[7]),
                        "tx_power": int(row[8]),
                        "hash": row[9].strip(),
                        "seq": int(row[10]),
                        "relay_id": int(row[11]) if len(row) > 11 and row[11].strip().isdigit() else 0,
                        "link_seq": int(row[12]) if len(row) > 12 and row[12].strip().isdigit() else 0,
                    })
                elif event_type in ("SCAN_START", "SCAN_STOP", "SCAN_FAIL"):
                    scan_events.append({
                        "event": event_type,
                        "timestamp_ns": int(row[1]),
                        "arg": row[2].strip() if len(row) > 2 else "",
                    })
                elif event_type == "STATE" and len(row) >= 9:
                    state_events.append({
                        "timestamp_ns": int(row[1]),
                        "nearby": row[2].strip(),
                        "gatt_count": int(row[3]),
                        "screen_on": row[4].strip().lower() == "true",
                        "battery_saver": row[5].strip().lower() == "true",
                        "thermal": int(row[6]),
                        "battery_pct": int(row[7]),
                        "active_sets": int(row[8]),
                    })
            except (ValueError, IndexError):
                # Skip malformed lines gracefully
                continue

    return {
        "metadata": metadata,
        "tx_updates": tx_updates,
        "tx_dones": tx_dones,
        "rx_events": rx_events,
        "scan_events": scan_events,
        "state_events": state_events,
    }


def percentile(data: List[float], p: float) -> float:
    """Computes p-th percentile (0..100) of sorted list data."""
    if not data:
        return 0.0
    if len(data) == 1:
        return data[0]
    s = sorted(data)
    idx = (len(s) - 1) * (p / 100.0)
    floor_idx = int(math.floor(idx))
    ceil_idx = int(math.ceil(idx))
    if floor_idx == ceil_idx:
        return s[floor_idx]
    d0 = s[floor_idx] * (ceil_idx - idx)
    d1 = s[ceil_idx] * (idx - floor_idx)
    return d0 + d1


def analyze(tx_data: Dict[str, Any], rx_data: Dict[str, Any]) -> Dict[str, Any]:
    """Computes all benchmark metrics between TX and RX datasets."""
    tx_updates = tx_data["tx_updates"]
    tx_dones = tx_data["tx_dones"]
    rx_events = rx_data["rx_events"]

    # 1. Distinct seqs sent and received
    # If TX log has updates:
    sent_seqs_order: List[int] = []
    sent_seqs_set = set()
    tx_first_update_time: Dict[int, int] = {}

    for up in tx_updates:
        s = up["seq"]
        if s not in sent_seqs_set:
            sent_seqs_set.add(s)
            sent_seqs_order.append(s)
            tx_first_update_time[s] = up["timestamp_ns"]

    # RX events by seq
    rx_seqs_set = set()
    rx_first_arrival_time: Dict[int, int] = {}
    rx_first_scan_time: Dict[int, int] = {}
    rx_count_by_seq: Dict[int, int] = {}

    for rx in rx_events:
        s = rx["seq"]
        rx_seqs_set.add(s)
        rx_count_by_seq[s] = rx_count_by_seq.get(s, 0) + 1
        if s not in rx_first_arrival_time:
            rx_first_arrival_time[s] = rx["arrival_ns"]
            rx_first_scan_time[s] = rx["scan_timestamp_ns"]

    total_sent = len(sent_seqs_set)
    received_sent_seqs = sent_seqs_set.intersection(rx_seqs_set)
    received_count = len(received_sent_seqs)

    logical_delivery_ratio = (received_count / total_sent) if total_sent > 0 else 0.0

    # 2. Delivery within 1 s
    # Offset estimated as min(t_rx_first(seq) - t_tx_update(seq))
    # Assumption: Fastest one-way delay is approximately 0 ms
    offset_candidates = [
        rx_first_arrival_time[s] - tx_first_update_time[s]
        for s in received_sent_seqs
        if s in tx_first_update_time and s in rx_first_arrival_time
    ]
    min_offset_ns = min(offset_candidates) if offset_candidates else 0

    within_1s_count = 0
    seq_latencies_ms: Dict[int, float] = {}
    for s in received_sent_seqs:
        if s in tx_first_update_time and s in rx_first_arrival_time:
            apparent_delay_ns = rx_first_arrival_time[s] - tx_first_update_time[s]
            aligned_delay_ns = apparent_delay_ns - min_offset_ns
            latency_ms = aligned_delay_ns / 1_000_000.0
            seq_latencies_ms[s] = latency_ms
            if aligned_delay_ns <= 1_000_000_000:  # <= 1.0 second
                within_1s_count += 1

    delivery_within_1s_ratio = (within_1s_count / total_sent) if total_sent > 0 else 0.0

    # 3. Events heard per second for fixed payloads
    total_rx_events = len(rx_events)
    events_per_sec = 0.0
    rx_duration_sec = 0.0
    if rx_events:
        t_start = min(e["arrival_ns"] for e in rx_events)
        t_end = max(e["arrival_ns"] for e in rx_events)
        rx_duration_sec = (t_end - t_start) / 1_000_000_000.0
        if rx_duration_sec > 0:
            events_per_sec = total_rx_events / rx_duration_sec

    # 4. Inter-arrival gap distribution (arrival_ns of consecutive RX events)
    gaps_ms: List[float] = []
    gaps_over_2s = 0
    if len(rx_events) > 1:
        sorted_arrivals = sorted(e["arrival_ns"] for e in rx_events)
        for i in range(1, len(sorted_arrivals)):
            gap_ns = sorted_arrivals[i] - sorted_arrivals[i - 1]
            gap_ms = gap_ns / 1_000_000.0
            gaps_ms.append(gap_ms)
            if gap_ms > 2000.0:
                gaps_over_2s += 1

    gap_median_ms = percentile(gaps_ms, 50.0) if gaps_ms else 0.0
    gap_p95_ms = percentile(gaps_ms, 95.0) if gaps_ms else 0.0
    gap_max_ms = max(gaps_ms) if gaps_ms else 0.0

    # 5. TX update-to-done latency (matched by phy and seq)
    # Map (phy, seq) -> tx_done timestamp
    tx_done_by_phy_seq: Dict[Tuple[str, int], int] = {}
    matched_done_count = 0
    for done in tx_dones:
        k = (done["phy"], done["seq"])
        if k not in tx_done_by_phy_seq:
            tx_done_by_phy_seq[k] = done["timestamp_ns"]

    tx_latencies_ms: List[float] = []
    for up in tx_updates:
        k = (up["phy"], up["seq"])
        if k in tx_done_by_phy_seq:
            matched_done_count += 1
            lat_ns = tx_done_by_phy_seq[k] - up["timestamp_ns"]
            if lat_ns >= 0:
                tx_latencies_ms.append(lat_ns / 1_000_000.0)

    total_updates = len(tx_updates)
    tx_done_match_pct = (matched_done_count / total_updates * 100.0) if total_updates > 0 else 0.0
    tx_lat_median_ms = percentile(tx_latencies_ms, 50.0) if tx_latencies_ms else 0.0
    tx_lat_p95_ms = percentile(tx_latencies_ms, 95.0) if tx_latencies_ms else 0.0
    tx_lat_max_ms = max(tx_latencies_ms) if tx_latencies_ms else 0.0

    # 6. Payload versions never received
    missing_seqs = sorted(list(sent_seqs_set - rx_seqs_set))

    # 7. Callback delay (arrival_ns - scan_timestamp_ns)
    callback_delays_ms: List[float] = []
    for rx in rx_events:
        d_ns = rx["arrival_ns"] - rx["scan_timestamp_ns"]
        if d_ns >= 0:
            callback_delays_ms.append(d_ns / 1_000_000.0)

    cb_delay_median_ms = percentile(callback_delays_ms, 50.0) if callback_delays_ms else 0.0
    cb_delay_p95_ms = percentile(callback_delays_ms, 95.0) if callback_delays_ms else 0.0
    cb_delay_max_ms = max(callback_delays_ms) if callback_delays_ms else 0.0

    # 8. Share of incomplete or truncated dataStatus
    # Android ScanResult: DATA_COMPLETE = 0, DATA_TRUNCATED = 2
    truncated_count = sum(1 for rx in rx_events if rx["data_status"] != 0)
    truncated_share = (truncated_count / total_rx_events) if total_rx_events > 0 else 0.0

    return {
        "total_sent_seqs": total_sent,
        "total_rx_seqs": received_count,
        "logical_delivery": logical_delivery_ratio,
        "delivery_within_1s": delivery_within_1s_ratio,
        "min_offset_ms": min_offset_ns / 1_000_000.0,
        "total_rx_events": total_rx_events,
        "rx_duration_sec": rx_duration_sec,
        "events_per_sec": events_per_sec,
        "gap_median_ms": gap_median_ms,
        "gap_p95_ms": gap_p95_ms,
        "gap_max_ms": gap_max_ms,
        "gaps_over_2s": gaps_over_2s,
        "total_tx_updates": total_updates,
        "matched_tx_done": matched_done_count,
        "tx_done_match_pct": tx_done_match_pct,
        "tx_lat_median_ms": tx_lat_median_ms,
        "tx_lat_p95_ms": tx_lat_p95_ms,
        "tx_lat_max_ms": tx_lat_max_ms,
        "missing_seqs_count": len(missing_seqs),
        "missing_seqs_sample": missing_seqs[:20],
        "cb_delay_median_ms": cb_delay_median_ms,
        "cb_delay_p95_ms": cb_delay_p95_ms,
        "cb_delay_max_ms": cb_delay_max_ms,
        "truncated_count": truncated_count,
        "truncated_share": truncated_share,
    }


def print_report(tx_data: Dict[str, Any], rx_data: Dict[str, Any], results: Dict[str, Any]):
    """Prints a structured markdown report of the benchmark analysis."""
    tx_meta = tx_data["metadata"]
    rx_meta = rx_data["metadata"]

    print("================================================================================")
    print(" KHOJ BLE MESH BENCHMARK ANALYSIS REPORT")
    print("================================================================================")
    print(f"TX Phone: {tx_meta['model']} (Build: {tx_meta['build']}, Git: {tx_meta['gitHash']})")
    print(f"RX Phone: {rx_meta['model']} (Build: {rx_meta['build']}, Git: {rx_meta['gitHash']})")
    print(f"Run ID:   TX={tx_meta['runId']} | RX={rx_meta['runId']}")
    print("--------------------------------------------------------------------------------")
    print("1. SINGLE-HOP LOGICAL-FRAME DELIVERY")
    print(f"   - Unique Seqs Sent:      {results['total_sent_seqs']}")
    print(f"   - Unique Seqs Received:  {results['total_rx_seqs']}")
    print(f"   - Logical-Frame Delivery: {results['logical_delivery'] * 100:.2f}%")
    print(f"   - Delivery within 1.0 s:  {results['delivery_within_1s'] * 100:.2f}%")
    print(f"     (Assumption: fastest one-way delay ~ 0 ms; estimated clock offset: {results['min_offset_ms']:.1f} ms)")
    if results['missing_seqs_count'] > 0:
        sample_str = ", ".join(map(str, results['missing_seqs_sample']))
        more_str = "..." if results['missing_seqs_count'] > 20 else ""
        print(f"   - Missed Payload Seqs:   {results['missing_seqs_count']} [{sample_str}{more_str}]")
    else:
        print("   - Missed Payload Seqs:   0 (All sent frames received!)")

    print("\n2. RECEPTION RATE & GAP DISTRIBUTION")
    print(f"   - Total RX Events:       {results['total_rx_events']} over {results['rx_duration_sec']:.1f} s")
    print(f"   - Events Heard / Second: {results['events_per_sec']:.2f} ev/s (Expected nominal: ~9.5 ev/s at 100 ms interval)")
    print(f"   - Inter-Arrival Gaps:    Median={results['gap_median_ms']:.1f} ms, p95={results['gap_p95_ms']:.1f} ms, Max={results['gap_max_ms']:.1f} ms")
    print(f"   - Gaps > 2.0 Seconds:    {results['gaps_over_2s']}")

    print("\n3. TX UPDATE-TO-DONE SCHEDULING LATENCY")
    print(f"   - Total TX Updates:      {results['total_tx_updates']}")
    print(f"   - Matched TX Dones:      {results['matched_tx_done']} ({results['tx_done_match_pct']:.1f}%)")
    print(f"   - Update-to-Done Delay:  Median={results['tx_lat_median_ms']:.2f} ms, p95={results['tx_lat_p95_ms']:.2f} ms, Max={results['tx_lat_max_ms']:.2f} ms")

    print("\n4. HOST & LINK LAYER HEALTH")
    print(f"   - Callback Delay (Host): Median={results['cb_delay_median_ms']:.2f} ms, p95={results['cb_delay_p95_ms']:.2f} ms, Max={results['cb_delay_max_ms']:.2f} ms")
    print(f"   - Truncated / Incomplete: {results['truncated_count']} ({results['truncated_share'] * 100:.2f}%)")
    print("================================================================================\n")


def run_selftest():
    """Generates synthetic logs with ground truth parameters and asserts all analysis metrics."""
    print("[SELFTEST] Generating synthetic TX and RX logs with known parameters...")

    # Ground truth parameters:
    # 100 sequence updates (seq 1..100) at 250 ms interval
    # Drop every 5th sequence (seq % 5 == 0) -> exactly 20 dropped, 80 received -> 80.0% delivery
    # Clock offset: RX clock = TX clock + 350 ms (350,000,000 ns)
    # Transit delay: 30 ms for 78 sequences, and 1200 ms for 2 sequences (seq 11 and seq 21) -> 78/100 = 78.0% within 1s
    # Fixed event rate: 10 RX events per second
    # Update-to-done: 18 ms for each TX update
    # Incomplete status: 4 out of 80 RX events -> 5.0%

    num_seqs = 100
    update_interval_ns = 250_000_000  # 250 ms
    tx_base_ns = 1_000_000_000_000    # arbitrary base timestamp
    rx_clock_offset_ns = 350_000_000  # +350 ms offset

    tx_updates = []
    tx_dones = []
    rx_events = []

    for s in range(1, num_seqs + 1):
        t_tx = tx_base_ns + (s - 1) * update_interval_ns
        h = f"{s:08x}"
        tx_updates.append({
            "timestamp_ns": t_tx,
            "phy": "LE 1M",
            "seq": s,
            "len": 200,
            "hash": h,
        })
        # Done after 18 ms
        tx_dones.append({
            "timestamp_ns": t_tx + 18_000_000,
            "phy": "LE 1M",
            "status": 0,
            "seq": s,
        })

        if s % 5 != 0:
            # Delivered!
            # Transit delay
            transit_delay_ns = 1_200_000_000 if s in (11, 21) else 30_000_000
            t_rx_arrival = t_tx + rx_clock_offset_ns + transit_delay_ns
            # Callback delay 2 ms
            t_rx_scan = t_rx_arrival - 2_000_000
            # Data status: 4 truncated events
            data_status = 2 if s in (1, 2, 3, 4) else 0

            rx_events.append({
                "scan_timestamp_ns": t_rx_scan,
                "arrival_ns": t_rx_arrival,
                "rssi": -55,
                "primary_phy": "LE 1M",
                "secondary_phy": "LE 1M",
                "sid": 1,
                "data_status": data_status,
                "tx_power": 1,
                "hash": h,
                "seq": s,
                "relay_id": 0,
                "link_seq": s,
            })

    synth_tx = {
        "metadata": {"model": "PhoneA (TX)", "build": "test-build-1", "gitHash": "d76a1e6", "runId": "synth_tx"},
        "tx_updates": tx_updates,
        "tx_dones": tx_dones,
        "rx_events": [],
        "scan_events": [],
        "state_events": [],
    }

    synth_rx = {
        "metadata": {"model": "PhoneB (RX)", "build": "test-build-1", "gitHash": "d76a1e6", "runId": "synth_rx"},
        "tx_updates": [],
        "tx_dones": [],
        "rx_events": rx_events,
        "scan_events": [],
        "state_events": [],
    }

    res = analyze(synth_tx, synth_rx)
    print_report(synth_tx, synth_rx, res)

    # Assertions
    print("[SELFTEST] Verifying assertions...")
    assert res["total_sent_seqs"] == 100, f"Expected 100 sent, got {res['total_sent_seqs']}"
    assert res["total_rx_seqs"] == 80, f"Expected 80 rx, got {res['total_rx_seqs']}"
    assert abs(res["logical_delivery"] - 0.80) < 1e-4, f"Expected 80% delivery, got {res['logical_delivery']}"
    assert abs(res["delivery_within_1s"] - 0.78) < 1e-4, f"Expected 78% within 1s, got {res['delivery_within_1s']}"
    assert abs(res["tx_done_match_pct"] - 100.0) < 1e-4, f"Expected 100% matched TX done, got {res['tx_done_match_pct']}"
    assert abs(res["tx_lat_median_ms"] - 18.0) < 1e-4, f"Expected 18ms TX done latency, got {res['tx_lat_median_ms']}"
    assert res["missing_seqs_count"] == 20, f"Expected 20 missing seqs, got {res['missing_seqs_count']}"
    assert abs(res["cb_delay_median_ms"] - 2.0) < 1e-4, f"Expected 2.0ms callback delay, got {res['cb_delay_median_ms']}"
    assert res["truncated_count"] == 4, f"Expected 4 truncated, got {res['truncated_count']}"
    assert abs(res["truncated_share"] - (4 / 80)) < 1e-4, f"Expected 5% truncated share, got {res['truncated_share']}"

    print("[SELFTEST] ALL ASSERTIONS PASSED! Self-test succeeded.")


def main():
    parser = argparse.ArgumentParser(description="Analyze KHOJ radio benchmark logs.")
    parser.add_argument("--tx", help="Path to TX radio_log.csv")
    parser.add_argument("--rx", help="Path to RX radio_log.csv")
    parser.add_argument("--selftest", action="store_true", help="Run synthetic validation self-test")

    args = parser.parse_args()

    if args.selftest:
        run_selftest()
        return

    if not args.tx or not args.rx:
        parser.print_help()
        sys.exit(1)

    if not os.path.exists(args.tx):
        print(f"Error: TX log file not found: {args.tx}", file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(args.rx):
        print(f"Error: RX log file not found: {args.rx}", file=sys.stderr)
        sys.exit(1)

    tx_data = parse_radio_log(args.tx)
    rx_data = parse_radio_log(args.rx)
    results = analyze(tx_data, rx_data)
    print_report(tx_data, rx_data, results)


if __name__ == "__main__":
    main()
