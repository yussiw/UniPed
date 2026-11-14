package br.uniped.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.StatFs
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Gerenciador de arquivos do UniPed.
 * Guarda tudo numa pasta própria do app (não precisa de permissão de armazenamento):
 * criar pasta, criar/editar texto, renomear, copiar, mover, excluir, baixar por link e abrir arquivos.
 */
class FilesActivity : Activity() {

    companion object {
        private val TEXT_EXT = setOf(
            "txt", "md", "log", "json", "xml", "csv", "ini", "conf", "cfg", "properties",
            "sh", "yml", "yaml", "html", "htm", "js", "css", "kt", "java", "py",
            "m3u", "m3u8", "srt", "list"
        )
        private const val MAX_TEXT = 512 * 1024L

        /** Pasta raiz do armazenamento do UniPed. */
        fun rootDir(ctx: Context): File {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val r = File(base, "UniPed")
            if (!r.exists()) {
                r.mkdirs()
                for (n in listOf("Downloads", "Documentos", "Imagens", "Videos", "Musicas")) {
                    File(r, n).mkdirs()
                }
            }
            return r
        }
    }

    private lateinit var root: File
    private lateinit var cwd: File
    private lateinit var adapter: FileAdapter
    private lateinit var list: RecyclerView
    private lateinit var sounds: UiSounds
    private val io = Executors.newSingleThreadExecutor()

    private var clip: File? = null
    private var clipCut = false
    @Volatile private var busy = false
    @Volatile private var downloading = false
    @Volatile private var cancelFlag = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        setContentView(R.layout.activity_files)
        window.setBackgroundDrawable(null)
        hideSystemBars()
        sounds = UiSounds(this)

        root = rootDir(this)
        cwd = root

        adapter = FileAdapter({ openFile(it) }, { showActions(it) }, { sounds.tick() })
        list = findViewById(R.id.fmList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.itemAnimator = null
        list.overScrollMode = View.OVER_SCROLL_NEVER

        bind(R.id.fmUp) { if (!goUp()) toast("Você já está na pasta raiz") }
        bind(R.id.fmNewFolder) { newFolder() }
        bind(R.id.fmNewFile) { newFile() }
        bind(R.id.fmDownload) { askDownload() }
        bind(R.id.fmPaste) { paste() }
        bind(R.id.fmRefresh) { refresh() }

        findViewById<View>(R.id.fmStatus).setOnClickListener {
            if (downloading) {
                cancelFlag = true
                toast("Cancelando…")
            }
        }

        refresh()
    }

    private fun bind(id: Int, action: () -> Unit) {
        val v = findViewById<View>(id)
        v.setOnClickListener { action() }
        popOnFocus(v, 1.06f) { sounds.tick() }
    }

    // ---------- Janela ----------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onDestroy() {
        if (isFinishing) cancelFlag = true
        io.shutdown()
        sounds.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!goUp()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // Botão MENU do controle abre as opções do item em foco
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val f = currentFocus?.tag as? File
            if (f != null) showActions(f) else toast("Selecione um arquivo ou pasta")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ---------- Navegação / listagem ----------

    private fun goUp(): Boolean {
        if (cwd.canonicalPath == root.canonicalPath) return false
        val parent = cwd.parentFile ?: return false
        val prev = cwd.name
        cwd = parent
        refresh(prev)
        return true
    }

    private fun enter(dir: File) {
        cwd = dir
        refresh()
    }

    private fun pathLabel(): String {
        val rel = try {
            cwd.relativeTo(root).path
        } catch (_: Exception) {
            ""
        }
        return if (rel.isEmpty()) "Meus arquivos" else "Meus arquivos / " + rel.replace(File.separatorChar, '/')
    }

    private fun refresh(focusName: String? = null, moveFocus: Boolean = true) {
        val files = (cwd.listFiles() ?: emptyArray<File>()).sortedWith(
            compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
        )
        adapter.submit(files)
        findViewById<TextView>(R.id.fmPath).text = pathLabel()
        findViewById<View>(R.id.fmEmpty).visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fmPaste).visibility = if (clip != null) View.VISIBLE else View.GONE
        updateStorage()
        if (!moveFocus) return

        var idx = 0
        if (focusName != null) {
            val found = files.indexOfFirst { it.name == focusName }
            if (found >= 0) idx = found
        }
        if (files.isNotEmpty()) list.scrollToPosition(idx)
        list.post {
            val target = list.findViewHolderForAdapterPosition(idx)?.itemView
            if (target != null) target.requestFocus()
            else findViewById<View>(R.id.fmNewFolder).requestFocus()
        }
    }

    private fun updateStorage() {
        if (io.isShutdown) return
        val tv = findViewById<TextView>(R.id.fmStorage)
        val free = try {
            StatFs(root.path).availableBytes
        } catch (_: Exception) {
            0L
        }
        val base = "Livre: " + Formatter.formatFileSize(this, free)
        tv.text = base
        io.execute {
            val used = dirSize(root)
            runOnUiThread {
                if (!isDestroyed) {
                    tv.text = base + " · UniPed: " + Formatter.formatFileSize(this, used)
                }
            }
        }
    }

    private fun dirSize(f: File): Long {
        if (f.isFile) return f.length()
        var total = 0L
        f.listFiles()?.forEach { total += dirSize(it) }
        return total
    }

    // ---------- Operações ----------

    private fun validName(n: String): Boolean {
        val t = n.trim()
        return t.isNotEmpty() && t != "." && t != ".." && !t.contains('/') && t.length <= 120
    }

    private fun sanitize(n: String): String {
        val s = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (s.isEmpty() || s == "." || s == "..") "download" else s
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    private fun newFolder() {
        promptText("Nova pasta", "Nome da pasta", "", "Criar") { name ->
            if (!validName(name)) {
                toast("Nome inválido")
            } else {
                val f = File(cwd, name.trim())
                if (f.exists()) toast("Já existe um item com esse nome")
                else if (f.mkdirs()) refresh(f.name)
                else toast("Não foi possível criar a pasta")
            }
        }
    }

    private fun newFile() {
        promptFile("Novo arquivo de texto", "novo.txt", true, "") { name, text ->
            if (!validName(name)) {
                toast("Nome inválido")
            } else {
                val f = File(cwd, name.trim())
                if (f.exists()) {
                    toast("Já existe um item com esse nome")
                } else {
                    try {
                        f.writeText(text)
                        refresh(f.name)
                    } catch (_: Exception) {
                        toast("Não foi possível criar o arquivo")
                    }
                }
            }
        }
    }

    private fun editText(f: File) {
        if (f.length() > MAX_TEXT) {
            toast("Arquivo grande demais para editar aqui")
            return
        }
        val content = try {
            f.readText()
        } catch (_: Exception) {
            toast("Não foi possível ler o arquivo")
            return
        }
        promptFile("Editar: " + f.name, f.name, false, content) { _, text ->
            try {
                f.writeText(text)
                toast("Salvo")
                refresh(f.name, false)
            } catch (_: Exception) {
                toast("Não foi possível salvar")
            }
        }
    }

    private fun rename(f: File) {
        promptText("Renomear", "Novo nome", f.name, "Renomear") { name ->
            val parent = f.parentFile
            if (parent == null || !validName(name)) {
                toast("Nome inválido")
            } else {
                val t = File(parent, name.trim())
                if (t.exists()) toast("Já existe um item com esse nome")
                else if (f.renameTo(t)) refresh(t.name)
                else toast("Não foi possível renomear")
            }
        }
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Excluir")
            .setMessage("Excluir \"" + f.name + "\"" + (if (f.isDirectory) " e tudo dentro dela?" else "?"))
            .setPositiveButton("Excluir") { _, _ ->
                runBg("Excluindo…", { f.deleteRecursively() }) {
                    if (clip != null && clip == f) clip = null
                    refresh()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun paste() {
        val src = clip ?: return
        if (!src.exists()) {
            clip = null
            refresh()
            return
        }
        val dir = cwd
        if (src.isDirectory) {
            val s = src.canonicalPath
            val d = dir.canonicalPath
            if (d == s || d.startsWith(s + File.separator)) {
                toast("Não dá para colar uma pasta dentro dela mesma")
                return
            }
        }
        val cut = clipCut
        if (cut && src.parentFile?.canonicalPath == dir.canonicalPath) {
            toast("O item já está nesta pasta")
            return
        }
        val target = uniqueFile(dir, src.name)
        runBg(if (cut) "Movendo…" else "Copiando…", {
            if (cut) {
                if (!src.renameTo(target)) {
                    src.copyRecursively(target, false)
                    src.deleteRecursively()
                }
            } else {
                src.copyRecursively(target, false)
            }
        }) {
            if (cut) clip = null
            refresh(target.name)
        }
    }

    private fun showActions(f: File) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, a: () -> Unit) {
            labels.add(label)
            acts.add(a)
        }
        add(if (f.isDirectory) "Abrir pasta" else "Abrir") { openFile(f) }
        if (f.isFile && f.extension.lowercase() !in TEXT_EXT) {
            add("Editar como texto") { editText(f) }
        }
        add("Renomear") { rename(f) }
        add("Copiar") {
            clip = f
            clipCut = false
            toast("Copiado. Vá até a pasta de destino e use Colar")
            refresh(null, false)
        }
        add("Mover (recortar)") {
            clip = f
            clipCut = true
            toast("Pronto para mover. Vá até a pasta de destino e use Colar")
            refresh(null, false)
        }
        add("Excluir") { confirmDelete(f) }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .show()
    }

    private fun openFile(f: File) {
        if (f.isDirectory) {
            enter(f)
            return
        }
        val ext = f.extension.lowercase()
        if (ext in TEXT_EXT) {
            editText(f)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val mime = if (ext == "apk") {
                "application/vnd.android.package-archive"
            } else {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            }
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            toast("Nenhum app para abrir este tipo de arquivo")
        } catch (_: Exception) {
            toast("Não foi possível abrir o arquivo")
        }
    }

    // ---------- Tarefas em segundo plano ----------

    private fun showBusy(label: String) {
        busy = true
        findViewById<View>(R.id.fmStatusBox).visibility = View.VISIBLE
        findViewById<TextView>(R.id.fmStatus).text = label
        findViewById<ProgressBar>(R.id.fmProgress).isIndeterminate = true
    }

    private fun hideBusy() {
        busy = false
        findViewById<View>(R.id.fmStatusBox).visibility = View.GONE
    }

    private fun runBg(label: String, work: () -> Unit, done: () -> Unit) {
        if (busy) {
            toast("Aguarde a operação atual terminar")
            return
        }
        showBusy(label)
        io.execute {
            var ok = true
            try {
                work()
            } catch (_: Throwable) {
                ok = false
            }
            runOnUiThread {
                if (!isDestroyed) {
                    hideBusy()
                    if (!ok) toast("Não foi possível concluir a operação")
                    done()
                }
            }
        }
    }

    // ---------- Download ----------

    private fun askDownload() {
        val url = field("Link (http ou https)", "https://", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, false)
        val name = field("Nome do arquivo (opcional)", "", InputType.TYPE_CLASS_TEXT, false)
        val d = AlertDialog.Builder(this)
            .setTitle("Baixar arquivo para " + pathLabel())
            .setView(box(url, name))
            .setPositiveButton("Baixar") { _, _ -> startDownload(url.text.toString(), name.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun connect(start: String): HttpURLConnection {
        var u = URL(start)
        var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) UniPed")
            val code = c.responseCode
            if (code in 300..399 && hops < 6) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirecionamento inválido")
                u = URL(u, loc)
                c.disconnect()
                hops++
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("servidor respondeu HTTP $code")
            }
            return c
        }
    }

    private fun nameFromConn(c: HttpURLConnection): String {
        var n: String? = null
        val cd = c.getHeaderField("Content-Disposition")
        if (cd != null) {
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
            if (m != null) {
                val raw = m.groupValues[1]
                n = try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (_: Exception) {
                    raw
                }
            }
        }
        if (n.isNullOrBlank()) {
            val raw = c.url.path.substringAfterLast('/')
            n = try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                raw
            }
        }
        return sanitize(n ?: "download")
    }

    private fun showProgress(name: String, done: Long, total: Long) {
        val tv = findViewById<TextView>(R.id.fmStatus)
        val pb = findViewById<ProgressBar>(R.id.fmProgress)
        var txt = "Baixando $name — " + Formatter.formatFileSize(this, done)
        if (total > 0) {
            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
            txt += " de " + Formatter.formatFileSize(this, total) + " ($pct%)"
            pb.isIndeterminate = false
            pb.max = 100
            pb.progress = pct
        } else {
            pb.isIndeterminate = true
        }
        tv.text = "$txt  ·  OK para cancelar"
    }

    private fun startDownload(rawUrl: String, rawName: String) {
        var url = rawUrl.trim()
        if (url.isEmpty() || url == "https://") return
        if (!url.contains("://")) url = "https://$url"
        if (busy) {
            toast("Aguarde a operação atual terminar")
            return
        }
        val finalUrl = url
        val dir = cwd
        val wanted = rawName.trim()
        cancelFlag = false
        downloading = true
        showBusy("Conectando…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Thread {
            var part: File? = null
            var result = ""
            try {
                val c = connect(finalUrl)
                val name = if (wanted.isNotEmpty() && validName(wanted)) wanted else nameFromConn(c)
                val target = uniqueFile(dir, name)
                val partFile = File(dir, target.name + ".part")
                part = partFile
                val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                var done = 0L
                var last = 0L
                c.inputStream.use { input ->
                    FileOutputStream(partFile).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelFlag) throw IOException("cancelado")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = SystemClock.uptimeMillis()
                            if (now - last > 300) {
                                last = now
                                val d = done
                                runOnUiThread { if (!isDestroyed) showProgress(name, d, total) }
                            }
                        }
                    }
                }
                c.disconnect()
                if (!partFile.renameTo(target)) throw IOException("não foi possível salvar")
                result = "Baixado: " + target.name
            } catch (e: Throwable) {
                part?.delete()
                result = if (cancelFlag) "Download cancelado" else "Falha no download: " + (e.message ?: "erro")
            }
            runOnUiThread {
                downloading = false
                if (!isDestroyed) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    hideBusy()
                    toast(result)
                    if (cwd == dir) refresh(null, false)
                }
            }
        }.start()
    }

    // ---------- Diálogos de texto ----------

    private fun field(hint: String, initial: String, type: Int, multi: Boolean): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = type
        if (multi) {
            e.minLines = 6
            e.maxLines = 14
            e.gravity = Gravity.TOP or Gravity.START
        } else {
            e.setSingleLine(true)
        }
        e.setText(initial)
        return e
    }

    private fun box(vararg views: View): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        val p = (20 * resources.displayMetrics.density).toInt()
        l.setPadding(p, p / 2, p, 0)
        for (v in views) {
            l.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val s = ScrollView(this)
        s.addView(l)
        return s
    }

    private fun promptText(title: String, hint: String, initial: String, ok: String, onOk: (String) -> Unit) {
        val e = field(hint, initial, InputType.TYPE_CLASS_TEXT, false)
        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box(e))
            .setPositiveButton(ok) { _, _ -> onOk(e.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun promptFile(
        title: String,
        name: String,
        nameEditable: Boolean,
        content: String,
        onOk: (String, String) -> Unit
    ) {
        val n = field("Nome do arquivo", name, InputType.TYPE_CLASS_TEXT, false)
        val t = field(
            "Conteúdo", content,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, true
        )
        val view = if (nameEditable) box(n, t) else box(t)
        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Salvar") { _, _ -> onOk(n.text.toString(), t.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }
}

class FileAdapter(
    private val onOpen: (File) -> Unit,
    private val onMenu: (File) -> Unit,
    private val onFocusTick: () -> Unit
) : RecyclerView.Adapter<FileAdapter.Holder>() {

    private var items: List<File> = emptyList()
    private val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    fun submit(newItems: List<File>) {
        items = newItems
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.rowIcon)
        val name: TextView = view.findViewById(R.id.rowName)
        val info: TextView = view.findViewById(R.id.rowInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_file, parent, false)
        popOnFocus(v, 1.03f) { onFocusTick() }
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val f = items[position]
        val ctx = holder.itemView.context
        holder.itemView.tag = f
        holder.icon.setImageResource(if (f.isDirectory) R.drawable.ic_fm_folder else R.drawable.ic_fm_file)
        holder.name.text = f.name
        val whenTxt = df.format(Date(f.lastModified()))
        holder.info.text = if (f.isDirectory) {
            val n = f.list()?.size ?: 0
            n.toString() + (if (n == 1) " item" else " itens") + " · " + whenTxt
        } else {
            Formatter.formatFileSize(ctx, f.length()) + " · " + whenTxt
        }
        holder.itemView.setOnClickListener { onOpen(f) }
        holder.itemView.setOnLongClickListener {
            onMenu(f)
            true
        }
    }

    override fun getItemCount() = items.size
}
