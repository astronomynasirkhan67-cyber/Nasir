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
        if (ok) engine.startListening() else vm.note = "माइक की अनुमति चाहिए / Mic permission needed"
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = VoiceEngine(this, vm)
        vm.showSetup = !(ModelStore.hasVosk(this, "hi") || ModelStore.hasVosk(this, "en"))
        if (!ModelStore.hasVosk(this, "hi") && ModelStore.hasVosk(this, "en")) vm.lang = "en"
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

    fun pickLlmFile() = pickModel.launch(arrayOf("*/*"))

    fun downloadVosk() {
        if (vm.busy) return
        vm.busy = true
        Thread {
            try {
                for (l in listOf("hi", "en")) {
                    if (ModelStore.hasVosk(this, l)) continue
                    vm.post { vm.note = "Vosk $l download… 0%" }
                    ModelStore.fetchVosk(this, l) { p -> vm.post { vm.note = "Vosk $l download… $p%" } }
                }
                vm.post { vm.note = "Vosk तैयार ✓"; vm.tick++ }
                engine.loadVosk()
            } catch (e: Throwable) {
                vm.post { vm.note = "Error: ${e.message}" }
            } finally {
                vm.post { vm.busy = false }
            }
        }.start()
    }

    fun downloadLlm() {
        val url = vm.llmUrl.trim()
        if (url.isEmpty()) { vm.note = "पहले Gemma का link डालो / Paste the Gemma link first"; return }
        if (vm.busy) return
        vm.busy = true
        Thread {
            try {
                vm.post { vm.note = "Gemma download… 0%" }
                ModelStore.download(url, ModelStore.llmFile(this), vm.hfToken) { p ->
                    vm.post { vm.note = "Gemma download… $p%" }
                }
                vm.post { vm.note = "Gemma तैयार ✓ (load हो रहा है)"; vm.tick++ }
                engine.loadLlm()
            } catch (e: Throwable) {
                vm.post { vm.note = "Error: ${e.message}" }
            } finally {
                vm.post { vm.busy = false }
            }
        }.start()
    }

    private fun copyModel(uri: Uri) {
        if (vm.busy) return
        vm.busy = true
        vm.note = "Gemma file copy हो रही है…"
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ModelStore.llmFile(this).outputStream().use { input.copyTo(it) }
                }
                vm.post { vm.note = "Gemma तैयार ✓"; vm.tick++ }
                engine.loadLlm()
            } catch (e: Throwable) {
                vm.post { vm.note = "Error: ${e.message}" }
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
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("NK Voice AI", style = MaterialTheme.typography.headlineSmall)
        Text("एक बार इंटरनेट से models लो। उसके बाद सब offline चलेगा।")

        Text("Vosk हिंदी: ${if (hiOk) "✓" else "✗"}    Vosk English: ${if (enOk) "✓" else "✗"}    Gemma: ${if (llmOk) "✓" else "✗"}")

        Button(onClick = { act.downloadVosk() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("1) Vosk (हिंदी + English) download करो")
        }

        Text("Gemma model (MediaPipe .task या .bin file). Hugging Face का direct link डालो:")
        OutlinedTextField(
            value = st.llmUrl, onValueChange = { st.llmUrl = it },
            label = { Text("Gemma link (https://huggingface.co/.../resolve/main/xxx.task)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = st.hfToken, onValueChange = { st.hfToken = it },
            label = { Text("Hugging Face token (ज़रूरी हो तो)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { act.downloadLlm() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("2) Gemma link से download करो")
        }
        OutlinedButton(onClick = { act.pickLlmFile() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("या फ़ोन से Gemma file चुनो")
        }

        if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (st.note.isNotEmpty()) Text(st.note)

        Button(
            onClick = { st.showSetup = false },
            enabled = (hiOk || enOk) && !st.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("चैट शुरू करो / Start chat") }
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
            FilterChip(selected = st.lang == "hi", onClick = { act.setLang("hi") }, label = { Text("हिंदी") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = st.lang == "en", onClick = { act.setLang("en") }, label = { Text("English") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { st.showSetup = true }) { Text("Setup") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(st.messages) { m ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp)
                    ) { Text(m.text, Modifier.padding(12.dp)) }
                }
            }
        }

        if (st.note.isNotEmpty()) Text(st.note, style = MaterialTheme.typography.bodySmall)
        Text(st.status, Modifier.padding(vertical = 6.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = st.handsFree, onCheckedChange = { st.handsFree = it })
            Spacer(Modifier.width(8.dp))
            Text("Hands-free")
            Spacer(Modifier.weight(1f))
            Button(onClick = { act.onMic() }) {
                Text(if (st.listening) "⏹ रोको" else "🎤 बोलो")
            }
        }
    }
}
