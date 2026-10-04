package com.mangaslayer.plus

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONTokener
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val Purple = Color(0xFF6A1B9A)
val LightBrown = Color(0xFFD7B899)

data class Series(val title: String, val url: String, val cover: String?)
data class Chapter(val title: String, val url: String)

interface Source {
    val name: String
    fun latest(): List<Series>
    fun description(url: String): String
    fun chapters(url: String): List<Chapter>
    fun pages(url: String): List<String>
}

val http = OkHttpClient()
fun html(url: String, post: Boolean = false): Document = http.newCall(
    Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
        .apply { if (post) post(FormBody.Builder().build()) }.build()
).execute().use { Jsoup.parse(it.body!!.string(), url) }

/** يفتح الصفحة في WebView مخفي وينفّذ JS حتى تستقر النتيجة (للصفحات التي تُحمَّل محتواها بـ JavaScript). */
object Web {
    lateinit var ctx: Context
    fun eval(url: String, js: String, timeoutMs: Long = 25000): List<String> {
        val out = LinkedBlockingQueue<List<String>>(1)
        val h = Handler(Looper.getMainLooper())
        h.post {
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            val start = System.currentTimeMillis()
            var last = -1
            var started = false
            fun poll() {
                wv.evaluateJavascript(js) { r ->
                    val list = runCatching {
                        val a = JSONArray(JSONTokener(r).nextValue() as String)
                        (0 until a.length()).map { a.getString(it) }
                    }.getOrDefault(emptyList())
                    val done = (list.isNotEmpty() && list.size == last) || System.currentTimeMillis() - start > timeoutMs
                    last = list.size
                    if (done) { wv.destroy(); out.offer(list) } else h.postDelayed({ poll() }, 900)
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) { if (!started) { started = true; poll() } }
            }
            wv.loadUrl(url)
        }
        return out.poll(timeoutMs + 5000, TimeUnit.MILLISECONDS) ?: emptyList()
    }
}

object ProComicApi : Source {
    override val name = "ProComic"
    const val BASE = "https://procomic.net"
    override fun latest(): List<Series> = html("$BASE/ar").select("a[href*=/ar/series/]").mapNotNull {
        val img = it.selectFirst("img")
        val t = img?.attr("alt").orEmpty().ifBlank { it.text() }
        if (t.isBlank()) null else Series(t, it.absUrl("href"), img?.absUrl("src"))
    }.distinctBy { it.url }
    override fun description(url: String): String = html(url).select("h2:contains(القصة) + *").text()
    // الفصول والصور تُحمَّل بـ JavaScript في procomic.pro، لذا نقرؤها عبر WebView مخفي (المحددات لم تُختبر).
    override fun chapters(url: String): List<Chapter> {
        val reader = "https://procomic.pro/ar/" + url.trimEnd('/').substringAfterLast('/')
        val js = "(function(){window.scrollTo(0,document.body.scrollHeight);var m={};document.querySelectorAll('a[href*=\\"/ar/chapter/\\"]').forEach(function(a){m[a.href]=a.textContent.trim()});return JSON.stringify(Object.keys(m).map(function(k){return k+'\\t'+m[k]}))})()"
        return Web.eval(reader, js).map { Chapter(it.substringAfter('\t').ifBlank { "فصل" }, it.substringBefore('\t')) }
    }
    override fun pages(url: String): List<String> {
        val js = "(function(){window.scrollTo(0,document.body.scrollHeight);return JSON.stringify([].slice.call(document.images).map(function(i){return i.currentSrc||i.src}).filter(function(s){return /cdn/.test(s)&&!/image_series|seo|avatar|logo/.test(s)}))})()"
        return Web.eval(url, js)
    }
}

/** مصدر Lek-Manga (قالب WordPress Madara المعروف). المحددات قياسية ولم تُختبر على الموقع. */
object LekManga : Source {
    override val name = "Lek-Manga"
    const val BASE = "https://io.lek-manga.net"
    override fun latest(): List<Series> = html("$BASE/manga/").select("div.page-item-detail").mapNotNull {
        val a = it.selectFirst("h3 a, h5 a") ?: return@mapNotNull null
        val img = it.selectFirst("img")
        Series(a.text(), a.absUrl("href"), img?.absUrl("data-src")?.ifBlank { img.absUrl("src") })
    }
    override fun description(url: String): String = html(url).select("div.summary__content").text()
    override fun chapters(url: String): List<Chapter> {
        val d = html(url.trimEnd('/') + "/ajax/chapters/", post = true)
        return d.select("li.wp-manga-chapter a").map { Chapter(it.text(), it.absUrl("href")) }
    }
    override fun pages(url: String): List<String> = html(url).select("div.reading-content img").map {
        it.attr("data-src").ifBlank { it.attr("src") }.trim()
    }
}

object Sources {
    val list: List<Source> = listOf(ProComicApi, LekManga)
    var cur: Source by mutableStateOf(list[0])
}

sealed class Screen {
    object Home : Screen()
    data class Detail(val s: Series) : Screen()
    data class Reader(val c: Chapter) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Web.ctx = applicationContext
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple, secondary = LightBrown, background = Color(0xFFFAF3EA))) {
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                var back by remember { mutableStateOf<Screen>(Screen.Home) }
                BackHandler(screen != Screen.Home) { screen = if (screen is Screen.Reader) back else Screen.Home }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val s = screen) {
                        is Screen.Home -> Home { back = Screen.Detail(it); screen = Screen.Detail(it) }
                        is Screen.Detail -> Detail(s.s) { screen = Screen.Reader(it) }
                        is Screen.Reader -> Reader(s.c)
                    }
                }
            }
        }
    }
}

@Composable
fun <T> load(key: Any, f: () -> T): State<Result<T>?> = produceState<Result<T>?>(null, key) {
    value = withContext(Dispatchers.IO) { runCatching { f() } }
}

@Composable
fun Msg(t: String) = Box(Modifier.fillMaxSize().padding(24.dp)) { Text(t, Modifier.padding(top = 80.dp)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(onOpen: (Series) -> Unit) {
    val r by load(Sources.cur.name) { Sources.cur.latest() }
    Column {
        TopAppBar(title = { Text("Manga Slayer+") }, actions = { TextButton({ Sources.cur = Sources.list[(Sources.list.indexOf(Sources.cur) + 1) % Sources.list.size] }) { Text(Sources.cur.name, color = LightBrown) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Purple, titleContentColor = LightBrown))
        val res = r
        when {
            res == null -> Msg("جارٍ التحميل...")
            res.isFailure -> Msg("تعذّر التحميل: ${res.exceptionOrNull()?.message}")
            else -> LazyVerticalGrid(GridCells.Adaptive(120.dp), contentPadding = PaddingValues(8.dp)) {
                items(res.getOrThrow()) { s ->
                    Column(Modifier.padding(4.dp).clickable { onOpen(s) }) {
                        AsyncImage(s.cover, null, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                        Text(s.title, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun Detail(s: Series, onChapter: (Chapter) -> Unit) {
    val desc by load(s.url) { Sources.cur.description(s.url) }
    val ch by load("ch" + s.url) { Sources.cur.chapters(s.url) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Text(s.title, style = MaterialTheme.typography.titleLarge, color = Purple) }
        item { Text(desc?.getOrNull().orEmpty(), Modifier.padding(vertical = 12.dp)) }
        val c = ch
        when {
            c == null -> item { Text("جارٍ تحميل الفصول...") }
            c.isFailure -> item { Text("الفصول غير مربوطة بعد: ${c.exceptionOrNull()?.message}") }
            else -> items(c.getOrThrow()) { Text(it.title, Modifier.fillMaxWidth().clickable { onChapter(it) }.padding(vertical = 12.dp)) }
        }
    }
}

@Composable
fun Reader(c: Chapter) {
    val p by load(c.url) { Sources.cur.pages(c.url) }
    val res = p
    when {
        res == null -> Msg("جارٍ التحميل...")
        res.isFailure -> Msg("تعذّر التحميل: ${res.exceptionOrNull()?.message}")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(res.getOrThrow()) { AsyncImage(it, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
        }
    }
}
