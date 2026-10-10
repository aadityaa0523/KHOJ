package app.linkfill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.linkfill.core.find.FindMode
import app.linkfill.core.find.FindState
import app.linkfill.core.find.Freshness
import app.linkfill.core.find.Level
import app.linkfill.core.find.Note
import app.linkfill.core.find.Proximity
import app.linkfill.core.find.RssiSnapshot
import app.linkfill.core.find.Source
import app.linkfill.core.find.Trend
import app.linkfill.find.FindUi
import app.linkfill.find.LinkPhase
import app.linkfill.find.RangingCaps
import app.linkfill.ui.FindScreen
import app.linkfill.ui.LinkFillTheme

/** DEBUG ONLY. `adb shell am start -n app.linkfill/.FindPreviewActivity --es mode PRECISION_DIRECTION`. All numbers below are made up. */
class FindPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = FindMode.valueOf(intent.getStringExtra("mode") ?: "PRECISION_DIRECTION")
        val now = System.currentTimeMillis()
        val rssi = RssiSnapshot(-63, -64.0, 2.5, 0.9, Trend.IMPROVING, Proximity.NEAR, 20, 300, 0.85)
        fun st(arrow: Double?, unc: Double?, dist: Double?, distUnc: Double?, src: Source?, notes: Set<Note> = emptySet(), prox: Proximity? = null, trend: Trend = Trend.UNKNOWN) =
            FindState(mode, dist, distUnc, src, arrow, unc, if (arrow != null) src else null, prox, trend, Freshness.LIVE, 2_000, Level.HIGH, notes, if (mode == FindMode.LAST_KNOWN) 45.0 else null)
        val state = when (mode) {
            FindMode.PRECISION_DIRECTION -> st(25.0, null, 7.0, null, Source.UWB)
            FindMode.PRECISION_DISTANCE -> st(null, null, 12.0, null, Source.BLE_CHANNEL_SOUNDING, setOf(Note.NO_ANGLE_FROM_HARDWARE, Note.MY_LOCATION_MISSING))
            FindMode.RANGE_WALK -> st(-30.0, 22.0, 9.0, null, Source.BLE_CHANNEL_SOUNDING)
            FindMode.LOCATION_BEARING -> st(-40.0, 25.0, 118.0, 9.0, Source.GPS)
            FindMode.BLE_PROXIMITY -> st(null, null, null, null, null, setOf(Note.GPS_TOO_CLOSE_FOR_DIRECTION), Proximity.NEAR, Trend.IMPROVING)
            FindMode.LAST_KNOWN -> st(null, null, 240.0, 14.0, Source.LAST_KNOWN, setOf(Note.TARGET_POSITION_OLD)).copy(targetFreshness = Freshness.STALE, targetFixAgeMs = 95_000)
            FindMode.UNAVAILABLE -> st(null, null, null, null, null, setOf(Note.MY_LOCATION_MISSING, Note.BLE_NOT_HEARD)).copy(targetFreshness = null, targetFixAgeMs = null)
        }
        val ui = FindUi(1, "Amma (preview)", true, state, state.arrowDeg, rssi, LinkPhase.RANGING, "SIMULATED preview", RangingCaps.NONE, null, true, true, 90.0, 10.0, true, 5.0, false, null, "(preview)", "preview", null, now)
        setContent {
            LinkFillTheme {
                Column {
                    Text("SIMULATED DATA - NOT A MEASUREMENT", color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFFC62828)).padding(8.dp))
                    FindScreen(ui, onStop = { finish() }, needRangingPermission = false, onAllowRanging = {}, onTrustAngle = {}, onGroundTruth = {})
                }
            }
        }
    }
}
