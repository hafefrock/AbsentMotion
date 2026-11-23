package app.absentmotion.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.AiConfig
import app.absentmotion.ai.AiOpenAi
import app.absentmotion.ai.AiRemoteConfig
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun remoteFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Am.Navy,
    unfocusedTextColor = Am.Navy,
    cursorColor = Am.HomeAccent,
    focusedBorderColor = Am.HomeAccent,
    unfocusedBorderColor = Am.Navy.copy(alpha = 0.4f),
    focusedLabelColor = Am.HomeAccent,
    unfocusedLabelColor = Am.Navy.copy(alpha = 0.7f),
)

/** Baris pemilih penyedia AI (Gemini atau penyedia lain yang kompatibel OpenAI). */
@Composable
internal fun AiProviderRow(provider: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(false) }
    ActionRow("Penyedia AI", AiRemoteConfig.label(provider)) { dialog = true }
    if (dialog) {
        val ids = listOf(AiRemoteConfig.GEMINI) + AiRemoteConfig.presets.map { it.id }
        val pick = { id: String ->
            AiRemoteConfig.setProvider(context, id)
            onChange(id)
            dialog = false
        }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Penyedia AI", color = Am.Navy) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Pilih Gemini atau penyedia lain yang punya jatah gratis. Kunci API hanya disimpan di HP ini.", color = Am.Navy, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    for (id in ids) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { pick(id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = provider == id,
                                onClick = { pick(id) },
                                colors = RadioButtonDefaults.colors(selectedColor = Am.HomeAccent, unselectedColor = Am.Navy),
                            )
                            Text(AiRemoteConfig.label(id), color = Am.Navy, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Catatan: paket gratis sebagian penyedia bisa memakai isi prompt untuk melatih model. Jangan kirim proyek yang rahasia.",
                        color = Am.Navy.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = { TextButton({ dialog = false }) { Text("Tutup", color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}

/** Pengaturan untuk penyedia non-Gemini: API key, model, alamat (khusus Kustom), tes koneksi. */
@Composable
internal fun AiRemoteRows(id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preset = AiRemoteConfig.preset(id)
    val label = AiRemoteConfig.label(id)
    var apiKey by remember(id) { mutableStateOf(AiRemoteConfig.apiKey(context, id)) }
    var model by remember(id) { mutableStateOf(AiRemoteConfig.model(context, id)) }
    var baseUrl by remember(id) { mutableStateOf(AiRemoteConfig.baseUrl(context, id)) }
    var vision by remember { mutableStateOf(AiConfig.vision(context)) }
    var slim by remember { mutableStateOf(AiRemoteConfig.slimMode(context)) }
    var dialog by remember(id) { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember(id) { mutableStateOf("") }

    ActionRow("API key $label", if (apiKey.isBlank()) "Belum diisi" else "••••" + apiKey.takeLast(4)) { dialog = "key" }
    Divider(10.dp)
    ActionRow("Model", model.ifBlank { "Belum diisi" }) { dialog = "model" }
    Divider(10.dp)
    if (id == "custom") {
        ActionRow("Alamat server", baseUrl.ifBlank { "Belum diisi" }) { dialog = "url" }
        Divider(10.dp)
    }
    ActionRow("Lihat hasil (tangkapan frame)", if (vision) "Aktif" else "Mati", chevron = false) {
        vision = !vision
        AiConfig.setVision(context, vision)
    }
    Divider(10.dp)
    ActionRow("Prompt ringan (hemat token)", if (slim == "on") "Selalu" else if (slim == "off") "Mati" else "Otomatis", chevron = false) {
        slim = if (slim == "auto") "on" else if (slim == "on") "off" else "auto"
        AiRemoteConfig.setSlimMode(context, slim)
    }
    Divider(10.dp)
    ActionRow("Tes koneksi", chevron = false, busy = testing) {
        if (!testing) {
            val remote = AiRemoteConfig.active(context)
            if (remote == null || remote.apiKey.isBlank()) {
                testResult = "Isi API key dulu."
            } else {
                testing = true
                testResult = ""
                scope.launch {
                    testResult = withContext(Dispatchers.IO) { AiOpenAi.test(remote) }
                    testing = false
                }
            }
        }
    }
    if (testResult.isNotBlank()) {
        Text(testResult, Modifier.padding(horizontal = 15.dp, vertical = 4.dp), color = Am.HomeMid, fontSize = 12.sp, lineHeight = 16.sp)
    }
    Text(
        "\"Lihat hasil\" hanya dipakai kalau modelnya mendukung gambar. Kalau tidak, aplikasi otomatis mengirim teks saja. \"Prompt ringan\" memangkas daftar efek (sekitar separuh ukuran prompt) dan memakai tanya-aplikasi; Otomatis hanya untuk Groq, yang batas tokennya kecil.",
        Modifier.padding(horizontal = 15.dp, vertical = 4.dp),
        color = Am.HomeMid,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

    when (dialog) {
        "key" -> RemoteTextDialog(
            title = "API key $label",
            info = if (preset != null && preset.keyPage.isNotBlank()) "Buat kunci gratis di ${preset.keyPage}, lalu tempel di sini. Kunci hanya disimpan di HP ini." else "Tempel API key dari penyedia. Kunci hanya disimpan di HP ini.",
            fieldLabel = if (apiKey.isBlank()) "API key" else "API key baru",
            initial = "",
            secret = true,
            onSave = {
                AiRemoteConfig.setApiKey(context, id, it)
                apiKey = AiRemoteConfig.apiKey(context, id)
            },
            onClose = { dialog = "" },
            onClear = if (apiKey.isNotBlank()) {
                {
                    AiRemoteConfig.setApiKey(context, id, "")
                    apiKey = ""
                }
            } else null,
        )
        "model" -> RemoteTextDialog(
            title = "Model $label",
            info = "Pilih dari saran, atau tulis nama model persis seperti di dokumentasi penyedia. Nama model gratis sering berganti, jadi ganti di sini kalau muncul galat \"model tidak ditemukan\".",
            fieldLabel = "Nama model",
            initial = model,
            secret = false,
            onSave = {
                AiRemoteConfig.setModel(context, id, it)
                model = AiRemoteConfig.model(context, id)
            },
            onClose = { dialog = "" },
            onClear = null,
            suggestions = preset?.saran.orEmpty(),
        )
        "url" -> RemoteTextDialog(
            title = "Alamat server",
            info = "Alamat dasar yang kompatibel OpenAI, tanpa /chat/completions. Contoh: https://api.groq.com/openai/v1",
            fieldLabel = "Alamat server",
            initial = baseUrl,
            secret = false,
            onSave = {
                AiRemoteConfig.setBaseUrl(context, id, it)
                baseUrl = AiRemoteConfig.baseUrl(context, id)
            },
            onClose = { dialog = "" },
            onClear = null,
        )
    }
}

@Composable
private fun RemoteTextDialog(
    title: String,
    info: String,
    fieldLabel: String,
    initial: String,
    secret: Boolean,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    onClear: (() -> Unit)?,
    suggestions: List<String> = emptyList(),
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, color = Am.Navy) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(info, color = Am.Navy, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                if (suggestions.isNotEmpty()) {
                    Text("Saran model (ketuk untuk memilih):", color = Am.Navy, fontSize = 12.sp)
                    for (suggestion in suggestions) {
                        TextButton({ text = suggestion }) { Text(suggestion, color = Am.HomeAccent, fontSize = 13.sp) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    singleLine = true,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    label = { Text(fieldLabel) },
                    colors = remoteFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton({
                    val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                    val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty().trim()
                    if (pasted.isNotEmpty()) text = pasted
                }) { Text("Tempel dari clipboard", color = Am.Navy, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(text)
                    onClose()
                },
                enabled = text.isNotBlank(),
            ) { Text("Simpan", color = Am.Navy) }
        },
        dismissButton = {
            Row {
                if (onClear != null) TextButton({
                    onClear()
                    onClose()
                }) { Text("Hapus", color = Am.Navy) }
                TextButton(onClose) { Text("Batal", color = Am.Navy) }
            }
        },
        containerColor = Am.Card,
    )
}
