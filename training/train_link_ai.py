"""
Link AI: predicts "this neighbour's Bluetooth availability fails in the next 5 s" (no frames received in 5 s)
from the features logged on real phones (link_log.csv), so the mesh can escalate to Nearby / Wi-Fi Direct or Coded PHY
before the link is completely dropped.

Usage (from repo root): python training/train_link_ai.py [training/data/link_*.csv]

Writes:
  app/src/main/assets/link_ai_v1.tflite          batch of 16 x 19 features -> 16 probabilities (Hexagon HTP NPU via QNN, LiteRT CPU fallback)
  app/src/main/assets/link_ai_v1.json            feature order, thresholds, grouped held-out metrics, data provenance
  core/src/main/resources/app/linkfill/core/mesh/loss_predictor_v2.txt   same model in pure-Kotlin LossPredictor format

Evaluation protocol (Phase 7):
  1. Outer: Leave-device-out (Train Phone A -> Test Phone B, Train Phone B -> Test Phone A)
  2. Middle: Grouped by session (phone:neighbor) with GroupKFold
  3. Calibration on out-of-fold predictions
  4. Comparison against baseline rule: risk = 1 - pdr(2 s)
"""
import json, sys, glob, datetime
import numpy as np, pandas as pd
from sklearn.model_selection import GroupKFold
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score, average_precision_score, precision_recall_curve, brier_score_loss, log_loss

ALL = [
    "distance_m", "rssi_median_2s", "rssi_std_10s", "rssi_slope_dbps", "rssi_drop",
    "pdr_0_5s", "pdr_2s", "pdr_10s", "consecutive_misses", "since_last_s", "coded_phy", "queue_depth",
    "adv_update_age_s", "active_adv_sets", "nearby_state", "gatt_link_count",
    "screen_on", "battery_saver", "thermal_status", "local_density", "peer_beacon_period_s"
]

F = [
    "rssi_median_2s", "rssi_std_10s", "rssi_slope_dbps", "rssi_drop",
    "pdr_0_5s", "pdr_2s", "pdr_10s", "consecutive_misses", "since_last_s", "queue_depth",
    "adv_update_age_s", "active_adv_sets", "nearby_state", "gatt_link_count",
    "screen_on", "battery_saver", "thermal_status", "local_density", "peer_beacon_period_s"
]
BATCH = 16

files = sys.argv[1:] or glob.glob("training/data/link_*.csv")
if not files:
    print("No training data found in training/data/link_*.csv")
    sys.exit(1)

parts = []
for f in files:
    d = pd.read_csv(f)
    phone_id = f.replace("\\", "/").split("link_")[-1].split(".")[0]
    d["phone"] = phone_id
    parts.append(d)
df = pd.concat(parts, ignore_index=True)

# Impute defaults for newly added columns if reading older logs
defaults = {
    "adv_update_age_s": 0.0,
    "active_adv_sets": 1.0,
    "nearby_state": 0.0,
    "gatt_link_count": 0.0,
    "screen_on": 1.0,
    "battery_saver": 0.0,
    "thermal_status": 0.0,
    "local_density": 1.0,
    "peer_beacon_period_s": 3.0,
}
for col, val in defaults.items():
    if col not in df.columns:
        df[col] = val

df = df[df.rssi_median_2s < 0].copy()
df["session"] = df.phone + ":" + df.neighbor.astype(str)

X = df[F].values.astype(np.float64)
y = df.lost_5s.values.astype(int)
g = df.session.values
phones = sorted(df.phone.unique().tolist())

print(f"Loaded {len(df)} rows across {len(set(g))} sessions from {len(phones)} devices: {phones}")
print(f"Base rate of 5s availability failure: {y.mean():.4f} ({y.sum()} failures)")

mean = X.mean(0)
std = X.std(0) + 1e-9
std[std < 1e-6] = 1.0
Z = (X - mean) / std

def get_clf():
    return LogisticRegression(max_iter=3000, C=1.0)

# 1. Baseline Rule: 1 - pdr(2 s)
rule = np.clip(1.0 - df.pdr_2s.values.astype(np.float64), 0.0, 1.0)
rule_pr_auc = float(average_precision_score(y, rule))
rule_roc_auc = float(roc_auc_score(y, rule))
rule_brier = float(brier_score_loss(y, rule))
print(f"\nBaseline Rule (1 - pdr_2s): PR-AUC={rule_pr_auc:.3f}, ROC-AUC={rule_roc_auc:.3f}, Brier={rule_brier:.4f}")

# 2. Middle Split: GroupKFold by session
n_splits = min(5, len(set(g)))
held = np.zeros(len(y))
for tr, te in GroupKFold(n_splits=n_splits).split(Z, y, g):
    held[te] = get_clf().fit(Z[tr], y[tr]).predict_proba(Z[te])[:, 1]

model_pr_auc = float(average_precision_score(y, held))
model_roc_auc = float(roc_auc_score(y, held))
model_brier = float(brier_score_loss(y, held))
model_log_loss = float(log_loss(y, held))
print(f"Model (Grouped by session): PR-AUC={model_pr_auc:.3f}, ROC-AUC={model_roc_auc:.3f}, Brier={model_brier:.4f}, LogLoss={model_log_loss:.4f}")

# 3. Outer Split: Leave-Device-Out
device_eval = {}
if len(phones) >= 2:
    for target in phones:
        tr_mask = (df.phone != target).values
        te_mask = (df.phone == target).values
        if tr_mask.any() and te_mask.any():
            clf_dev = get_clf().fit(Z[tr_mask], y[tr_mask])
            pred_dev = clf_dev.predict_proba(Z[te_mask])[:, 1]
            y_dev = y[te_mask]
            rule_dev = rule[te_mask]
            device_eval[target] = {
                "rows": int(te_mask.sum()),
                "base_rate": round(float(y_dev.mean()), 4),
                "model_pr_auc": round(float(average_precision_score(y_dev, pred_dev)), 3),
                "rule_pr_auc": round(float(average_precision_score(y_dev, rule_dev)), 3),
                "model_brier": round(float(brier_score_loss(y_dev, pred_dev)), 4),
                "rule_brier": round(float(brier_score_loss(y_dev, rule_dev)), 4),
                "model_roc_auc": round(float(roc_auc_score(y_dev, pred_dev)), 3),
                "rule_roc_auc": round(float(roc_auc_score(y_dev, rule_dev)), 3),
            }
            print(f"Leave-Device-Out (Test on {target}): Model PR-AUC={device_eval[target]['model_pr_auc']} vs Rule={device_eval[target]['rule_pr_auc']}, Model Brier={device_eval[target]['model_brier']} vs Rule={device_eval[target]['rule_brier']}")

# 4. Operational Thresholds & Calibration
prec, rec, thr = precision_recall_curve(y, held)
# Medium catches 70% of drops; High keeps precision at 3x base rate
medium_idx = np.argmin(np.abs(rec[:-1] - 0.70))
medium_t = float(thr[medium_idx])
ok_idx = np.where(prec[:-1] >= 3.0 * y.mean())[0]
high_t = float(thr[ok_idx[0]]) if len(ok_idx) else float(np.quantile(held, 0.95))
high_t = max(high_t, medium_t * 1.5)

# Recall at operational false-alarm rate (FAR = 5% and 10%)
neg_mask = (y == 0)
t_far_5 = float(np.percentile(held[neg_mask], 95))
t_far_10 = float(np.percentile(held[neg_mask], 90))
recall_at_far_5 = float((held[y == 1] >= t_far_5).mean())
recall_at_far_10 = float((held[y == 1] >= t_far_10).mean())

# Calibration Bins
calib_bins = []
bins = np.linspace(0.0, 1.0, 11)
for i in range(len(bins) - 1):
    low, high = bins[i], bins[i + 1]
    in_bin = (held >= low) & (held < high if i < len(bins) - 2 else held <= high)
    if in_bin.sum() > 0:
        calib_bins.append({
            "bin": f"{low:.1f}-{high:.1f}",
            "mean_predicted": round(float(held[in_bin].mean()), 3),
            "observed": round(float(y[in_bin].mean()), 3),
            "samples": int(in_bin.sum())
        })

# 5. Bootstrap 95% Confidence Intervals by Session
rng = np.random.RandomState(42)
sessions_list = list(set(g))
boot_pr = []
boot_brier = []
for _ in range(500):
    boot_sessions = set(rng.choice(sessions_list, size=len(sessions_list), replace=True))
    mask = np.isin(g, list(boot_sessions))
    if y[mask].sum() > 0:
        boot_pr.append(average_precision_score(y[mask], held[mask]))
        boot_brier.append(brier_score_loss(y[mask], held[mask]))

ci_pr = [round(float(np.percentile(boot_pr, 2.5)), 3), round(float(np.percentile(boot_pr, 97.5)), 3)]
ci_brier = [round(float(np.percentile(boot_brier, 2.5)), 4), round(float(np.percentile(boot_brier, 97.5)), 4)]

metrics = {
    "model_pr_auc": round(model_pr_auc, 3), "rule_pr_auc": round(rule_pr_auc, 3),
    "model_brier": round(model_brier, 4), "rule_brier": round(rule_brier, 4),
    "model_roc_auc": round(model_roc_auc, 3), "rule_1_minus_pdr2s_roc_auc": round(rule_roc_auc, 3),
    "model_log_loss": round(model_log_loss, 4),
    "recall_at_far_5pct": round(recall_at_far_5, 3), "recall_at_far_10pct": round(recall_at_far_10, 3),
    "pr_auc_95ci": ci_pr, "brier_95ci": ci_brier,
    "medium_threshold": round(medium_t, 4), "high_threshold": round(high_t, 4),
    "base_rate": round(float(y.mean()), 4),
    "rows": int(len(df)), "sessions": int(len(set(g))),
    "phones": phones, "leave_device_out": device_eval,
    "calibration": calib_bins,
}

# 6. Fit Final Production Model
final = get_clf().fit(Z, y)
w, b = final.coef_[0], final.intercept_[0]

# 7. Export TFLite Model (Batch = 16 for Hexagon HTP / LiteRT)
import tensorflow as tf
inp = tf.keras.Input(shape=(len(F),), batch_size=BATCH, dtype=tf.float32)
dense = tf.keras.layers.Dense(1, activation="sigmoid")
out = tf.keras.layers.Reshape((1,))(dense(inp))
km = tf.keras.Model(inp, out)
dense.set_weights([(w / std).reshape(-1, 1).astype(np.float32), np.array([b - np.sum(w * mean / std)], dtype=np.float32)])
tfl = tf.lite.TFLiteConverter.from_keras_model(km).convert()
open("app/src/main/assets/link_ai_v1.tflite", "wb").write(tfl)

check = tf.lite.Interpreter(model_content=tfl); check.allocate_tensors()
x16 = X[:BATCH].astype(np.float32)
check.set_tensor(check.get_input_details()[0]["index"], x16); check.invoke()
got = check.get_tensor(check.get_output_details()[0]["index"]).ravel()
assert np.allclose(got, final.predict_proba(Z[:BATCH])[:, 1], atol=1e-4), "TFLite output disagrees with sklearn"

# 8. Export JSON Metadata
meta = {
    "features": F, "batch": BATCH,
    "target": "lost_5s: neighbour availability failure in next 5 s (no frame received)",
    "trained_at": datetime.datetime.now().isoformat(timespec="minutes"),
    "data": "real dual-device field logs with grouped session and leave-device splits",
    **metrics
}
json.dump(meta, open("app/src/main/assets/link_ai_v1.json", "w"), indent=2)

# 9. Export Pure-Kotlin LossPredictor v2
cols = [ALL.index(f) for f in F]
txt = ["linkfill-logreg 1"] + [
    f"meta {k}={str(v).replace(' ', '_')}" for k, v in meta.items() if k not in ("features", "phones", "leave_device_out", "calibration", "pr_auc_95ci", "brier_95ci")
] + [
    "columns " + " ".join(map(str, cols)),
    "features " + " ".join(ALL),
    "mean " + " ".join(repr(float(v)) for v in mean),
    "std " + " ".join(repr(float(v)) for v in std),
    "weights " + " ".join(repr(float(v)) for v in [b, *w])
]
open("core/src/main/resources/app/linkfill/core/mesh/loss_predictor_v2.txt", "w").write("\n".join(txt) + "\n")
print(f"\nSuccessfully wrote:")
print(f"  app/src/main/assets/link_ai_v1.tflite ({len(tfl)} bytes)")
print(f"  app/src/main/assets/link_ai_v1.json")
print(f"  core/src/main/resources/app/linkfill/core/mesh/loss_predictor_v2.txt")

