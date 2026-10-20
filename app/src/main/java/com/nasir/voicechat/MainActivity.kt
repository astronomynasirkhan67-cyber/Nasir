package com.nasir.voicechat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val vm = AppState()
    private lateinit var engine: VoiceEngine

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) engine.startListening() else vm.note = "Microphone permission is required."
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = VoiceEngine(this, vm)
        vm.showSetup = !(ModelStore.hasVosk(this, "en") || ModelStore.hasVosk(this, "hi"))
        vm.lang = "en"
        engine.init()
        setContent { App(vm, this) }
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

    fun downloadVosk() {
        if (vm.busy) return
        vm.busy = true
        Thread {
            try {
                for (l in listOf("hi", "en")) {
                    if (ModelStore.hasVosk(this, l)) continue
                    vm.post { vm.note = "Downloading Vosk $l… 0%" }
                    ModelStore.fetchVosk(this, l) { p -> vm.post { vm.note = "Downloading Vosk $l… $p%" } }
                }
                vm.post { vm.note = "Vosk models downloaded ✓"; vm.tick++ }
                engine.loadVosk()
            } catch (e: Throwable) {
                vm.post { vm.note = "Setup error: ${e.message ?: "Unknown error"}" }
            } finally {
                vm.post { vm.busy = false }
            }
        }.start()
    }

    fun downloadLlm() {
        val url = vm.llmUrl.trim()
        if (url.isEmpty()) { vm.note = "Please enter a direct Gemma model download link first."; return }
        if (vm.busy) return
        vm.busy = true
        Thread {
            try {
                vm.post { vm.note = "Downloading Gemma… 0%" }
                ModelStore.download(url, ModelStore.llmFile(this), vm.hfToken) { p ->
                    vm.post { vm.note = "Downloading Gemma… $p%" }
                }
                vm.post { vm.note = "Gemma downloaded ✓ Loading model…"; vm.tick++ }
                engine.loadLlm()
            } catch (e: Throwable) {
                vm.post { vm.note = "Setup error: ${e.message ?: "Unknown error"}" }
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
                contentResolver.openInputStream(uri)?.use { input ->
                    ModelStore.llmFile(this).outputStream().use { input.copyTo(it) }
                }
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

@Composable
fun App(st: AppState, act: MainActivity) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (st.showSetup) SetupScreen(st, act) else ChatScreen(st, act)
        }
    }
}

@Composable
fun SetupScreen(st: AppState, act: MainActivity) {
    val tick = st.tick
    val hiOk = remember(tick) { ModelStore.hasVosk(act, "hi") }
    val enOk = remember(tick) { ModelStore.hasVosk(act, "en") }
    val llmOk = remember(tick) { ModelStore.hasLlm(act) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("NK Voice AI", style = MaterialTheme.typography.headlineLarge)
        Text("Your private AI assistant for English text chat and voice conversations.")
        Text("Models  •  Vosk English: ${if (enOk) "Ready ✓" else "Not installed"}  •  Vosk Hindi: ${if (hiOk) "Ready ✓" else "Not installed"}  •  Gemma: ${if (llmOk) "Ready ✓" else "Not installed"}")
        Button(onClick = { act.downloadVosk() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("1. Download speech models (English + Hindi)")
        }
        Text("Gemma language model")
        Text("Choose a compatible MediaPipe .task model file, or provide its direct download URL. The model is large and is not bundled inside the APK.")
        OutlinedTextField(value = st.llmUrl, onValueChange = { st.llmUrl = it },
            label = { Text("Direct Gemma model URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = st.hfToken, onValueChange = { st.hfToken = it },
            label = { Text("Hugging Face token (if required)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { act.downloadLlm() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("2. Download Gemma model")
        }
        OutlinedButton(onClick = { act.pickLlmFile() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Choose a model file from this phone")
        }
        if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (st.note.isNotEmpty()) Text(st.note)
        Button(onClick = { st.showSetup = false }, enabled = (hiOk || enOk) && !st.busy,
            modifier = Modifier.fillMaxWidth()) { Text("Start chatting") }
    }
}

@Composable
fun ChatScreen(st: AppState, act: MainActivity) {
    val listState = rememberLazyListState()
    LaunchedEffect(st.messages.size) {
        if (st.messages.isNotEmpty()) listState.animateScrollToItem(st.messages.size - 1)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("NK Voice AI", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            FilterChip(selected = st.lang == "en", onClick = { act.setLang("en") }, label = { Text("English") })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = st.lang == "hi", onClick = { act.setLang("hi") }, label = { Text("Hindi") })
            TextButton(onClick = { st.showSetup = true }) { Text("Setup") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            if (st.messages.isEmpty()) item {
                Text("Hello! Type a message below, or tap the microphone to talk.",
                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
            }
            items(st.messages) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(shape = RoundedCornerShape(18.dp),
                        color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 320.dp)) {
                        Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
            if (st.responding) item { Text("Thinking…", Modifier.padding(8.dp)) }
        }
        if (st.note.isNotEmpty()) Text(st.note, style = MaterialTheme.typography.bodySmall)
        Text(st.status, Modifier.padding(vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = st.draft, onValueChange = { st.draft = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Message in English…") },
                maxLines = 4, enabled = !st.responding)
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val text = st.draft.trim()
                if (text.isNotEmpty() && !st.responding) { st.draft = ""; act.sendText(text) }
            }, enabled = st.draft.isNotBlank() && !st.responding) { Text("Send") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = st.handsFree, onCheckedChange = { st.handsFree = it })
            Spacer(Modifier.width(8.dp)); Text("Hands-free")
            Spacer(Modifier.weight(1f))
            Button(onClick = { act.onMic() }) { Text(if (st.listening) "Stop listening" else "🎙 Voice chat") }
        }
    }
}
