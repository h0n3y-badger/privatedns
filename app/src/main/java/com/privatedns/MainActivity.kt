package com.privatedns

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

data class Preset(val label: String, val host: String)

val PRESETS = listOf(
    Preset("Quad9 unfiltered", "dns10.quad9.net"),
    Preset("Quad9 malware-blocking", "dns.quad9.net"),
    Preset("AdGuard ad-blocking", "dns.adguard-dns.com"),
    Preset("Cloudflare", "one.one.one.one"),
    Preset("Mullvad", "dns.mullvad.net"),
)

const val GRANT_CMD = "adb shell pm grant com.privatedns android.permission.WRITE_SECURE_SETTINGS"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(DnsController.read(context)) }
    var hasPerm by remember { mutableStateOf(DnsController.hasPermission(context)) }
    var selectedHost by remember { mutableStateOf(state.hostname.ifEmpty { PRESETS[0].host }) }
    var customHost by remember { mutableStateOf("") }
    var useCustom by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    fun refresh() {
        state = DnsController.read(context)
        hasPerm = DnsController.hasPermission(context)
    }

    LaunchedEffect(Unit) { refresh() }

    val targetHost = if (useCustom) customHost.trim() else selectedHost

    fun applyResult(err: String?, action: String) {
        refresh()
        log = if (err == null) "$action ✓  (mode=${state.mode}${if (state.hostname.isNotEmpty()) ", host=${state.hostname}" else ""})"
        else "$action failed: $err"
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Private DNS") }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Current state", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (state.mode) {
                            DnsController.MODE_HOSTNAME -> "Strict DoT → ${state.hostname}"
                            DnsController.MODE_AUTO -> "Automatic (opportunistic)"
                            else -> "Off (plaintext DNS)"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!hasPerm) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Missing WRITE_SECURE_SETTINGS — run once from the PC:",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(GRANT_CMD, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        OutlinedButton(onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("grant", GRANT_CMD))
                        }) { Text("Copy command") }
                    }
                }
            }

            Text("Provider", style = MaterialTheme.typography.titleMedium)
            PRESETS.forEach { p ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = !useCustom && selectedHost == p.host,
                        onClick = { useCustom = false; selectedHost = p.host },
                    )
                    Column {
                        Text(p.label)
                        Text(p.host, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = useCustom, onClick = { useCustom = true })
                OutlinedTextField(
                    value = customHost,
                    onValueChange = { customHost = it; useCustom = true },
                    label = { Text("Custom DoT hostname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = hasPerm && targetHost.isNotEmpty(),
                    onClick = { applyResult(DnsController.setHostname(context, targetHost), "Applied $targetHost") },
                ) { Text("Apply") }
                OutlinedButton(
                    enabled = hasPerm,
                    onClick = { applyResult(DnsController.setAutomatic(context), "Set automatic") },
                ) { Text("Automatic") }
                OutlinedButton(
                    enabled = hasPerm,
                    onClick = { applyResult(DnsController.setOff(context), "Turned off") },
                ) { Text("Off") }
            }

            Button(
                enabled = !testing && targetHost.isNotEmpty(),
                onClick = {
                    testing = true
                    log = "Testing DoT to $targetHost:853 …"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { DotTester.test(targetHost) }
                        log = (if (r.ok) "DoT OK ✓\n" else "DoT FAILED ✗\n") + r.detail
                        testing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Test $targetHost")
            }

            if (log.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        log,
                        Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
