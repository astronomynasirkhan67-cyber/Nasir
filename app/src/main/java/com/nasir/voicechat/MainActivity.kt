package com.nasir.voicechat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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

    private var camUri: Uri? = null

    private val galleryPick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) engine.askImage(uri, vm.imageQuestion)
    }

    private val filePick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) engine.askFile(uri, vm.imageQuestion)
    }

    private val cameraShot = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = camUri
        if (ok && u != null) engine.askImage(u, vm.imageQuestion)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashInfo.install(this)
        vm.crashInfo = CrashInfo.consume(this)
        engine = VoiceEngine(this, vm)
        vm.showSetup = !(ModelStore.hasLlm(this) && ModelStore.hasVosk(this))
        vm.lang = "en"
        vm.modelId = ModelStore.selected(this).id
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

    fun selectModel(id: String) {
        if (vm.busy) return
        engine.switchModel(id)
        vm.note = ""
        vm.tick++
    }

    fun nextModel() {
        if (vm.busy || vm.responding) return
        val have = ModelStore.MODELS.filter { ModelStore.hasModel(this, it) }
        if (have.size < 2) { vm.note = "Download another model in Setup to switch."; return }
        val i = have.indexOfFirst { it.id == vm.modelId }
        selectModel(have[(i + 1) % have.size].id)
    }

    fun openGallery() {
        if (vm.responding) return
        vm.imageQuestion = vm.draft.trim(); vm.draft = ""
        galleryPick.launch("image/*")
    }

    fun openCamera() {
        if (vm.responding) return
        vm.imageQuestion = vm.draft.trim(); vm.draft = ""
        try {
            val dir = java.io.File(cacheDir, "images").apply { mkdirs() }
            val f = java.io.File(dir, "camera.jpg")
            val u = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            camUri = u
            cameraShot.launch(u)
        } catch (e: Throwable) {
            vm.note = "Camera error: ${e.message}"
        }
    }

    // types: e.g. arrayOf("video/*"), arrayOf("application/pdf"), arrayOf("*/*")
    fun pickFile(types: Array<String>) {
        if (vm.responding) return
        vm.imageQuestion = vm.draft.trim(); vm.draft = ""
        try { filePick.launch(types) } catch (e: Throwable) { vm.note = "Could not open the file picker: ${e.message}" }
    }

    fun downloadFile(uri: String, name: String, mime: String) {
        Thread {
            val err = FileMaker.exportToDownloads(this, Uri.parse(uri), name, mime)
            runOnUiThread {
                vm.note = err ?: "Saved ✓  Downloads/NK Voice AI/$name"
                Toast.makeText(this, err ?: "Saved in Downloads/NK Voice AI", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    fun openFile(uri: String, mime: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Open"))
        } catch (_: ActivityNotFoundException) {
            vm.note = "No app can open this file. Find it in Downloads/NK Voice AI."
        } catch (e: Throwable) {
            vm.note = "Could not open: ${e.message}"
        }
    }

    fun shareFile(uri: String, mime: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Throwable) {
            vm.note = "Could not share: ${e.message}"
        }
    }

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
                    val m = ModelStore.selected(this)
                    vm.post { vm.note = "Downloading ${m.label} (${m.size}, use Wi-Fi)… 0%" }
                    ModelStore.fetchLlm(this) { p ->
                        vm.post { vm.note = "Downloading ${m.label} (${m.size}, use Wi-Fi)… $p%" }
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
        vm.note = "Copying ${ModelStore.selected(this).label} model file…"
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
                vm.post { vm.note = "Model file copied ✓"; vm.tick++ }
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
    val llmOk = remember(tick, st.modelId) { ModelStore.hasLlm(act) }
    val sel = remember(tick, st.modelId) { ModelStore.selected(act) }
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
                ModelStore.MODELS.forEach { m ->
                    val has = remember(tick) { ModelStore.hasModel(act, m) }
                    val state = when {
                        !has -> "Not installed"
                        m.id == st.modelId && st.gemmaReady -> "Ready ✓"
                        m.id == st.modelId -> "Downloaded, loading…"
                        else -> "Downloaded ✓"
                    }
                    Text("${m.label}: $state")
                }
            }
        }
        Text("Choose AI model", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelStore.MODELS.forEach { m ->
                LangPill(m.label, m.id == st.modelId) { act.selectModel(m.id) }
            }
        }
        Text(
            when (sel.id) {
                "qwen3fast" -> "Qwen3 Fast: smallest and quickest (answers in seconds). Best for short English questions. No picture support."
                "qwen3" -> "Qwen3: fast and good in English. For Urdu answers and pictures use Gemma 4. No picture support."
                else -> "Gemma 4: best quality, reads pictures, good for Urdu. Slower on this phone."
            }, fontSize = 12.sp, color = Muted
        )
        if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink, trackColor = Line)
        if (st.note.isNotEmpty()) Text(st.note, fontSize = 13.sp, color = Muted)
        Button(
            onClick = { act.autoSetup(true) }, enabled = !st.busy,
            colors = blackButtonColors(), modifier = Modifier.fillMaxWidth()
        ) { Text(if (llmOk) "Download / resume" else "Download ${sel.label}") }
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
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Try: \"make a pdf on Newton's laws\"  •  \"LED blinking code zip\"  •  tap + to send a picture, video, PDF or file",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }
            items(st.messages) { m ->
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start
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
                    if (m.fileUri != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { act.openFile(m.fileUri, m.fileMime ?: "*/*") },
                                colors = blackButtonColors(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) { Text("Open", fontSize = 13.sp) }
                            Button(
                                onClick = { act.downloadFile(m.fileUri, m.fileName ?: "file", m.fileMime ?: "*/*") },
                                colors = blackButtonColors(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) { Text("⬇ Download", fontSize = 13.sp) }
                            OutlinedButton(
                                onClick = { act.shareFile(m.fileUri, m.fileMime ?: "*/*") },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) { Text("Share", fontSize = 13.sp) }
                        }
                    }
                }
            }
            if (st.responding && st.messages.lastOrNull()?.fromUser == true) item { Text("Thinking…", color = Muted, modifier = Modifier.padding(8.dp)) }
        }

        if (st.note.isNotEmpty()) Text(st.note, fontSize = 12.sp, color = Muted)
        Text(st.status, Modifier.padding(vertical = 4.dp), fontSize = 12.sp, color = Muted)

        // Input row: text field + (mic when empty, send when typing)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { menu = true }, enabled = !st.responding,
                    modifier = Modifier.size(48.dp).border(1.dp, Line, CircleShape)
                ) { Text("+", fontSize = 26.sp, color = Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("📷  Camera") }, onClick = { menu = false; act.openCamera() })
                    DropdownMenuItem(text = { Text("🖼  Picture from gallery") }, onClick = { menu = false; act.openGallery() })
                    DropdownMenuItem(text = { Text("🎬  Video") }, onClick = { menu = false; act.pickFile(arrayOf("video/*")) })
                    DropdownMenuItem(text = { Text("📄  PDF") }, onClick = { menu = false; act.pickFile(arrayOf("application/pdf")) })
                    DropdownMenuItem(text = { Text("📝  Document (Word / text)") }, onClick = {
                        menu = false
                        act.pickFile(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*"))
                    })
                    DropdownMenuItem(text = { Text("📎  Any file") }, onClick = { menu = false; act.pickFile(arrayOf("*/*")) })
                }
            }
            Spacer(Modifier.width(8.dp))
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
            LangPill(ModelStore.MODELS.firstOrNull { it.id == st.modelId }?.label ?: "Model", true) { act.nextModel() }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { st.showSetup = true }) { Text("Setup", color = Muted) }
        }
    }
}
