package app.linkfill

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.linkfill.mesh.MeshController
import app.linkfill.session.LinkSession
import app.linkfill.session.Phase
import app.linkfill.session.Role
import app.linkfill.ui.DiagnosticsScreen
import app.linkfill.ui.HomeScreen
import app.linkfill.ui.KhojApp
import app.linkfill.ui.LinkFillTheme
import app.linkfill.ui.MeshScreen
import app.linkfill.ui.PermissionScreen
import app.linkfill.ui.ReceiverScreen
import app.linkfill.ui.SenderScreen
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

class MainActivity : ComponentActivity() {
    /** Before Android 13 there is no per-app language setting; apply the language chosen in KHOJ ourselves. */
    override fun attachBaseContext(base: android.content.Context) {
        val tag = base.getSharedPreferences("mesh", android.content.Context.MODE_PRIVATE).getString("lang", null)
        if (tag == null || Build.VERSION.SDK_INT >= 33) return super.attachBaseContext(base)
        val config = android.content.res.Configuration(base.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LinkFillApp
        val playServicesOk = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        setContent { LinkFillTheme { App(app.session, app.mesh, playServicesOk) } }
    }
}

private enum class Screen { KHOJ, LINKFILL, DIAGNOSTICS, MESH }

/** Permissions Nearby Connections and location sharing need on this Android version. */
fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}

@Composable
private fun App(session: LinkSession, mesh: MeshController, playServicesOk: Boolean) {
    val state by session.state.collectAsStateWithLifecycle()
    val meshState by mesh.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.KHOJ) }
    var pendingRole by rememberSaveable { mutableStateOf<Role?>(null) }
    var deniedOnce by rememberSaveable { mutableStateOf(false) }

    fun missing() = requiredPermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun begin(role: Role) = when (role) {
        Role.SENDER -> session.startSharing()
        Role.RECEIVER -> session.startFinding()
        Role.NONE -> screen = Screen.MESH // the crew mesh needs the same Bluetooth and location permissions
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val role = pendingRole
        if (missing().isEmpty() && role != null) { pendingRole = null; deniedOnce = false; begin(role) } else deniedOnce = true
    }

    fun request(role: Role) {
        if (missing().isEmpty()) begin(role) else pendingRole = role
    }

    val role = pendingRole
    when {
        role != null -> {
            BackHandler { pendingRole = null; deniedOnce = false }
            PermissionScreen(
                role = role,
                denied = deniedOnce,
                onContinue = {
                    val ask = missing() + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                    launcher.launch(ask.toTypedArray())
                },
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                },
                onCancel = { pendingRole = null; deniedOnce = false },
            )
        }
        screen == Screen.MESH -> {
            BackHandler { screen = Screen.LINKFILL }
            MeshScreen(meshState, mesh, onBack = { screen = Screen.LINKFILL })
        }
        screen == Screen.DIAGNOSTICS -> {
            BackHandler { screen = Screen.LINKFILL }
            DiagnosticsScreen(state, session, onBack = { screen = Screen.LINKFILL })
        }
        state.role == Role.SENDER && state.phase != Phase.IDLE -> {
            BackHandler { session.reset() }
            SenderScreen(state, session, onDiagnostics = { screen = Screen.DIAGNOSTICS })
        }
        state.role == Role.RECEIVER && state.phase != Phase.IDLE -> {
            BackHandler { session.reset() }
            ReceiverScreen(state, session, onDiagnostics = { screen = Screen.DIAGNOSTICS })
        }
        screen == Screen.KHOJ -> KhojApp(meshState, mesh, onAdvanced = { screen = Screen.LINKFILL })
        else -> {
            BackHandler { screen = Screen.KHOJ }
            HomeScreen(
            state = state,
            playServicesOk = playServicesOk,
            onName = session::setName,
            onShare = { request(Role.SENDER) },
            onFind = { request(Role.RECEIVER) },
            onDiagnostics = { screen = Screen.DIAGNOSTICS },
            onMesh = { request(Role.NONE) },
        )
        }
    }
}
