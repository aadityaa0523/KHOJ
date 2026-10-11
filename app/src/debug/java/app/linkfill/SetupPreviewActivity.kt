package app.linkfill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.linkfill.mesh.MeshUiState
import app.linkfill.ui.CallTheme
import app.linkfill.ui.SetupFlow

/** DEBUG ONLY. Draws the first setup step (language) so it can be checked without wiping the app's data: `adb shell am start -n app.linkfill/.SetupPreviewActivity --ez night false`. Do not tap a language: it changes the app language. */
class SetupPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = intent.getBooleanExtra("night", false)
        setContent { CallTheme(night) { SetupFlow(MeshUiState(name = "Asha"), (application as LinkFillApp).mesh) } }
    }
}
