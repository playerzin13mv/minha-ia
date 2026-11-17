package com.minhaia.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class AiModel(val label: String, val emoji: String, val desc: String) {
    RAPIDO("Rápido", "⚡", "Respostas curtas e diretas"),
    RACIOCINIO("Raciocínio", "🧠", "Pensa passo a passo"),
    PROGRAMACAO("Programação", "💻", "Foco em código e projetos")
}

data class Attachment(val name: String, val path: String, val size: Long, val isText: Boolean)

data class Msg(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val model: AiModel?,
    val attachments: List<Attachment>
)

data class Conversation(
    val id: Long,
    val title: String,
    val model: AiModel,
    val messages: List<Msg>,
    val updatedAt: Long
)

fun modelFrom(s: String?): AiModel =
    runCatching { AiModel.valueOf(s ?: "") }.getOrDefault(AiModel.RAPIDO)

fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b / 1048576.0)
}

// ---------- Configurações ----------
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nexa", Context.MODE_PRIVATE)

    var defaultModel: AiModel
        get() = modelFrom(sp.getString("model", "RAPIDO"))
        set(v) {
            sp.edit().putString("model", v.name).apply()
        }

    var instructions: String
        get() = sp.getString("instr", "") ?: ""
        set(v) {
            sp.edit().putString("instr", v).apply()
        }

    var textScale: Float
        get() = sp.getFloat("scale", 1f)
        set(v) {
            sp.edit().putFloat("scale", v).apply()
        }
}

// ---------- Conversas salvas ----------
object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "conversas.json")

    fun load(ctx: Context): List<Conversation> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { convFrom(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, list: List<Conversation>) {
        val arr = JSONArray()
        list.forEach { arr.put(convTo(it)) }
        file(ctx).writeText(arr.toString())
    }

    private fun convTo(c: Conversation): JSONObject {
        val o = JSONObject()
        o.put("id", c.id)
        o.put("title", c.title)
        o.put("model", c.model.name)
        o.put("updatedAt", c.updatedAt)
        val ms = JSONArray()
        c.messages.forEach { ms.put(msgTo(it)) }
        o.put("messages", ms)
        return o
    }

    private fun msgTo(m: Msg): JSONObject {
        val o = JSONObject()
        o.put("id", m.id)
        o.put("text", m.text)
        o.put("fromUser", m.fromUser)
        o.put("model", m.model?.name ?: "")
        val arr = JSONArray()
        m.attachments.forEach { a ->
            val ao = JSONObject()
            ao.put("name", a.name)
            ao.put("path", a.path)
            ao.put("size", a.size)
            ao.put("isText", a.isText)
            arr.put(ao)
        }
        o.put("attachments", arr)
        return o
    }

    private fun convFrom(o: JSONObject): Conversation {
        val ms = o.getJSONArray("messages")
        val msgs = (0 until ms.length()).map { msgFrom(ms.getJSONObject(it)) }
        return Conversation(
            o.getLong("id"),
            o.getString("title"),
            modelFrom(o.optString("model")),
            msgs,
            o.optLong("updatedAt")
        )
    }

    private fun msgFrom(o: JSONObject): Msg {
        val arr = o.optJSONArray("attachments") ?: JSONArray()
        val atts = (0 until arr.length()).map {
            val a = arr.getJSONObject(it)
            Attachment(a.getString("name"), a.getString("path"), a.getLong("size"), a.getBoolean("isText"))
        }
        val modelStr = o.optString("model")
        return Msg(
            o.getLong("id"),
            o.getString("text"),
            o.getBoolean("fromUser"),
            if (modelStr.isEmpty()) null else modelFrom(modelStr),
            atts
        )
    }
}

// ---------- Arquivos anexados ----------
object FileUtil {
    private val textExt = setOf(
        "gd", "tscn", "tres", "godot", "cfg", "import", "gdshader", "shader", "glsl",
        "lua", "love", "c", "cpp", "h", "hpp", "cs", "kt", "kts", "java", "py", "js", "ts",
        "json", "txt", "md", "xml", "ini", "sh", "gradle", "yml", "yaml", "toml", "csv",
        "html", "css", "cmake", "mk", "gitignore", "properties", "sln", "vcxproj", "rs", "go"
    )
    private val skipDirs = setOf(".git", ".godot", ".gradle", "build", ".idea", "node_modules")

    fun isText(name: String): Boolean {
        val n = name.substringAfterLast('/').lowercase()
        val ext = n.substringAfterLast('.', "")
        return ext in textExt || n in setOf("makefile", "cmakelists.txt", "readme", "license")
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment ?: "arquivo"
    }

    private fun baseDir(ctx: Context, convId: Long): File {
        val d = File(ctx.filesDir, "anexos/$convId")
        d.mkdirs()
        return d
    }

    fun deleteConv(ctx: Context, convId: Long) {
        File(ctx.filesDir, "anexos/$convId").deleteRecursively()
    }

    fun deleteAll(ctx: Context) {
        File(ctx.filesDir, "anexos").deleteRecursively()
    }

    fun copyUri(ctx: Context, uri: Uri, convId: Long, relPath: String? = null): Attachment? {
        return try {
            val name = relPath ?: displayName(ctx, uri).replace('/', '_')
            var dest = File(baseDir(ctx, convId), name)
            dest.parentFile?.mkdirs()
            if (dest.exists()) {
                dest = File(dest.parentFile, "${System.currentTimeMillis()}_${dest.name}")
            }
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            Attachment(name, dest.absolutePath, dest.length(), isText(name))
        } catch (e: Exception) {
            null
        }
    }

    fun importTree(ctx: Context, tree: Uri, convId: Long): List<Attachment> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val result = mutableListOf<Attachment>()

        fun walk(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val n = f.name ?: continue
                if (f.isDirectory) {
                    if (n !in skipDirs) walk(f, "$prefix$n/")
                } else {
                    val a = copyUri(ctx, f.uri, convId, "$prefix$n")
                    if (a != null) result.add(a)
                }
            }
        }

        walk(root, (root.name ?: "projeto") + "/")
        return result
    }
}

// ---------- Motor da IA (provisório) ----------
interface AiEngine {
    suspend fun reply(model: AiModel, history: List<Msg>, instructions: String): String
}

class PlaceholderEngine : AiEngine {
    override suspend fun reply(model: AiModel, history: List<Msg>, instructions: String): String {
        val last = history.lastOrNull { it.fromUser }
        val files = last?.attachments ?: emptyList()
        val info = withContext(Dispatchers.IO) { describe(files) }
        val said = last?.text ?: ""
        return when (model) {
            AiModel.RAPIDO -> {
                delay(400)
                "⚡ Modo Rápido\nVocê disse: $said$info"
            }
            AiModel.RACIOCINIO -> {
                delay(1200)
                "🧠 Modo Raciocínio\n1) Li sua mensagem.\n2) Analisei o contexto.\n3) Ainda sou só o esqueleto, sem cérebro real.\n\nVocê disse: $said$info"
            }
            AiModel.PROGRAMACAO -> {
                delay(800)
                "💻 Modo Programação\nVocê disse: $said$info"
            }
        }
    }

    private fun describe(files: List<Attachment>): String {
        if (files.isEmpty()) return ""
        val text = files.filter { it.isText }
        var lines = 0
        text.forEach { a ->
            runCatching { File(a.path).useLines { s -> lines += s.count() } }
        }
        return "\n\n📎 Recebi ${files.size} arquivo(s), ${text.size} de texto/código (~$lines linhas)."
    }
}
