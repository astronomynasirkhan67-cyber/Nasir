package com.nasir.voicechat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm = AppState()
    private lateinit var engine: VoiceEngine
    private var photoUri: Uri? = null

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) engine.startListening() else vm.note = "Microphone permission is needed"
    }
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyModel(uri)
    }
    private val pickDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) summarizeUri(uri, null)
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) summarizeUri(uri, null)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = photoUri
        if (ok && u != null) summarizeUri(u, "photo")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = VoiceEngine(this, vm)
        val hasHi = ModelStore.hasVosk(this, "hi")
        val hasEn = ModelStore.hasVosk(this, "en")
        vm.showSetup = !(hasHi || hasEn)
        vm.lang = if (hasEn || !hasHi) "en" else "hi"
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

    fun sendTyped() {
        val t = vm.typed.trim()
        if (t.isEmpty()) return
        vm.typed = ""
        engine.submitText(t)
    }

    fun setLang(l: String) = engine.setLang(l)
    fun pickLlmFile() = pickModel.launch(arrayOf("*/*"))
    fun pickDocument() = pickDoc.launch(arrayOf("*/*"))
    fun pickPicture() = pickImage.launch(arrayOf("image/*"))

    fun openCamera() {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "photo_" + System.currentTimeMillis() + ".jpg")
        val u = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        photoUri = u
        takePhoto.launch(u)
    }

    private fun summarizeUri(uri: Uri, label: String?) {
        vm.status = "Reading…"
        Thread {
            try {
                val name = label ?: Extractor.displayName(this, uri)
                val ex = Extractor.fromFile(this, uri, vm.lang == "hi")
                vm.post { engine.summarize(name, ex) }
            } catch (e: Throwable) {
                vm.post { vm.note = "Could not read it: ${e.message}"; vm.status = "Ready" }
            }
        }.start()
    }

    fun downloadVosk() {
        if (vm.busy) return
        vm.busy = true
        Thread {
            try {
                for (l in listOf("en", "hi")) {
                    if (ModelStore.hasVosk(this, l)) continue
                    vm.post { vm.note = "Vosk $l download… 0%" }
                    ModelStore.fetchVosk(this, l) { p -> vm.post { vm.note = "Vosk $l download… $p%" } }
                }
                vm.post { vm.note = "Vosk ready ✓"; vm.tick++ }
                engine.loadVosk()
            } catch (e: Throwable) {
                vm.post { vm.note = "Error: ${e.message}" }
            } finally {
                vm.post { vm.busy = false }
            }
        }.start()
    }

    private fun fetchLlm(url: String, token: String?, kind: String) {
        if (vm.busy) return
        vm.busy = true
        Thread {
            try {
                vm.post { vm.note = "AI model download… 0%" }
                ModelStore.download(url, ModelStore.llmFile(this), token) { p ->
                    vm.post { vm.note = "AI model download… $p%" }
                }
                ModelStore.setKind(this, kind)
                vm.post { vm.note = "AI model downloaded ✓ (loading…)"; vm.tick++ }
                engine.loadLlm()
            } catch (e: Throwable) {
                vm.post { vm.note = "Error: ${e.message}" }
            } finally {
                vm.post { vm.busy = false }
            }
        }.start()
    }

    fun downloadBuiltInModel() = fetchLlm(ModelStore.QWEN_URL, null, "chatml")

    fun downloadLlmFromLink() {
        val url = vm.llmUrl.trim()
        if (url.isEmpty()) { vm.note = "Paste a model link first"; return }
        fetchLlm(url, vm.hfToken, if (url.lowercase().contains("gemma")) "gemma" else "chatml")
    }

    private fun copyModel(uri: Uri) {
        if (vm.busy) return
        vm.busy = true
        vm.note = "Copying AI model file…"
        Thread {
            try {
                var name = ""
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: ""
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    ModelStore.llmFile(this).outputStream().use { input.copyTo(it) }
                }
                ModelStore.setKind(this, if (name.lowercase().contains("gemma")) "gemma" else "chatml")
                vm.post { vm.note = "AI model ready ✓"; vm.tick++ }
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
        Text("Download the models once with internet. After that everything works offline.")
        Text("Vosk English: ${if (enOk) "✓" else "✗"}    Vosk Hindi: ${if (hiOk) "✓" else "✗"}    AI model: ${if (llmOk) "✓" else "✗"}")

        Button(onClick = { act.downloadVosk() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("1) Download speech models (English + Hindi)")
        }
        Button(onClick = { act.downloadBuiltInModel() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("2) Download built-in AI model (~550 MB, no login)")
        }

        Text("Or use your own AI model (.task / .bin):")
        OutlinedTextField(
            value = st.llmUrl, onValueChange = { st.llmUrl = it },
            label = { Text("Model link (optional)") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = st.hfToken, onValueChange = { st.hfToken = it },
            label = { Text("Hugging Face token (only if the model needs login)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { act.downloadLlmFromLink() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Download from link")
        }
        OutlinedButton(onClick = { act.pickLlmFile() }, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Pick a model file from phone")
        }

        if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (st.note.isNotEmpty()) Text(st.note)

        Button(
            onClick = { st.showSetup = false },
            enabled = (hiOk || enOk) && !st.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start chat") }
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
            FilterChip(selected = st.lang == "hi", onClick = { act.setLang("hi") }, label = { Text("Hindi") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = st.lang == "en", onClick = { act.setLang("en") }, label = { Text("English") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { st.showSetup = true }) { Text("Setup") }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(st.messages) { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
                ) {
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
        Text(st.status, Modifier.padding(vertical = 4.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.typed, onValueChange = { st.typed = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Type here, or ask for code…") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { act.sendTyped() }) { Text("Send") }
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { act.openCamera() }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)) { Text("📷 Camera") }
            OutlinedButton(onClick = { act.pickPicture() }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)) { Text("🖼 Picture") }
            OutlinedButton(onClick = { act.pickDocument() }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)) { Text("📎 File") }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = st.handsFree, onCheckedChange = { st.handsFree = it })
            Spacer(Modifier.width(6.dp))
            Text("Hands-free", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(12.dp))
            Switch(checked = st.codeMode, onCheckedChange = { st.codeMode = it })
            Spacer(Modifier.width(6.dp))
            Text("Code → ZIP", style = MaterialTheme.typography.bodySmall)
        }

        Button(onClick = { act.onMic() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(if (st.listening) "⏹ Stop" else "🎤 Speak")
        }
    }
}
