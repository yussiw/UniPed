package br.uniped.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class AppItem(val label: String, val icon: Drawable, val intent: Intent)

/** Efeito de "pulinho" ao receber foco (leve para GPU fraca: só escala). */
fun popOnFocus(v: View, scale: Float, onFocus: (() -> Unit)? = null) {
    v.setOnFocusChangeListener { view, hasFocus ->
        if (hasFocus) {
            onFocus?.invoke()
            view.animate().scaleX(scale).scaleY(scale)
                .setDuration(180).setInterpolator(OvershootInterpolator(2f)).start()
        } else {
            view.animate().scaleX(1f).scaleY(1f)
                .setDuration(140).setInterpolator(DecelerateInterpolator()).start()
        }
    }
}

/**
 * Sons da interface, bem baixinhos.
 * Para mudar o volume, ajuste TICK_VOL e INTRO_VOL (0.0 a 1.0).
 */
class UiSounds(context: Context) {

    companion object {
        const val TICK_VOL = 0.18f
        const val INTRO_VOL = 0.30f
    }

    private val pool: SoundPool
    private var tickId = 0
    private var introId = 0
    @Volatile private var tickReady = false
    @Volatile private var introReady = false
    @Volatile private var introWanted = false
    private var lastTick = 0L

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
        pool.setOnLoadCompleteListener { sp, id, status ->
            if (status == 0) {
                if (id == tickId) tickReady = true
                if (id == introId) {
                    introReady = true
                    // a intro começou antes do som terminar de carregar
                    if (introWanted) {
                        introWanted = false
                        sp.play(id, INTRO_VOL, INTRO_VOL, 1, 0, 1f)
                    }
                }
            }
        }
        tickId = pool.load(context, R.raw.tick, 1)
        introId = pool.load(context, R.raw.intro, 1)
    }

    fun playIntro() {
        if (introReady) pool.play(introId, INTRO_VOL, INTRO_VOL, 1, 0, 1f)
        else introWanted = true
    }

    fun tick() {
        if (!tickReady) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 55) return
        lastTick = now
        val rate = 0.96f + Random.nextFloat() * 0.08f
        pool.play(tickId, TICK_VOL, TICK_VOL, 1, 0, rate)
    }

    fun release() {
        pool.release()
    }
}

class MainActivity : Activity() {

    companion object {
        // A intro toca uma vez por processo (não toda vez que voltar para a Home)
        private var introPlayed = false
    }

    private lateinit var adapter: AppAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private val columns = 8
    private var appsShown = false
    private var contentStartAt = 0L
    private var tickAfter = 0L
    private lateinit var sounds: UiSounds

    private val handler = Handler(Looper.getMainLooper())
    private val statusTask = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 5000)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // Toca o "tick" de navegação (não durante a intro)
    private fun tick() {
        if (SystemClock.uptimeMillis() >= tickAfter) sounds.tick()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ocupa também a área do recorte da câmera (celular)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        setContentView(R.layout.activity_main)
        hideSystemBars()
        sounds = UiSounds(this)

        // Tamanho do ícone proporcional à tela (menor no celular, maior na TV)
        val dm = resources.displayMetrics
        val usable = dm.widthPixels - 200 * dm.density
        val iconPx = (usable / columns * 0.5f).toInt()
        adapter = AppAdapter(iconPx, { item, v -> startApp(item, v) }, { tick() })

        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, columns)
        grid.adapter = adapter

        // Som de "scroll" ao arrastar a lista com o dedo
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var dragging = false
            private var acc = 0f

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                dragging = newState == RecyclerView.SCROLL_STATE_DRAGGING
                if (dragging) acc = 0f
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!dragging) return
                acc += abs(dy)
                if (acc > dp(56f)) {
                    acc = 0f
                    tick()
                }
            }
        })

        // Orbe e atalhos da barra de tarefas
        findViewById<View>(R.id.orb).setOnClickListener { openSettings() }
        bindQuick(R.id.qWifi, Settings.ACTION_WIFI_SETTINGS)
        bindQuick(R.id.qBt, Settings.ACTION_BLUETOOTH_SETTINGS)
        bindQuick(R.id.qSound, Settings.ACTION_SOUND_SETTINGS)
        bindQuick(R.id.qDisplay, Settings.ACTION_DISPLAY_SETTINGS)
        bindQuick(R.id.qStorage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        bindQuick(R.id.qApps, Settings.ACTION_APPLICATION_SETTINGS)
        findViewById<View>(R.id.netStatus).setOnClickListener {
            openSettings(Settings.ACTION_WIFI_SETTINGS)
        }
        listOf(
            R.id.orb, R.id.qWifi, R.id.qBt, R.id.qSound, R.id.qDisplay, R.id.qStorage, R.id.qApps
        ).forEach { popOnFocus(findViewById(it), 1.12f) { tick() } }

        // Intro rápida (~0,9 s)
        val layer = findViewById<View>(R.id.introLayer)
        if (!introPlayed) {
            introPlayed = true
            contentStartAt = SystemClock.uptimeMillis() + 390
            tickAfter = contentStartAt + 700
            prepareIntro()
            layer.post { playIntro() }
        } else {
            layer.visibility = View.GONE
        }
    }

    // ---------- Intro ----------

    private fun prepareIntro() {
        findViewById<View>(R.id.introOrb).apply { alpha = 0f; scaleX = 0.5f; scaleY = 0.5f }
        findViewById<View>(R.id.introText).apply { alpha = 0f; translationY = dp(12f) }
        findViewById<View>(R.id.titleRow).apply { alpha = 0f; translationY = -dp(14f) }
        findViewById<View>(R.id.panel).apply {
            alpha = 0f; translationY = dp(34f); scaleX = 0.97f; scaleY = 0.97f
        }
        findViewById<View>(R.id.taskbar).apply { alpha = 0f; translationY = dp(52f) }
    }

    private fun playIntro() {
        val layer = findViewById<View>(R.id.introLayer)
        val group = findViewById<View>(R.id.introGroup)
        val orb = findViewById<View>(R.id.introOrb)
        val text = findViewById<View>(R.id.introText)
        val decel = DecelerateInterpolator(1.8f)
        sounds.playIntro()

        // 1) Orbe "estoura" na tela e o nome aparece
        orb.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(240).setInterpolator(OvershootInterpolator(1.8f)).start()
        text.animate().alpha(1f).translationY(0f)
            .setStartDelay(100).setDuration(220).setInterpolator(decel).start()

        // 2) A intro dá um zoom e some, enquanto a Home desliza para o lugar
        handler.postDelayed({
            group.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f)
                .setDuration(260).setInterpolator(AccelerateInterpolator()).start()
            layer.animate().alpha(0f).setDuration(260)
                .withEndAction { layer.visibility = View.GONE }.start()

            findViewById<View>(R.id.titleRow).animate().alpha(1f).translationY(0f)
                .setDuration(300).setInterpolator(decel).start()
            findViewById<View>(R.id.panel).animate().alpha(1f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .setStartDelay(40).setDuration(360).setInterpolator(decel).start()
            findViewById<View>(R.id.taskbar).animate().alpha(1f).translationY(0f)
                .setStartDelay(100).setDuration(320).setInterpolator(decel).start()
        }, 330L)
    }

    // ---------- Barras do sistema ----------

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

    // ---------- Ciclo de vida ----------

    override fun onResume() {
        super.onResume()
        loadApps()
        handler.post(statusTask)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTask)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        sounds.release()
        super.onDestroy()
    }

    // Home não deve fechar com o botão Voltar
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // intencionalmente vazio
    }

    // Botão MENU do controle abre as configurações
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Configurações ----------

    private fun bindQuick(id: Int, action: String) {
        findViewById<View>(id).setOnClickListener { openSettings(action) }
    }

    // Abre uma tela de configurações; se a TV não tiver, cai nas configurações gerais
    private fun openSettings(action: String = Settings.ACTION_SETTINGS) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            if (action != Settings.ACTION_SETTINGS) openSettings()
        }
    }

    // ---------- Status (rede, disco, RAM) ----------

    private fun updateStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        val text: String
        val color: Int
        if (caps == null) {
            text = "Sem conexão"
            color = 0xFFFF6B6B.toInt()
        } else {
            val tipo = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados móveis"
                else -> "Rede"
            }
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                text = "$tipo · Conectado"
                color = 0xFF7CFC9A.toInt()
            } else {
                text = "$tipo · Sem internet"
                color = 0xFFFFD166.toInt()
            }
        }
        val net = findViewById<TextView>(R.id.netStatus)
        net.text = text
        net.compoundDrawableTintList = ColorStateList.valueOf(color)

        val stat = StatFs(Environment.getDataDirectory().path)
        findViewById<TextView>(R.id.diskStatus).text =
            "Disco: " + Formatter.formatFileSize(this, stat.availableBytes) + " livres"

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        findViewById<TextView>(R.id.ramStatus).text =
            "RAM: " + Formatter.formatFileSize(this, mi.availMem) + " livre"
    }

    // ---------- Apps ----------

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val found = LinkedHashMap<String, AppItem>()

            // Leanback primeiro (apps de TV), depois apps comuns
            for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                val query = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (info in pm.queryIntentActivities(query, 0)) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == packageName || found.containsKey(pkg)) continue
                    val launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(category)
                        .setClassName(pkg, info.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    found[pkg] = AppItem(info.loadLabel(pm).toString(), info.loadIcon(pm), launch)
                }
            }

            val sorted = found.values.sortedBy { it.label.lowercase() }
            runOnUiThread {
                val first = !appsShown
                appsShown = true
                adapter.entranceStartAt = max(contentStartAt, SystemClock.uptimeMillis())
                adapter.submit(sorted, first)
            }
        }
    }

    // Abre o app "crescendo" a partir do ícone clicado
    private fun startApp(item: AppItem, from: View) {
        try {
            val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
            startActivity(item.intent, opts.toBundle())
        } catch (_: ActivityNotFoundException) {
        }
    }
}

class AppAdapter(
    private val iconPx: Int,
    private val onClick: (AppItem, View) -> Unit,
    private val onFocusTick: () -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var items: List<AppItem> = emptyList()

    // Entrada em cascata: só nos primeiros itens visíveis, só na primeira exibição
    var entranceStartAt = 0L
    private var entranceUntil = 0L

    fun submit(newItems: List<AppItem>, animate: Boolean) {
        val same = newItems.map { it.intent.component } == items.map { it.intent.component }
        if (same && !animate) return
        items = newItems
        if (animate) entranceUntil = SystemClock.uptimeMillis() + 600
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val holder = Holder(view)

        holder.icon.layoutParams = holder.icon.layoutParams.apply {
            width = iconPx
            height = iconPx
        }
        holder.label.setTextSize(TypedValue.COMPLEX_UNIT_PX, iconPx * 0.26f)
        popOnFocus(view, 1.08f) { onFocusTick() }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label
        holder.itemView.setOnClickListener { onClick(item, holder.itemView) }

        val v = holder.itemView
        v.animate().cancel()
        val now = SystemClock.uptimeMillis()
        if (now < entranceUntil && position < 32) {
            val columns = 8
            val row = position / columns
            val col = position % columns
            val delay = max(0L, entranceStartAt - now) + row * 40L + col * 18L
            v.alpha = 0f
            v.translationY = iconPx * 0.3f
            v.scaleX = 0.9f
            v.scaleY = 0.9f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay).setDuration(300)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            v.alpha = 1f
            v.translationY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
        }
    }

    override fun getItemCount() = items.size
}
