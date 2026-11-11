package com.vasmarfas.networkhelm

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.app.Services
import com.vasmarfas.networkhelm.app.androidServices
import com.vasmarfas.networkhelm.app.localNetworkAllowed
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.ui.terminal.AndroidTerminal
import com.vasmarfas.networkhelm.ui.terminal.LocalTerminal
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        FileKit.init(this)
        val services = androidServices(applicationContext)
        setContent {
            val scope = rememberCoroutineScope()
            val shell = remember { Shell(services, scope) }
            // the process outlives the activity, so the sessions end here: a router in Safe Mode rolls its changes back
            DisposableEffect(shell) { onDispose { shell.routers.toList().forEach(shell::disconnect) } }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) { if (!localNetworkAllowed(this@MainActivity)) ask.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }
            BackHandler(enabled = shell.canGoBack) { shell.back() }
            val night = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val dark = shell.settings.theme.isDark(shell.settings.current.dark, night)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(LocalTerminal provides { channel, modifier -> AndroidTerminal(channel, modifier) }) {
                App(shell)
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(Services(MemoryStorage(), MemoryTrustStore(), neighbors = null))
}
