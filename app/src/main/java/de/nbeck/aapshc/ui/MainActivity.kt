package de.nbeck.aapshc.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nbeck.aapshc.BuildConfig
import de.nbeck.aapshc.hc.HealthConnectSource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    // Nur in Debug-Builds: adb shell am start -n de.nbeck.aapshc/.ui.MainActivity --ez demo true [--ei tab 1]
    val demo = BuildConfig.DEBUG && activity?.intent?.getBooleanExtra("demo", false) == true
    if (demo) {
        vm.enableDemo()
        if (activity?.intent?.getBooleanExtra("pdf", false) == true) LaunchedEffect(Unit) { vm.exportDemoPdf() }
    }
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { vm.refresh() }

    // Benachrichtigungen (Android 13+) einmalig anfragen, wenn Hinweise eingeschaltet sind.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!demo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (s.notifyHypoRisk || s.notifyErrors || s.weeklyReport) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var tab by rememberSaveable { mutableIntStateOf(if (demo) activity?.intent?.getIntExtra("tab", 0) ?: 0 else 0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(if (tab == 0) "Health Connect → AAPS" else "Auswertung") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.CloudSync, contentDescription = null) }, label = { Text("Sync") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Insights, contentDescription = null) }, label = { Text("Auswertung") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        CompositionLocalProvider(LocalGlucoseUnit provides s.glucoseUnit) {
            if (tab == 1) {
                AnalysisScreen(s, vm, modifier)
            } else {
                SyncScreen(s, vm, modifier) {
                    val wanted = if (s.backgroundSupported) HealthConnectSource.ALL_PERMISSIONS
                    else HealthConnectSource.ALL_PERMISSIONS - HealthConnectSource.BACKGROUND
                    permissionLauncher.launch(wanted)
                }
            }
        }
    }
}
