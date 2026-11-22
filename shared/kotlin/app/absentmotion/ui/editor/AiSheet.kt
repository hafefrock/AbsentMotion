package app.absentmotion.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.AiConfig
import app.absentmotion.ai.AiGemini
import app.absentmotion.ai.AiRemoteConfig
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val AiExamples = listOf(
    "Tambah teks judul di tengah layar",
    "Buat lingkaran merah yang memantul",
    "Tambah efek glow pada layer terpilih",
    "Buat teks muncul pelan-pelan",
)

@Composable
internal fun AiBarButton(state: EditorState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.clickable { open = true }, contentAlignment = Alignment.Center) {
        Text("AI", color = Am.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    if (open) AiAssistantDialog(state) { open = false }
}

@Composable
private fun AiAssistantDialog(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val remote = remember { AiRemoteConfig.active(context) }
    val key = remember { remote?.apiKey ?: AiConfig.apiKey(context) }
    val model = remember { remote?.model ?: AiConfig.model(context) }
    val thinking = remember { AiConfig.thinking(context) }
    val vision = remember { AiConfig.vision(context) }
    var request by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf<List<String>>(emptyList()) }
    var skipped by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var turns by remember { mutableStateOf(AiGemini.conversation.countFor(state)) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Am.OnDark,
        unfocusedTextColor = Am.OnDark,
        cursorColor = Am.Accent,
        focusedBorderColor = Am.Accent,
        unfocusedBorderColor = Am.OnDark.copy(alpha = 0.4f),
        focusedLabelColor = Am.Accent,
        unfocusedLabelColor = Am.OnDark.copy(alpha = 0.7f),
    )

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Asisten AI", color = Am.OnDark) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (key.isBlank()) {
                    Text(
                        "API key ${remote?.label ?: "Gemini"} belum diisi. Buka Pengaturan di layar daftar proyek, lalu isi \"Asisten AI\".",
                        color = Am.OnDark,
                        fontSize = 14.sp,
                    )
                } else {
                    Text("Model: $model  •  Berpikir: ${AiConfig.thinkingLabel(thinking)}  •  Lihat hasil: ${if (vision) "ya" else "tidak"}", color = Am.OnDark.copy(alpha = 0.7f), fontSize = 12.sp)
                    if (turns > 0) {
                        Text("Percakapan berlanjut: $turns permintaan", color = Am.OnDark.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (request.isBlank() && message.isBlank() && !busy) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            for (example in AiExamples) {
                                Box(
                                    Modifier.padding(end = 6.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .border(1.dp, Am.Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                        .clickable { request = example }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(example, color = Am.Accent, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it },
                        minLines = 3,
                        label = { Text(if (turns > 0) "Lanjutkan atau ubah..." else "Apa yang ingin diubah?") },
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        Text(progress.ifBlank { "Memproses..." }, color = Am.Accent, fontSize = 14.sp)
                    }
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = Am.OnDark, fontSize = 14.sp)
                    }
                    for (line in applied) Text("✓ $line", color = Am.OnDark, fontSize = 13.sp)
                    for ((name, reason) in skipped) Text("✗ $name: $reason", color = Am.OnDark.copy(alpha = 0.7f), fontSize = 13.sp)
                    if (turns > 0 && !busy) {
                        TextButton({
                            AiGemini.conversation.reset()
                            turns = 0
                            message = ""
                            applied = emptyList()
                            skipped = emptyList()
                        }) { Text("Percakapan baru", color = Am.Accent, fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = {
            if (key.isNotBlank()) {
                TextButton(
                    onClick = {
                        val text = request.trim()
                        state.playing = false
                        busy = true
                        message = ""
                        applied = emptyList()
                        skipped = emptyList()
                        job = scope.launch {
                            AiGemini.remote = remote
                            val result = AiGemini.run(state, key, model, thinking, vision, context.cacheDir, text) { progress = it }
                            if (!isActive) return@launch
                            busy = false
                            progress = ""
                            message = result.galat ?: result.pesan
                            val outcome = result.hasil
                            if (outcome != null) {
                                applied = outcome.diterapkan
                                skipped = outcome.dilewati
                                if (outcome.dilewati.isEmpty()) request = ""
                            }
                            turns = AiGemini.conversation.countFor(state)
                            job = null
                        }
                    },
                    enabled = !busy && request.isNotBlank(),
                ) { Text("Kirim", color = Am.Accent) }
            }
        },
        dismissButton = {
            if (busy) {
                TextButton({
                    job?.cancel()
                    AiGemini.cancelActive()
                    job = null
                    busy = false
                    progress = ""
                    message = "Dihentikan."
                    turns = AiGemini.conversation.countFor(state)
                }) { Text("Hentikan", color = Am.Accent) }
            } else {
                TextButton({ onClose() }) { Text("Tutup", color = Am.Accent) }
            }
        },
        containerColor = Am.Field,
    )
}
