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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.AiConfig
import app.absentmotion.ai.AiGemini
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

@Composable
private fun aiFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Am.Navy,
    unfocusedTextColor = Am.Navy,
    cursorColor = Am.HomeAccent,
    focusedBorderColor = Am.HomeAccent,
    unfocusedBorderColor = Am.Navy.copy(alpha = 0.4f),
    focusedLabelColor = Am.HomeAccent,
    unfocusedLabelColor = Am.Navy.copy(alpha = 0.7f),
)

@Composable
internal fun AiSettingsRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(AiConfig.apiKey(context)) }
    var model by remember { mutableStateOf(AiConfig.model(context)) }
    var thinking by remember { mutableStateOf(AiConfig.thinking(context)) }
    var thinkingDialog by remember { mutableStateOf(false) }
    var vision by remember { mutableStateOf(AiConfig.vision(context)) }
    var keyDialog by remember { mutableStateOf(false) }
    var modelDialog by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }

    ActionRow("API key Gemini", if (apiKey.isBlank()) "Belum diisi" else "••••" + apiKey.takeLast(4)) { keyDialog = true }
    Divider(10.dp)
    ActionRow("Model", model) { modelDialog = true }
    Divider(10.dp)
    ActionRow("Tingkat berpikir", AiConfig.thinkingLabel(thinking)) { thinkingDialog = true }
    Divider(10.dp)
    ActionRow("Lihat hasil (tangkapan frame)", if (vision) "Aktif" else "Mati", chevron = false) {
        vision = !vision
        AiConfig.setVision(context, vision)
    }
    Divider(10.dp)
    ActionRow("Tes koneksi", chevron = false, busy = testing) {
        if (!testing) {
            if (apiKey.isBlank()) {
                testResult = "Isi API key dulu."
            } else {
                testing = true
                testResult = ""
                scope.launch {
                    testResult = AiGemini.test(apiKey, model)
                    testing = false
                }
            }
        }
    }
    if (testResult.isNotBlank()) {
        Text(testResult, Modifier.padding(horizontal = 15.dp, vertical = 4.dp), color = Am.HomeMid, fontSize = 12.sp, lineHeight = 16.sp)
    }

    if (keyDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text("API key Gemini", color = Am.Navy) },
            text = {
                Column {
                    Text("Buat kunci gratis di aistudio.google.com/apikey, lalu tempel di sini. Kunci hanya disimpan di HP ini.", color = Am.Navy, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.trim() },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(if (apiKey.isBlank()) "API key" else "API key baru") },
                        colors = aiFieldColors(),
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
                        AiConfig.setApiKey(context, text)
                        apiKey = AiConfig.apiKey(context)
                        keyDialog = false
                    },
                    enabled = text.isNotBlank(),
                ) { Text("Simpan", color = Am.Navy) }
            },
            dismissButton = {
                Row {
                    if (apiKey.isNotBlank()) TextButton({
                        AiConfig.setApiKey(context, "")
                        apiKey = ""
                        keyDialog = false
                    }) { Text("Hapus kunci", color = Am.Navy) }
                    TextButton({ keyDialog = false }) { Text("Batal", color = Am.Navy) }
                }
            },
            containerColor = Am.Card,
        )
    }

    if (modelDialog) {
        var list by remember { mutableStateOf(AiConfig.models(context)) }
        var choice by remember { mutableStateOf(model) }
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { modelDialog = false },
            title = { Text("Model AI", color = Am.Navy) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (name in list) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { choice = name },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = choice == name,
                                onClick = { choice = name },
                                colors = RadioButtonDefaults.colors(selectedColor = Am.HomeAccent, unselectedColor = Am.Navy),
                            )
                            Text(name, color = Am.Navy, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                            if (list.size > 1) TextButton({
                                AiConfig.removeModel(context, name)
                                list = AiConfig.models(context)
                                if (choice == name) choice = list.first()
                            }) { Text("Hapus", color = Am.Navy, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Tambah model baru") },
                        colors = aiFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            val added = AiConfig.addModel(context, newName)
                            if (added.isNotBlank()) {
                                list = AiConfig.models(context)
                                choice = added
                                newName = ""
                            }
                        },
                        enabled = AiConfig.cleanModel(newName).isNotBlank(),
                    ) { Text("Tambah", color = Am.Navy) }
                }
            },
            confirmButton = {
                TextButton({
                    AiConfig.setModel(context, choice)
                    model = AiConfig.model(context)
                    modelDialog = false
                }) { Text("Simpan", color = Am.Navy) }
            },
            dismissButton = { TextButton({ modelDialog = false }) { Text("Tutup", color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }

    if (thinkingDialog) {
        var choice by remember { mutableStateOf(thinking) }
        AlertDialog(
            onDismissRequest = { thinkingDialog = false },
            title = { Text("Tingkat berpikir", color = Am.Navy) },
            text = {
                Column {
                    Text("Makin tinggi, makin teliti merencanakan, tapi lebih lambat. Tidak semua model mendukung semua tingkat.", color = Am.Navy, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    for ((level, label) in AiConfig.thinkingChoices) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { choice = level },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = choice == level,
                                onClick = { choice = level },
                                colors = RadioButtonDefaults.colors(selectedColor = Am.HomeAccent, unselectedColor = Am.Navy),
                            )
                            Text(label, color = Am.Navy, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    AiConfig.setThinking(context, choice)
                    thinking = AiConfig.thinking(context)
                    thinkingDialog = false
                }) { Text("Simpan", color = Am.Navy) }
            },
            dismissButton = { TextButton({ thinkingDialog = false }) { Text("Tutup", color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}
