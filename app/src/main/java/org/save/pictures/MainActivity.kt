package org.save.pictures

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.util.LruCache
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Item(val url: String, val file: File, val w: Int, val h: Int, val size: Long, val mime: String, var checked: Boolean = true)

class MainActivity : Activity() {
    lateinit var web: WebView
    lateinit var urlBox: EditText
    lateinit var bar: ProgressBar
    val ui = Handler(Looper.getMainLooper())
    val pool = Executors.newFixedThreadPool(6)
    val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun fmt(b: Long) = if (b < 1048576) "%.0f KB".format(b / 1024.0) else "%.2f MB".format(b / 1048576.0)

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this); top.setBackgroundColor(0xFF111111.toInt()); top.setPadding(dp(8), dp(8), dp(8), dp(8))
        urlBox = EditText(this).apply {
            setSingleLine(); hint = "Site ou busca"; setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt())
            imeOptions = EditorInfo.IME_ACTION_GO; inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        val goBtn = Button(this).apply { text = "Ir"; setOnClickListener { go() } }
        top.addView(urlBox, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(goBtn)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        web = WebView(this)
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = UA; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setDownloadListener { u, _, _, mime, _ -> if (!intercept(u) && mime?.contains("pdf") == true) openPdf(u) }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) { urlBox.setText(u) }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = intercept(r.url.toString())
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { bar.progress = p; bar.visibility = if (p >= 100) View.GONE else View.VISIBLE }
        }
        col.addView(top); col.addView(bar, LinearLayout.LayoutParams(-1, dp(3))); col.addView(web, LinearLayout.LayoutParams(-1, -1))
        root.addView(col)
        val fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_download); setPadding(dp(16), dp(16), dp(16), dp(16)); elevation = dp(8).toFloat()
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFB300.toInt()) }
        }
        val lp = FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END); lp.setMargins(0, 0, dp(16), dp(90))
        root.addView(fab, lp)
        var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f
        fab.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = v.x - e.rawX; dy = v.y - e.rawY; sx = e.rawX; sy = e.rawY }
                MotionEvent.ACTION_MOVE -> { v.x = e.rawX + dx; v.y = e.rawY + dy }
                MotionEvent.ACTION_UP -> if (Math.abs(e.rawX - sx) < 15 && Math.abs(e.rawY - sy) < 15) scan()
            }
            true
        }
        setContentView(root)
        web.loadUrl("https://www.google.com/imghp")
    }

    fun go() {
        var t = urlBox.text.toString().trim(); if (t.isEmpty()) return
        t = if (!t.contains(".") || t.contains(" ")) "https://www.google.com/search?tbm=isch&q=" + Uri.encode(t)
        else if (!t.startsWith("http")) "https://$t" else t
        web.loadUrl(t)
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    fun scan() {
        Toast.makeText(this, "Busca profunda...", Toast.LENGTH_SHORT).show()
        web.evaluateJavascript("(async function(){for(var i=0;i<8;i++){window.scrollBy(0,innerHeight);await new Promise(r=>setTimeout(r,250));}window.scrollTo(0,0);return 1})()", null)
        ui.postDelayed({
            web.evaluateJavascript(JS) { v ->
                val s = try { JSONTokener(v).nextValue() as? String } catch (e: Exception) { null }
                if (s == null) { Toast.makeText(this, "Nada encontrado", Toast.LENGTH_SHORT).show(); return@evaluateJavascript }
                val a = JSONArray(s); val l = ArrayList<String>()
                for (i in 0 until a.length()) l.add(a.getString(i))
                analyze(l, web.url ?: "")
            }
        }, 2600)
    }

    fun fetch(u: String, ref: String): Item? {
        try {
            val f = File(cacheDir, "sp_" + u.hashCode() + ".bin"); val size: Long
            if (u.startsWith("data:")) {
                val i = u.indexOf("base64,"); if (i < 0) return null
                val by = Base64.decode(u.substring(i + 7), Base64.DEFAULT); f.writeBytes(by); size = by.size.toLong()
            } else {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 12000
                c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Referer", ref)
                CookieManager.getInstance().getCookie(u)?.let { c.setRequestProperty("Cookie", it) }
                if (c.responseCode != 200) return null
                var n = 0L
                c.inputStream.use { i -> f.outputStream().use { o ->
                    val buf = ByteArray(16384)
                    while (true) { val r = i.read(buf); if (r < 0) break; n += r; if (n > 25_000_000) return null; o.write(buf, 0, r) }
                } }
                size = n
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0 || size < 3000 || (o.outWidth < 48 && o.outHeight < 48)) { f.delete(); return null }
            return Item(u, f, o.outWidth, o.outHeight, size, o.outMimeType ?: "image/jpeg")
        } catch (e: Exception) { return null }
    }

    fun analyze(urls: List<String>, ref: String) {
        val items = ArrayList<Item>(); val done = AtomicInteger(0); val total = urls.size
        val dlg = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val title = TextView(this).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); setTextColor(Color.WHITE); setBackgroundColor(0xFF111111.toInt()); textSize = 16f; text = "Analisando 0/$total" }
        val list = ListView(this)
        val cache = LruCache<String, Bitmap>(40)
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, par: ViewGroup?): View {
                val it = items[p]
                val row = LinearLayout(this@MainActivity); row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(dp(8), dp(6), dp(8), dp(6))
                val img = ImageView(this@MainActivity); img.scaleType = ImageView.ScaleType.CENTER_CROP
                row.addView(img, LinearLayout.LayoutParams(dp(80), dp(80)))
                val tv = TextView(this@MainActivity); tv.setPadding(dp(10), 0, dp(6), 0)
                tv.text = "${it.w} x ${it.h}\n${fmt(it.size)} • ${it.mime.substringAfter("/").uppercase()}\n${Uri.parse(it.url).host ?: "inline"}"
                row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
                val cb = CheckBox(this@MainActivity); cb.isChecked = it.checked
                cb.setOnCheckedChangeListener { _, c -> it.checked = c }
                row.addView(cb)
                val bm = cache.get(it.file.path)
                if (bm != null) img.setImageBitmap(bm) else pool.execute {
                    val o = BitmapFactory.Options().apply { inSampleSize = Math.max(1, Math.min(it.w, it.h) / 160) }
                    val t = BitmapFactory.decodeFile(it.file.path, o)
                    if (t != null) { cache.put(it.file.path, t); ui.post { img.setImageBitmap(t) } }
                }
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ -> items[p].checked = !items[p].checked; adapter.notifyDataSetChanged() }
        val btns = LinearLayout(this); btns.setBackgroundColor(0xFF111111.toInt()); btns.setPadding(dp(8), dp(8), dp(8), dp(8))
        val all = Button(this).apply { text = "Todas"; setOnClickListener { val v = items.any { !it.checked }; items.forEach { it.checked = v }; adapter.notifyDataSetChanged() } }
        val dl = Button(this).apply { text = "Baixar"; setOnClickListener { save(items.filter { it.checked }) } }
        val close = Button(this).apply { text = "Fechar"; setOnClickListener { dlg.dismiss() } }
        for (b in listOf(all, dl, close)) btns.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(title); col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f)); col.addView(btns)
        dlg.setContentView(col); dlg.show()
        for (u in urls) pool.execute {
            val it = fetch(u, ref)
            val n = done.incrementAndGet()
            ui.post {
                if (it != null) { items.add(it); items.sortByDescending { x -> x.w.toLong() * x.h } ; adapter.notifyDataSetChanged() }
                title.text = if (n >= total) "${items.size} imagens encontradas" else "Analisando $n/$total • ${items.size} imagens"
            }
        }
    }

    fun save(sel: List<Item>) {
        if (sel.isEmpty()) { Toast.makeText(this, "Nenhuma selecionada", Toast.LENGTH_SHORT).show(); return }
        Thread {
            var ok = 0
            sel.forEachIndexed { i, it ->
                try {
                    val ext = it.mime.substringAfter("/").replace("jpeg", "jpg")
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "save_${System.currentTimeMillis()}_$i.$ext")
                        put(MediaStore.Images.Media.MIME_TYPE, it.mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Save Pictures")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { o -> it.file.inputStream().use { inp -> inp.copyTo(o) } }
                    ok++
                } catch (e: Exception) { }
            }
            ui.post { Toast.makeText(this, "$ok imagem(ns) salvas em Pictures/Save Pictures", Toast.LENGTH_LONG).show() }
        }.start()
    }

    fun msg(t: String) { ui.post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() } }

    fun intercept(u: String): Boolean {
        val p = u.substringBefore("?").substringBefore("#").lowercase()
        return when {
            p.endsWith(".pdf") -> { openPdf(u); true }
            Regex(".*\\.(jpe?g|png|webp|gif|bmp)").matches(p) -> { openImage(u); true }
            else -> false
        }
    }

    fun download(u: String, ref: String, f: File): Boolean = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Referer", ref)
        CookieManager.getInstance().getCookie(u)?.let { c.setRequestProperty("Cookie", it) }
        if (c.responseCode != 200) false else { c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }; true }
    } catch (e: Exception) { false }

    fun openPdf(u: String) {
        Toast.makeText(this, "Abrindo desenho...", Toast.LENGTH_SHORT).show()
        val ref = web.url ?: ""
        pool.execute {
            val f = File(cacheDir, "v_" + u.hashCode() + ".pdf")
            if (!download(u, ref, f)) { msg("Erro ao baixar o desenho"); return@execute }
            try {
                val r = PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
                val pg = r.openPage(0)
                val sc = 2000f / pg.width
                val bm = Bitmap.createBitmap(2000, (pg.height * sc).toInt(), Bitmap.Config.ARGB_8888)
                bm.eraseColor(Color.WHITE)
                pg.render(bm, null, Matrix().apply { setScale(sc, sc) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pg.close(); r.close()
                ui.post { viewer(bm, f) }
            } catch (e: Exception) { msg("Erro ao abrir: ${e.message}") }
        }
    }

    fun openImage(u: String) {
        Toast.makeText(this, "Abrindo imagem...", Toast.LENGTH_SHORT).show()
        val ref = web.url ?: ""
        pool.execute {
            val f = File(cacheDir, "v_" + u.hashCode() + ".img")
            if (!download(u, ref, f)) { msg("Erro ao baixar a imagem"); return@execute }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val o2 = BitmapFactory.Options().apply { inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 2500) }
            val bm = BitmapFactory.decodeFile(f.path, o2)
            if (bm == null) msg("Formato não suportado") else ui.post { viewer(bm, null) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun viewer(bm: Bitmap, pdf: File?) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this); root.setBackgroundColor(Color.BLACK)
        val img = ImageView(this); img.setImageBitmap(bm); img.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(img, FrameLayout.LayoutParams(-1, -1))
        var sc = 1f; var lx = 0f; var ly = 0f; var last = 0
        val sd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(g: ScaleGestureDetector): Boolean {
                sc = (sc * g.scaleFactor).coerceIn(1f, 6f); img.scaleX = sc; img.scaleY = sc; return true
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                sc = 1f; img.scaleX = 1f; img.scaleY = 1f; img.translationX = 0f; img.translationY = 0f; return true
            }
        })
        root.setOnTouchListener { _, e ->
            sd.onTouchEvent(e); gd.onTouchEvent(e)
            if (e.pointerCount == 1 && !sd.isInProgress) {
                if (last != 1 || e.action == MotionEvent.ACTION_DOWN) { lx = e.rawX; ly = e.rawY }
                else if (e.action == MotionEvent.ACTION_MOVE && sc > 1f) {
                    img.translationX += e.rawX - lx; img.translationY += e.rawY - ly; lx = e.rawX; ly = e.rawY
                }
            }
            last = e.pointerCount; true
        }
        val bar = LinearLayout(this); bar.setBackgroundColor(0xAA000000.toInt()); bar.setPadding(dp(8), dp(8), dp(8), dp(8))
        val bs = ArrayList<Button>()
        bs.add(Button(this).apply { text = "Fechar"; setOnClickListener { d.dismiss() } })
        bs.add(Button(this).apply { text = "Salvar imagem"; setOnClickListener { savePng(bm) } })
        if (pdf != null) bs.add(Button(this).apply { text = "Salvar PDF"; setOnClickListener { savePdf(pdf) } })
        for (b in bs) bar.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        d.setContentView(root); d.show()
    }

    fun savePng(bm: Bitmap) = Thread {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "desenho_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Save Pictures")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            contentResolver.openOutputStream(uri)!!.use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            msg("Salvo em Pictures/Save Pictures")
        } catch (e: Exception) { msg("Erro ao salvar") }
    }.start()

    fun savePdf(f: File) = Thread {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "desenho_${System.currentTimeMillis()}.pdf")
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Save Pictures")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            msg("PDF salvo em Download/Save Pictures")
        } catch (e: Exception) { msg("Erro ao salvar PDF") }
    }.start()

    val JS = """(function(){var s=new Set();
function add(u){if(!u)return;try{u=new URL(u,document.baseURI).href;}catch(e){return;}if(/^(https?:|data:image)/.test(u))s.add(u);}
document.querySelectorAll('img,source,video,image').forEach(function(e){['src','data-src','data-original','data-lazy-src','data-lazy','data-url','poster','href'].forEach(function(a){add(e.getAttribute(a));});['srcset','data-srcset'].forEach(function(a){var v=e.getAttribute(a);if(v)v.split(',').forEach(function(p){add(p.trim().split(/\s+/)[0]);});});});
document.querySelectorAll('meta[property="og:image"],meta[name="twitter:image"],link[rel~="icon"],link[rel="image_src"]').forEach(function(e){add(e.content||e.href);});
document.querySelectorAll('a[href]').forEach(function(a){if(/\.(jpe?g|png|gif|webp|bmp|avif)(\?|#|${'$'})/i.test(a.href))add(a.href);});
document.querySelectorAll('*').forEach(function(e){var b=getComputedStyle(e).backgroundImage;if(b&&b!='none'){var r=/url\(["']?([^"')]+)["']?\)/g,m;while(m=r.exec(b))add(m[1]);}});
var h=document.documentElement.outerHTML.replace(/\\\//g,'/'),m2,r2=/https?:\/\/[^"'\s)\\<>]+\.(jpe?g|png|webp|gif|avif)[^"'\s)\\<>]*/gi;
while(m2=r2.exec(h))add(m2[0]);
return JSON.stringify(Array.from(s));})()"""
}
