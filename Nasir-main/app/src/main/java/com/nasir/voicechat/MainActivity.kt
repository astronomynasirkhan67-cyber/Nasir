package com.nasir.voicechat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val vm = AppState()
    private lateinit var engine: VoiceEngine

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) vm.note = "Microphone permission is required for voice chat."
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashInfo.install(this)
        vm.crashInfo = CrashInfo.consume(this)
        engine = VoiceEngine(this, vm)
        vm.showSetup = !(ModelStore.hasLlm(this) && ModelStore.hasVosk(this))
        vm.lang = "en"
        engine.init()
        setContent { App(vm, this) }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
        autoSetup()
    }

    override fun onStop() {
        // Release the microphone when the app goes to the background.
        try { engine.stopListening() } catch (_: Throwable) {}
        super.onStop()
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    fun onMic() {
        if (vm.listening) {
            engine.stopListening()
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            engine.startListening()
        } else {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun setLang(l: String) = engine.setLang(l)

    fun sendText(text: String) = engine.sendText(text)

    fun pickLlmFile() = pickModel.launch(arrayOf("*/*"))

    // Downloads everything that is missing (English speech model + Gemma 4) by itself.
    // Safe to call again: finished files are skipped and half-finished downloads resume.
    fun autoSetup(userTap: Boolean = false) {
        if (vm.busy) return
        val needVosk = !ModelStore.hasVosk(this)
        val needGemma = !ModelStore.hasLlm(this)
        if (!needVosk && !needGemma) {
            if (userTap) vm.note = "Everything is already installed ✓"
            return
        }
        vm.busy = true
        Thread {
            try {
                if (needVosk) {
                    vm.post { vm.note = "Downloading English speech model… 0%" }
                    ModelStore.fetchVosk(this) { p -> vm.post { vm.note = "Downloading English speech model… $p%" } }
                    vm.post { vm.tick++ }
                    engine.loadVosk()
                }
                if (needGemma) {
                    vm.post { vm.note = "Downloading Gemma 4 (about 2.4 GB, use Wi-Fi)… 0%" }
                    ModelStore.fetchGemma(this) { p ->
                        vm.post { vm.note = "Downloading Gemma 4 (about 2.4 GB, use Wi-Fi)… $p%" }
                    }
                    vm.post { vm.tick++ }
                    engine.loadLlm()
                }
                vm.post { vm.note = "All set ✓"; vm.tick++; vm.showSetup = false }
            } catch (e: Throwable) {
                vm.post { vm.note = "Download stopped: ${e.message ?: "Unknown error"}. Tap \"Download / resume\" to continue." }
            } finally {
                vm.post { vm.busy = false }
            }
        }.start()
    }

    private fun copyModel(uri: Uri) {
        if (vm.busy) return
        vm.busy = true
        vm.note = "Copying Gemma model file…"
        Thread {
            try {
                val dest = ModelStore.llmFile(this)
                dest.parentFile?.mkdirs()
                val tmp = java.io.File(dest.path + ".part")
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw java.io.IOException("Could not save file")
                vm.post { vm.note = "Gemma model file copied ✓"; vm.tick++ }
                engine.loadLlm()
            } catch (e: Throwable) {
                vm.post { vm.note = "Setup error: ${e.message ?: "Unknown error"}" }
            } finally {
                vm.post { vm.busy = false }
            }
        }.start()
    }
}

// ------------------------------------------------------------------ theme

private val Ink = Color(0xFF0A0A0A)
private val Soft = Color(0xFFF2F2F2)
private val Line = Color(0xFFE0E0E0)
private val Muted = Color(0xFF6B6B6B)
private val Disabled = Color(0xFFBDBDBD)

private val NkColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Soft,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = Color.White,
    secondaryContainer = Soft,
    onSecondaryContainer = Ink,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Soft,
    onSurfaceVariant = Ink,
    outline = Line,
    outlineVariant = Line
)

@Composable
fun App(st: AppState, act: MainActivity) {
    MaterialTheme(colorScheme = NkColors) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            if (st.showSetup) SetupScreen(st, act) else ChatScreen(st, act)
        }
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
fun NkLogo(size: androidx.compose.ui.unit.Dp) {
    Image(
        painter = painterResource(R.drawable.ic_nk_logo),
        contentDescription = "NK Voice AI",
        modifier = Modifier.size(size)
    )
}

@Composable
fun WaveIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val heights = listOf(0.35f, 0.7f, 1f, 0.55f, 0.3f)
        val step = w / heights.size
        heights.forEachIndexed { i, f ->
            val x = step * (i + 0.5f)
            drawLine(
                color = color,
                start = Offset(x, h / 2f - h * f / 2f),
                end = Offset(x, h / 2f + h * f / 2f),
                strokeWidth = step * 0.5f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun CircleButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp)
            .background(if (enabled) Ink else Disabled, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun LangPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) Ink else Color.White,
        border = BorderStroke(1.dp, if (selected) Ink else Line),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = if (selected) Color.White else Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
fun blackButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Ink,
    contentColor = Color.White,
    disabledContainerColor = Disabled,
    disabledContentColor = Color.White
)

// ------------------------------------------------------------------ screens

@Composable
fun SetupScreen(st: AppState, act: MainActivity) {
    val tick = st.tick
    val enOk = remember(tick) { ModelStore.hasVosk(act) }
    val llmOk = remember(tick) { ModelStore.hasLlm(act) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NkLogo(44.dp)
            Spacer(Modifier.width(12.dp))
            Text("NK Voice AI", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Your private AI voice assistant. On first launch the app downloads its own models. " +
                "After that, chat and English voice work without internet.",
            color = Muted
        )
        Surface(shape = RoundedCornerShape(14.dp), color = Soft, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("English voice: ${if (enOk) "Ready ✓" else "Not installed"}")
                Text("Urdu voice: uses your phone's speech service")
                Text("Gemma 4: ${if (llmOk) (if (st.gemmaReady) "Ready ✓" else "Downloaded, loading…") else "Not installed"}")
            }
        }
        if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink, trackColor = Line)
        if (st.note.isNotEmpty()) Text(st.note, fontSize = 13.sp, color = Muted)
        Button(
            onClick = { act.autoSetup(true) }, enabled = !st.busy,
            colors = blackButtonColors(), modifier = Modifier.fillMaxWidth()
        ) { Text("Download / resume") }
        OutlinedButton(
            onClick = { act.pickLlmFile() }, enabled = !st.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("I already have the .litertlm file on this phone") }
        Button(
            onClick = { st.showSetup = false }, enabled = (enOk || llmOk) && !st.busy,
            colors = blackButtonColors(), modifier = Modifier.fillMaxWidth()
        ) { Text("Start chatting") }
    }
}

@Composable
fun ChatScreen(st: AppState, act: MainActivity) {
    val listState = rememberLazyListState()
    val urdu = st.lang == "ur"
    LaunchedEffect(st.messages.size) {
        if (st.messages.isNotEmpty()) listState.animateScrollToItem(st.messages.size - 1)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NkLogo(32.dp)
            Spacer(Modifier.width(10.dp))
            Text("NK Voice AI", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            LangPill("English", !urdu) { act.setLang("en") }
            Spacer(Modifier.width(6.dp))
            LangPill("اردو", urdu) { act.setLang("ur") }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

        // Crash report from the previous run (shown once)
        if (st.crashInfo.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp), color = Color.White,
                border = BorderStroke(1.dp, Ink), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("The app closed last time. Reason:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Box(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer { Text(st.crashInfo, fontSize = 11.sp) }
                    }
                    TextButton(onClick = { st.crashInfo = "" }) { Text("Dismiss") }
                }
            }
        }

        // Messages
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (st.messages.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    NkLogo(72.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (urdu) "میں آپ کی کیا مدد کر سکتا ہوں؟" else "How can I help you today?",
                        fontSize = 20.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (urdu) "لکھیں، یا مائیک دبا کر بولیں" else "Type below, or tap the microphone to talk.",
                        color = Muted, fontSize = 14.sp
                    )
                }
            }
            items(st.messages) { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (m.fromUser) Soft else Color.White,
                        border = if (m.fromUser) null else BorderStroke(1.dp, Line),
                        modifier = Modifier.widthIn(max = 310.dp)
                    ) {
                        Text(
                            m.text, color = Ink, fontSize = 16.sp, lineHeight = 22.sp,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)
                        )
                    }
                }
            }
            if (st.responding) item { Text("Thinking…", color = Muted, modifier = Modifier.padding(8.dp)) }
        }

        if (st.note.isNotEmpty()) Text(st.note, fontSize = 12.sp, color = Muted)
        Text(st.status, Modifier.padding(vertical = 4.dp), fontSize = 12.sp, color = Muted)

        // Input row: text field + (mic when empty, send when typing)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.draft, onValueChange = { st.draft = it },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(26.dp),
                placeholder = { Text(if (urdu) "پیغام لکھیں…" else "Message NK Voice AI…", color = Muted) },
                maxLines = 4, enabled = !st.responding
            )
            Spacer(Modifier.width(8.dp))
            if (st.draft.isNotBlank()) {
                CircleButton(
                    onClick = {
                        val text = st.draft.trim()
                        if (text.isNotEmpty() && !st.responding) { st.draft = ""; act.sendText(text) }
                    },
                    enabled = !st.responding
                ) { Text("↑", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            } else {
                CircleButton(onClick = { act.onMic() }, enabled = !st.responding || st.listening) {
                    if (st.listening) {
                        Box(Modifier.size(16.dp).background(Color.White, RoundedCornerShape(3.dp)))
                    } else {
                        WaveIcon(Color.White, Modifier.size(24.dp))
                    }
                }
            }
        }

        // Options row
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = st.handsFree, onCheckedChange = { st.handsFree = it })
            Spacer(Modifier.width(8.dp))
            Text("Hands-free", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { st.showSetup = true }) { Text("Setup", color = Muted) }
        }
    }
}
