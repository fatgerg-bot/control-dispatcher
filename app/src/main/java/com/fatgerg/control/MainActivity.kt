package com.fatgerg.control

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

private val Navy = Color(0xFF0E1E3A)
private val Card = Color(0xFF16294D)
private val Amber = Color(0xFFF5B800)
private val Green = Color(0xFF2FBF71)
private val Red = Color(0xFFE5484D)
private val Muted = Color(0xFF9FB0CC)

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var voiceForList: RadioVoice? = null
    private val voiceNames = mutableStateOf<List<Pair<String, String>>>(emptyList())

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        requestBasics()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Navy, surface = Navy, primary = Amber)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
                    var onSettings by remember { mutableStateOf(false) }
                    if (onSettings) {
                        SettingsScreen(onBack = { onSettings = false })
                    } else {
                        RadioScreen(onSettings = {
                            loadVoices()
                            onSettings = true
                        })
                    }
                }
            }
        }
    }

    private fun requestBasics() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (settings.alertNumber.isNotBlank()) wanted += Manifest.permission.SEND_SMS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun loadVoices() {
        if (voiceForList != null) return
        val v = RadioVoice(this, settings).also { it.init() }
        voiceForList = v
        thread {
            if (v.awaitReady()) voiceNames.value = v.labelledVoices()
        }
    }

    override fun onDestroy() {
        voiceForList?.shutdown()
        super.onDestroy()
    }

    // ---- Radio screen --------------------------------------------------------------------

    @Composable
    private fun RadioScreen(onSettings: () -> Unit) {
        val status by ControlState.status.collectAsState()
        val level by ControlState.level.collectAsState()
        val log by ControlState.log.collectAsState()
        val route by ControlState.audioRoute.collectAsState()
        val speaking by ControlState.speech.collectAsState()
        val held by ControlState.pttHeld.collectAsState()
        var sayText by remember { mutableStateOf("") }
        val onShift = status != Status.OFF
        val listState = rememberLazyListState()

        LaunchedEffect(log.size) {
            if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
        }
        LaunchedEffect(Unit) { ControlState.setRoute(AudioRoutes.describe(this@MainActivity)) }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.size(46.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("CONTROL", color = Amber, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onSettings) { Text("Settings", color = Muted) }
            }
            Text(route, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))

            // Status lamp
            val lampColour = when (status) {
                Status.LISTENING, Status.HEARING -> Green
                Status.THINKING, Status.LOADING -> Amber
                Status.TRANSMITTING, Status.SAFETY -> Red
                Status.OFF -> Muted
            }
            Row(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).background(lampColour, CircleShape))
                Spacer(Modifier.width(14.dp))
                Text(status.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))

            // Input level, for setting the radio's volume and the talk threshold
            Text("Radio input level", color = Muted, fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().height(14.dp)) {
                LinearProgressIndicator(
                    progress = { level / 100f },
                    modifier = Modifier.fillMaxWidth().height(14.dp),
                    color = if (speaking) Green else Muted,
                    trackColor = Card,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (status == Status.SAFETY) {
                Button(
                    onClick = { ControlService.send(this@MainActivity, ControlService.ACTION_RESUME) },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Everything's OK, resume the game", fontSize = 16.sp) }
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    if (!hasMic()) { requestBasics(); return@Button }
                    ControlService.send(
                        this@MainActivity,
                        if (onShift) ControlService.ACTION_END else ControlService.ACTION_START,
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (onShift) Red else Green),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(if (onShift) "End shift" else "Start shift", fontSize = 22.sp) }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { ControlService.send(this@MainActivity, ControlService.ACTION_TEST_TONE) },
                    modifier = Modifier.weight(1f),
                ) { Text("Test tone") }
                OutlinedButton(
                    onClick = { ControlService.send(this@MainActivity, ControlService.ACTION_STOP_NOW) },
                    modifier = Modifier.weight(1f),
                ) { Text("Stop now") }
            }
            Spacer(Modifier.height(8.dp))

            if (onShift && (settings.holdToTalkOnly || route.startsWith("No cable"))) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(if (held) Green else Card, RoundedCornerShape(32.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                ControlState.setPtt(true)
                                try { tryAwaitRelease() } finally { ControlState.setPtt(false) }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (held) "Talking… let go when done" else "Hold to talk",
                        color = if (held) Navy else Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sayText,
                    onValueChange = { sayText = it },
                    label = { Text("Say this over the radio") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (sayText.isNotBlank()) {
                        ControlService.send(this@MainActivity, ControlService.ACTION_SAY, sayText.trim())
                        sayText = ""
                    }
                }) { Text("Send") }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Card)

            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(log) { line ->
                    val (who, colour) = when (line.who) {
                        Who.OFFICER -> "OFFICER" to Green
                        Who.CONTROL -> "CONTROL" to Amber
                        Who.SYSTEM -> "APP" to Muted
                    }
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text("${line.time}  $who", color = colour, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                        Text(line.text, color = if (line.who == Who.SYSTEM) Muted else Color.White,
                            fontSize = 15.sp)
                    }
                }
            }
        }
    }

    // ---- Settings screen -----------------------------------------------------------------

    @Composable
    private fun SettingsScreen(onBack: () -> Unit) {
        var apiKey by remember { mutableStateOf(settings.apiKey) }
        var model by remember { mutableStateOf(settings.model) }
        var officers by remember { mutableStateOf(settings.officers) }
        var town by remember { mutableStateOf(settings.town) }
        var voice by remember { mutableStateOf(settings.voiceName) }
        var rate by remember { mutableStateOf(settings.speechRate.toString()) }
        var eoo by remember { mutableStateOf(settings.endOfOverSeconds.toString()) }
        var leadIn by remember { mutableStateOf(settings.leadInMs.toString()) }
        var threshold by remember { mutableStateOf(settings.talkThreshold.toString()) }
        var outLevel by remember { mutableStateOf(settings.outputLevel.toString()) }
        var idle by remember { mutableStateOf(settings.idleMinutes.toString()) }
        var trip by remember { mutableStateOf(settings.tripWords) }
        var alert by remember { mutableStateOf(settings.alertNumber) }
        var dnd by remember { mutableStateOf(settings.useDnd) }
        var beep by remember { mutableStateOf(settings.endBeep) }
        var pttOnly by remember { mutableStateOf(settings.holdToTalkOnly) }
        var prompt by remember { mutableStateOf(settings.customPrompt.ifBlank { Prompt.DEFAULT }) }
        val voices by voiceNames

        fun save() {
            settings.apiKey = apiKey
            settings.model = model
            settings.officers = officers
            settings.town = town
            settings.voiceName = voice
            rate.toFloatOrNull()?.let { settings.speechRate = it.coerceIn(0.5f, 2f) }
            eoo.toFloatOrNull()?.let { settings.endOfOverSeconds = it.coerceIn(0.3f, 3f) }
            leadIn.toIntOrNull()?.let { settings.leadInMs = it.coerceIn(0, 2000) }
            threshold.toIntOrNull()?.let { settings.talkThreshold = it.coerceIn(1, 90) }
            outLevel.toIntOrNull()?.let { settings.outputLevel = it.coerceIn(10, 100) }
            idle.toIntOrNull()?.let { settings.idleMinutes = it.coerceIn(1, 60) }
            settings.tripWords = trip
            settings.alertNumber = alert
            settings.useDnd = dnd
            settings.endBeep = beep
            settings.holdToTalkOnly = pttOnly
            settings.customPrompt = if (prompt.trim() == Prompt.DEFAULT.trim()) "" else prompt
            requestBasics()
            onBack()
        }

        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Amber, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("Cancel", color = Muted) }
                Button(onClick = { save() }) { Text("Save") }
            }

            Section("Claude")
            Field("Claude API key (stays on this phone)", apiKey, { apiKey = it }, secret = true)
            Field("Model", model, { model = it })

            Section("Officers and town")
            Field("One per line, e.g. Wolf One = Officer Wolf", officers, { officers = it }, lines = 4)
            Field("Town name", town, { town = it })

            Section("Control's voice")
            VoicePicker(voices, voice) { voice = it }
            OutlinedButton(onClick = {
                try {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (_: Exception) {
                    startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Get more voices and accents") }
            Field("Speech speed (1.0 = normal)", rate, { rate = it }, number = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bleep at the end of each transmission", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = beep, onCheckedChange = { beep = it })
            }

            Section("Radio levels")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Only listen while Hold to talk is pressed", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = pttOnly, onCheckedChange = { pttOnly = it })
            }
            Text("Handy for testing on the phone mic in a noisy room.", color = Muted, fontSize = 12.sp)
            Field("Talk threshold, 1-90 (how loud before it counts)", threshold, { threshold = it }, number = true)
            Field("Quiet seconds that end an over", eoo, { eoo = it }, number = true)
            Field("Lead-in tone before Control speaks (ms)", leadIn, { leadIn = it }, number = true)
            Field("Output level into the radio, 10-100", outLevel, { outLevel = it }, number = true)
            Field("Minutes of quiet before Control calls up", idle, { idle = it }, number = true)

            Section("Safety net")
            Field("Trip words, comma separated", trip, { trip = it }, lines = 4)
            Field("Text this number on a safety stop", alert, { alert = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Do Not Disturb while on shift", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = dnd, onCheckedChange = { dnd = it })
            }
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                OutlinedButton(onClick = {
                    startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow Do Not Disturb control") }
            }
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                OutlinedButton(onClick = {
                    startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }, modifier = Modifier.fillMaxWidth()) { Text("Stop the phone putting Control to sleep") }
            }

            Section("Control's instructions")
            Text("{OFFICERS}, {TOWN}, {TIME} and {SAFETY} are filled in automatically.",
                color = Muted, fontSize = 12.sp)
            Field("Prompt", prompt, { prompt = it }, lines = 12)
            TextButton(onClick = { prompt = Prompt.DEFAULT }) { Text("Reset to the built-in prompt") }
            Spacer(Modifier.height(40.dp))
        }
    }

    /** Drop-down list of the phone's British voices, with a button to hear the selected one. */
    @Composable
    private fun VoicePicker(voices: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }
        fun label(name: String): String {
            if (name.isEmpty()) return "Automatic (first British voice)"
            return voices.firstOrNull { it.first == name }?.second ?: name
        }
        Text("Voice", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (voices.isEmpty()) "Loading voices…" else label(selected) + "  ▾",
                        color = Color.White, fontSize = 13.sp,
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(label("")) },
                        onClick = { onPick(""); open = false },
                    )
                    voices.forEach { (name, lab) ->
                        DropdownMenuItem(
                            text = { Text(lab) },
                            onClick = { onPick(name); open = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                voiceForList?.preview(selected, "Wolf One from Control, radio check, over.")
            }) { Text("Hear it") }
        }
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun Section(title: String) {
        Spacer(Modifier.height(18.dp))
        Text(title.uppercase(), color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun Field(
        label: String,
        value: String,
        onChange: (String) -> Unit,
        secret: Boolean = false,
        number: Boolean = false,
        lines: Int = 1,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = lines == 1,
            minLines = lines,
            visualTransformation = if (secret) PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal)
                else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}
