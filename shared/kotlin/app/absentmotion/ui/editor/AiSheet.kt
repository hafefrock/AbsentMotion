package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.AiGemini
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

private const val AI_PREFS = "ai_gemini"
private const val AI_KEY = "api_key"

@Composable
internal fun AiAssistantOverlay(state: EditorState) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier.padding(end = 4.dp).size(38.dp).clip(CircleShape).background(Am.Accent).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("AI", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (open) AiAssistantDialog(state) { open = false }
}

@Composable
private fun AiAssistantDialog(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AI_PREFS, android.content.Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString(AI_KEY, "").orEmpty()) }
    var editingKey by remember { mutableStateOf(key.isBlank()) }
    var request by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf<List<String>>(emptyList()) }
    var skipped by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
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
                if (editingKey) {
                    Text("Tempel API key Gemini (dari Google AI Studio). Kunci disimpan hanya di HP ini.", color = Am.OnDark, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.trim() },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("API key") },
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it },
                        minLines = 3,
                        label = { Text("Apa yang ingin diubah?") },
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        Text("Memproses...", color = Am.Accent, fontSize = 14.sp)
                    }
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = Am.OnDark, fontSize = 14.sp)
                    }
                    for (line in applied) Text("✓ $line", color = Am.OnDark, fontSize = 13.sp)
                    for ((name, reason) in skipped) Text("✗ $name: $reason", color = Am.OnDark.copy(alpha = 0.7f), fontSize = 13.sp)
                    TextButton({ editingKey = true }) { Text("Ubah API key", color = Am.Accent, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            if (editingKey) {
                TextButton(
                    onClick = {
                        prefs.edit().putString(AI_KEY, key).apply()
                        editingKey = false
                    },
                    enabled = key.isNotBlank(),
                ) { Text("Simpan", color = Am.Accent) }
            } else {
                TextButton(
                    onClick = {
                        val text = request.trim()
                        state.playing = false
                        busy = true
                        message = ""
                        applied = emptyList()
                        skipped = emptyList()
                        scope.launch {
                            val result = AiGemini.run(state, key, text)
                            busy = false
                            message = result.galat ?: result.pesan
                            val outcome = result.hasil
                            if (outcome != null) {
                                applied = outcome.diterapkan
                                skipped = outcome.dilewati
                                if (outcome.dilewati.isEmpty()) request = ""
                            }
                        }
                    },
                    enabled = !busy && request.isNotBlank(),
                ) { Text("Kirim", color = Am.Accent) }
            }
        },
        dismissButton = {
            TextButton({ if (!busy) onClose() }) { Text("Tutup", color = Am.Accent) }
        },
        containerColor = Am.Field,
    )
}
