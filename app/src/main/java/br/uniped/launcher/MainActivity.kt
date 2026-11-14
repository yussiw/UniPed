package br.uniped.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

data class AppItem(val label: String, val icon: Drawable, val intent: Intent)

class MainActivity : Activity() {

    private lateinit var adapter: AppAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private val columns = 8

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

        // Tamanho do ícone proporcional à tela (menor no celular, maior na TV)
        val dm = resources.displayMetrics
        val usable = dm.widthPixels - (80 + 32) * dm.density
        val iconPx = (usable / columns * 0.5f).toInt()
        adapter = AppAdapter(iconPx) { startApp(it) }

        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, columns)
        grid.adapter = adapter

        findViewById<View>(R.id.orb).setOnClickListener { openSettings() }
    }

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

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    override fun onDestroy() {
        executor.shutdown()
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

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

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
            runOnUiThread { adapter.submit(sorted) }
        }
    }

    private fun startApp(item: AppItem) {
        try {
            startActivity(item.intent)
        } catch (_: ActivityNotFoundException) {
        }
    }
}

class AppAdapter(
    private val iconPx: Int,
    private val onClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var items: List<AppItem> = emptyList()

    fun submit(newItems: List<AppItem>) {
        items = newItems
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

        // Efeito de foco leve (a GPU Mali-450 é fraca)
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
