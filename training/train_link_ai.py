"""
Link AI: predicts "this neighbour's Bluetooth link drops completely in the next 5 s" from the features the app logs on
real phones (link_log.csv), so the mesh can bring in Nearby / Wi-Fi Direct before the link is gone.

Usage (from the repo root):  python training/train_link_ai.py training/data/link_*.csv
Writes:
  app/src/main/assets/link_ai_v1.tflite          batch of 16 x 11 features -> 16 probabilities (NPU via QNN, CPU fallback)
  app/src/main/assets/link_ai_v1.json            feature order, thresholds, held-out metrics, data provenance
  core/src/main/resources/app/linkfill/core/mesh/loss_predictor_v2.txt   same model for the pure-Kotlin fallback

Evaluation is grouped by recording session (a neighbour id lives for one app run), never by random rows.
"""
import json, sys, glob, datetime
import numpy as np, pandas as pd
from sklearn.model_selection import GroupKFold
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score, average_precision_score, precision_recall_curve

ALL = ["distance_m", "rssi_median_2s", "rssi_min_2s", "rssi_std_10s", "rssi_slope_dbps", "rssi_drop", "pdr_0_5s", "pdr_2s",
       "pdr_10s", "consecutive_misses", "since_last_s", "coded_phy", "queue_depth"]   # LinkFeatures.ALL order
F = ["rssi_median_2s", "rssi_min_2s", "rssi_std_10s", "rssi_slope_dbps", "rssi_drop", "pdr_0_5s", "pdr_2s", "pdr_10s",
     "consecutive_misses", "since_last_s", "queue_depth"]   # distance_m and coded_phy never varied in the field data
BATCH = 16
FRAME_RATE_CHANGE_MS = 1791634467000  # 2026-10-10 17:54:27 IST: idle frames went from 250 to 500 ms, which changes the labels

files = sys.argv[1:] or glob.glob("training/data/link_*.csv")
parts = []
for f in files:
    d = pd.read_csv(f)
    d["phone"] = f.split("link_")[-1].split(".")[0]
    parts.append(d)
df = pd.concat(parts)
df = df[(df.time_ms < FRAME_RATE_CHANGE_MS) & (df.rssi_median_2s < 0) & (df.rssi_min_2s < 0)]
df["session"] = df.phone + ":" + df.neighbor.astype(str)
X, y, g = df[F].values.astype(np.float64), df.lost_5s.values.astype(int), df.session.values
print(f"rows {len(df)}  positives {y.sum()} ({y.mean():.3%})  sessions {len(set(g))}  phones {df.phone.nunique()}")

mean, std = X.mean(0), X.std(0) + 1e-9
Z = (X - mean) / std

def model(): return LogisticRegression(max_iter=3000, C=1.0)

held = np.zeros(len(y)); rule = 1 - df.pdr_2s.values
for tr, te in GroupKFold(n_splits=min(5, len(set(g)))).split(Z, y, g):
    held[te] = model().fit(Z[tr], y[tr]).predict_proba(Z[te])[:, 1]
metrics = {
    "model_roc_auc": round(roc_auc_score(y, held), 3), "model_pr_auc": round(average_precision_score(y, held), 3),
    "rule_1_minus_pdr2s_roc_auc": round(roc_auc_score(y, rule), 3), "rule_pr_auc": round(average_precision_score(y, rule), 3),
    "base_rate": round(float(y.mean()), 4),
}
# Thresholds from held-out predictions: "medium" catches 70 % of drops, "high" keeps precision at 3x the base rate.
prec, rec, thr = precision_recall_curve(y, held)
medium = float(thr[np.argmin(np.abs(rec[:-1] - 0.70))])
ok = np.where(prec[:-1] >= 3 * y.mean())[0]
high = float(thr[ok[0]]) if len(ok) else float(np.quantile(held, 0.95))
high = max(high, medium * 1.5)
for name, t in [("medium", medium), ("high", high)]:
    sel = held >= t
    metrics[f"{name}_threshold"] = round(t, 4)
    metrics[f"{name}_recall"] = round(float(y[sel].sum() / y.sum()), 3)
    metrics[f"{name}_precision"] = round(float(y[sel].mean()) if sel.any() else 0.0, 3)
    metrics[f"{name}_flagged_share"] = round(float(sel.mean()), 3)
print(json.dumps(metrics, indent=1))

final = model().fit(Z, y)
w, b = final.coef_[0], final.intercept_[0]

# TFLite: standardisation folded into one dense layer + sigmoid, fixed batch of 16 rows for the NPU.
import tensorflow as tf
inp = tf.keras.Input(shape=(len(F),), batch_size=BATCH, dtype=tf.float32)
dense = tf.keras.layers.Dense(1, activation="sigmoid")
out = tf.keras.layers.Reshape((1,))(dense(inp))
km = tf.keras.Model(inp, out)
dense.set_weights([(w / std).reshape(-1, 1).astype(np.float32), np.array([b - np.sum(w * mean / std)], dtype=np.float32)])
tfl = tf.lite.TFLiteConverter.from_keras_model(km).convert()
open("app/src/main/assets/link_ai_v1.tflite", "wb").write(tfl)
check = tf.lite.Interpreter(model_content=tfl); check.allocate_tensors()
x16 = X[:BATCH].astype(np.float32); check.set_tensor(check.get_input_details()[0]["index"], x16); check.invoke()
got = check.get_tensor(check.get_output_details()[0]["index"]).ravel()
assert np.allclose(got, final.predict_proba(Z[:BATCH])[:, 1], atol=1e-4), "tflite disagrees with sklearn"

meta = {"features": F, "batch": BATCH, "target": "lost_5s: no frame from this neighbour in the next 5 s",
        "trained_at": datetime.datetime.now().isoformat(timespec="minutes"), "rows": int(len(df)), "sessions": int(len(set(g))),
        "phones": sorted(df.phone.unique().tolist()), "data": "real iQOO I2501 field logs (link_log.csv)", **metrics}
json.dump(meta, open("app/src/main/assets/link_ai_v1.json", "w"), indent=1)

# Same model in the pure-Kotlin LossPredictor format (columns index LinkFeatures.ALL).
cols = [ALL.index(f) for f in F]
txt = ["linkfill-logreg 1"] + [f"meta {k}={str(v).replace(' ', '_')}" for k, v in meta.items() if k not in ("features", "phones")] + [
    "columns " + " ".join(map(str, cols)), "features " + " ".join(ALL),
    "mean " + " ".join(repr(float(v)) for v in mean), "std " + " ".join(repr(float(v)) for v in std),
    "weights " + " ".join(repr(float(v)) for v in [b, *w])]
open("core/src/main/resources/app/linkfill/core/mesh/loss_predictor_v2.txt", "w").write("\n".join(txt) + "\n")
print("wrote link_ai_v1.tflite (%d bytes), link_ai_v1.json, loss_predictor_v2.txt" % len(tfl))
