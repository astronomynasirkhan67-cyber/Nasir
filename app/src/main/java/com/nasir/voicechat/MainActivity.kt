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
        vm.showSetup = !(ModelStore.hasLlm(this) && (ModelStore.hasVosk(this, "en") || ModelStore.hasVosk(this, "hi")))
        vm.lang = "en"
        engine.init()
        setContent { App(vm, this) }
        autoSetup()
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

    // Downloads everything that is missing (speech models + Gemma 4), by itself, on first launch.
    // Safe to call again: finished files are skipped and half-finished downloads resume.
    fun autoSetup() {
        if (vm.busy) return
        val needVosk = listOf("hi", "en").filter { !ModelStore.hasVosk(this, it) }
        val needGemma = !ModelStore.hasLlm(this)
        if (needVosk.isEmpty() && !needGemma) return
        vm.busy = true
        Thread {
            try {
                for (l in needVosk) {
                    vm.post { vm.note = "Downloading speech model ($l)… 0%" }
                    ModelStore.fetchVosk(this, l) { p -> vm.post { vm.note = "Downloading speech model ($l)… $p%" } }
                    vm.post { vm.tick++ }
                    if (l == vm.lang) engine.loadVosk()
                }
                if (needVosk.isNotEmpty()) engine.loadVosk()
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
        Text("Your private offline AI. On first launch the app downloads its own models (speech + Gemma 4). After that it works without internet.")
        Text("Speech English: ${if (enOk) "Ready ✓" else "Not installed"}\nSpeech Hindi: ${if (hiOk) "Ready ✓" else "Not installed"}\nGemma 4: ${if (llmOk) (if (st.gemmaReady) "Ready ✓" else "Downloaded, loading…") else "Not installed"}")
        if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (st.note.isNotEmpty()) Text(st.note)
        Button(onClick = { act.autoSetup() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Download / resume")
        }
        OutlinedButton(onClick = { act.pickLlmFile() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("I already have the .litertlm file on this phone")
        }
        Button(onClick = { st.showSetup = false }, enabled = (hiOk || enOk || llmOk) && !st.busy,
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
