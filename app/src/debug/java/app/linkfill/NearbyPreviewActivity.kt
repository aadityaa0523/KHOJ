package app.linkfill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.linkfill.core.protocol.LocationFix
import app.linkfill.mesh.CrewMember
import app.linkfill.mesh.MeshUiState
import app.linkfill.ui.CallBackground
import app.linkfill.ui.CallTheme
import app.linkfill.ui.NearbyScreen

/** DEBUG ONLY. Draws the Nearby screen with made-up people so the layout can be checked indoors: `adb shell am start -n app.linkfill/.NearbyPreviewActivity --ez night false`. */
class NearbyPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = intent.getBooleanExtra("night", false)
        val now = System.currentTimeMillis()
        val lat = 12.9716; val lon = 77.5946
        fun at(m: Double, deg: Double): Pair<Double, Double> = lat + m * Math.cos(Math.toRadians(deg)) / 111_320.0 to lon + m * Math.sin(Math.toRadians(deg)) / (111_320.0 * Math.cos(Math.toRadians(lat)))
        fun member(id: Int, name: String, m: Double, deg: Double, agoS: Int, batt: Int) = at(m, deg).let { CrewMember(id, name, it.first, it.second, 8, batt, now - agoS * 1000L, 1, -70) }
        val state = MeshUiState(
            running = true, name = "Me", setupDone = true, headingDeg = 20f, circleM = 100,
            myFix = LocationFix(lat, lon, 8f, 0f, 20f, now),
            members = listOf(member(1, "Maa", 4.0, 10.0, 1, 82), member(2, "Papa", 42.0, 38.0, 3, 64), member(3, "Aarav", 118.0, 322.0, 8, 41), member(4, "Nani", 420.0, 120.0, 12, 57), member(5, "Didi", 210.0, 200.0, 120, 23)),
            batteryUnrestricted = true, now = now,
        )
        setContent {
            CallTheme(night) { CallBackground { NearbyScreen(state, (application as LinkFillApp).mesh, null, {}, {}, {}, {}, {}, {}) } }
        }
    }
}
