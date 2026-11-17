package com.minhaia.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFB388FF),
                    secondary = Color(0xFF18FFFF)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val engine = remember { PlaceholderEngine() }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val conversations = remember {
        mutableStateListOf<Conversation>().apply {
            addAll(Store.load(ctx).sortedByDescending { it.updatedAt })
        }
    }
    val pending = remember { mutableStateListOf<Attachment>() }

    var currentId by remember { mutableStateOf(System.currentTimeMillis()) }
    var defaultModel by remember { mutableStateOf(prefs.defaultModel) }
    var draftModel by remember { mutableStateOf(prefs.defaultModel) }
    var input by remember { mutableStateOf("") }
    var thinkingId by remember { mutableStateOf<Long?>(null) }
    var importing by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf("chat") }
    var textScale by remember { mutableStateOf(prefs.textScale) }
    var instructions by remember { mutableStateOf(prefs.instructions) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val current = conversations.firstOrNull { it.id == currentId }
    val model = current?.model ?: draftModel

    fun upsert(c: Conversation) {
        val i = conversations.indexOfFirst { it.id == c.id }
        if (i >= 0) conversations[i] = c else conversations.add(0, c)
        Store.save(ctx, conversations.toList())
    }

    fun discardPending() {
        pending.forEach { File(it.path).delete() }
        pending.clear()
    }

    fun newChat() {
        discardPending()
        input = ""
        currentId = System.currentTimeMillis()
        draftModel = defaultModel
        scope.launch { drawerState.close() }
    }

    fun openChat(id: Long) {
        discardPending()
        input = ""
        currentId = id
        scope.launch { drawerState.close() }
    }

    fun setModel(m: AiModel) {
        if (current != null) upsert(current.copy(model = m)) else draftModel = m
    }

    fun deleteChat(id: Long) {
        conversations.removeAll { it.id == id }
        FileUtil.deleteConv(ctx, id)
        Store.save(ctx, conversations.toList())
        if (currentId == id) {
            pending.clear()
            input = ""
            currentId = System.currentTimeMillis()
            draftModel = defaultModel
        }
    }

    fun clearAll() {
        conversations.clear()
        pending.clear()
        FileUtil.deleteAll(ctx)
        Store.save(ctx, emptyList())
        input = ""
        currentId = System.currentTimeMillis()
        draftModel = defaultModel
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() && pending.isEmpty()) return
        if (thinkingId != null || importing) return

        val files = pending.toList()
        val base = current ?: Conversation(currentId, "Nova conversa", draftModel, emptyList(), System.currentTimeMillis())
        val title = if (base.messages.isEmpty()) {
            text.ifEmpty { files.firstOrNull()?.name?.substringAfterLast('/') ?: "Arquivos" }.take(32)
        } else base.title

        val userMsg = Msg(System.nanoTime(), text, true, null, files)
        val withUser = base.copy(
            title = title,
            messages = base.messages + userMsg,
            updatedAt = System.currentTimeMillis()
        )
        upsert(withUser)
        input = ""
        pending.clear()
        thinkingId = withUser.id

        scope.launch {
            try {
                val answer = engine.reply(withUser.model, withUser.messages, instructions)
                val latest = conversations.firstOrNull { it.id == withUser.id } ?: withUser
                val aiMsg = Msg(System.nanoTime(), answer, false, withUser.model, emptyList())
                upsert(latest.copy(messages = latest.messages + aiMsg, updatedAt = System.currentTimeMillis()))
            } finally {
                thinkingId = null
            }
        }
    }

    // ---------- seletores de arquivo ----------
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                val list = withContext(Dispatchers.IO) {
                    uris.mapNotNull { FileUtil.copyUri(ctx, it, currentId) }
                }
                pending.addAll(list)
                importing = false
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                val list = withContext(Dispatchers.IO) {
                    FileUtil.importTree(ctx, uri, currentId)
                }
                pending.addAll(list)
                importing = false
            }
        }
    }

    BackHandler(enabled = screen == "settings") { screen = "chat" }

    if (screen == "settings") {
        SettingsScreen(
            defaultModel = defaultModel,
            onDefaultModel = {
                defaultModel = it
                prefs.defaultModel = it
                if (current == null) draftModel = it
            },
            instructions = instructions,
            onInstructions = {
                instructions = it
                prefs.instructions = it
            },
            textScale = textScale,
            onTextScale = {
                textScale = it
                prefs.textScale = it
            },
            onBack = { screen = "chat" },
            onClearAll = { confirmClearAll = true }
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    DrawerContent(
                        conversations = conversations,
                        currentId = currentId,
                        onNew = { newChat() },
                        onOpen = { openChat(it) },
                        onDelete = { confirmDeleteId = it },
                        onSettings = {
                            screen = "settings"
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            },
            content = {
                ChatScreen(
                    messages = current?.messages ?: emptyList(),
                    model = model,
                    textScale = textScale,
                    input = input,
                    onInput = { input = it },
                    pending = pending,
                    thinking = thinkingId != null && thinkingId == currentId,
                    busy = thinkingId != null || importing,
                    importing = importing,
                    onMenu = { scope.launch { drawerState.open() } },
                    onModel = { setModel(it) },
                    onSend = { send() },
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onPickFolder = { pickFolder.launch(null) },
                    onRemovePending = {
                        File(it.path).delete()
                        pending.remove(it)
                    }
                )
            }
        )
    }

    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Apagar conversa?") },
            text = { Text("Ela e os anexos dela serão apagados.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteChat(id)
                    confirmDeleteId = null
                }) { Text("Apagar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancelar") }
            }
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Apagar tudo?") },
            text = { Text("Todas as conversas e anexos serão apagados.") },
            confirmButton = {
                TextButton(onClick = {
                    clearAll()
                    confirmClearAll = false
                }) { Text("Apagar tudo") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
fun ChatScreen(
    messages: List<Msg>,
    model: AiModel,
    textScale: Float,
    input: String,
    onInput: (String) -> Unit,
    pending: List<Attachment>,
    thinking: Boolean,
    busy: Boolean,
    importing: Boolean,
    onMenu: () -> Unit,
    onModel: (AiModel) -> Unit,
    onSend: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onRemovePending: (Attachment) -> Unit
) {
    val listState = rememberLazyListState()
    var modelMenu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val fontSize = (16 * textScale).sp

    LaunchedEffect(messages.size, thinking) {
        val count = messages.size + if (thinking) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onMenu) { Text("☰", fontSize = 22.sp) }
            Text(
                text = "Nexa",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Box {
                TextButton(onClick = { modelMenu = true }) {
                    Text("${model.emoji} ${model.label} ▾")
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    AiModel.values().forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.emoji} ${m.label} — ${m.desc}") },
                            onClick = {
                                onModel(m)
                                modelMenu = false
                            }
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("✨", fontSize = 48.sp)
                    Text("Oi! Eu sou a Nexa", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Manda uma mensagem ou anexa arquivos do seu projeto.",
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { msg -> Bubble(msg, fontSize) }
                if (thinking) {
                    item {
                        Text("Pensando...", color = Color.Gray, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }

        if (importing) {
            Text(
                "Importando arquivos...",
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (pending.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(pending) { a ->
                    Text(
                        text = "📎 ${a.name.substringAfterLast('/')}  ✕",
                        maxLines = 1,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color(0xFF2A2A3D), RoundedCornerShape(12.dp))
                            .clickable { onRemovePending(a) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Text(
                "${pending.size} arquivo(s) prontos para enviar",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box {
                TextButton(onClick = { attachMenu = true }) { Text("📎", fontSize = 22.sp) }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Arquivos") },
                        onClick = {
                            attachMenu = false
                            onPickFiles()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Pasta inteira (projeto)") },
                        onClick = {
                            attachMenu = false
                            onPickFolder()
                        }
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensagem") },
                maxLines = 5
            )
            Button(onClick = onSend, enabled = !busy) { Text("Enviar") }
        }
    }
}

@Composable
fun Bubble(msg: Msg, fontSize: TextUnit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (msg.fromUser) Color(0xFF4527A0) else Color(0xFF37474F),
                    RoundedCornerShape(16.dp)
                )
                .padding(12.dp)
        ) {
            val m = msg.model
            if (!msg.fromUser && m != null) {
                Text("${m.emoji} ${m.label}", color = Color(0xFFB0BEC5), fontSize = 12.sp)
            }
            if (msg.text.isNotEmpty()) {
                Text(msg.text, color = Color.White, fontSize = fontSize)
            }
            msg.attachments.take(6).forEach {
                Text(
                    "📎 ${it.name} (${formatSize(it.size)})",
                    color = Color(0xFFCFD8DC),
                    fontSize = 12.sp
                )
            }
            if (msg.attachments.size > 6) {
                Text(
                    "+${msg.attachments.size - 6} arquivos",
                    color = Color(0xFFCFD8DC),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun DrawerContent(
    conversations: List<Conversation>,
    currentId: Long,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("Nexa", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("＋ Nova conversa") }
        Spacer(Modifier.height(12.dp))
        Text("Conversas salvas", color = Color.Gray, fontSize = 13.sp)
        if (conversations.isEmpty()) {
            Text("Nenhuma ainda.", modifier = Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(conversations, key = { it.id }) { c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (c.id == currentId) Color(0x33B388FF) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onOpen(c.id) }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${c.model.emoji} ${c.title}",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { onDelete(c.id) }) { Text("🗑") }
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("⚙ Configurações")
        }
    }
}

@Composable
fun SettingsScreen(
    defaultModel: AiModel,
    onDefaultModel: (AiModel) -> Unit,
    instructions: String,
    onInstructions: (String) -> Unit,
    textScale: Float,
    onTextScale: (Float) -> Unit,
    onBack: () -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← Voltar") }
        Text("Configurações", style = MaterialTheme.typography.titleLarge)

        Text("Modelo padrão (novas conversas)", style = MaterialTheme.typography.titleMedium)
        AiModel.values().forEach { m ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onDefaultModel(m) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = defaultModel == m, onClick = { onDefaultModel(m) })
                Column {
                    Text("${m.emoji} ${m.label}")
                    Text(m.desc, color = Color.Gray, fontSize = 12.sp)
                }
            }
        }

        HorizontalDivider()
        Text("Instruções personalizadas", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = instructions,
            onValueChange = onInstructions,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ex: responda sempre em português") },
            minLines = 3
        )

        HorizontalDivider()
        Text(
            "Tamanho do texto: ${(textScale * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium
        )
        Slider(value = textScale, onValueChange = onTextScale, valueRange = 0.8f..1.5f)

        HorizontalDivider()
        Button(
            onClick = onClearAll,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
        ) { Text("Apagar todas as conversas") }

        Text(
            "Nexa v0.2 • modelos ainda em modo esqueleto (sem cérebro real)",
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}
