package com.codeassistant.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val TAG = "CodeAssistant"
        private const val PORT = 9190
        private const val APP_HOST = "codeassistant.local"
        private const val REQ_PICK_MODEL = 101
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView

    private var proc: Process? = null
    private var modelPfd: ParcelFileDescriptor? = null
    @Volatile private var modelRealPath: String? = null
    @Volatile private var serverReady = false
    @Volatile private var serverStatus = "No model selected"
    @Volatile private var safeKv = false
    @Volatile private var backActive = false

    private val serverLock = Any()
    private val genMain = AtomicInteger(0)
    private val logBuf = StringBuilder()

    // Per-launch bearer token for local llama-server (same security model as Szop)
    private val localKey: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val WATCHDOG_SH = listOf(
        "PARENT=\$1; PIDF=\$2; shift 2",
        "\"\$@\" &",
        "CHILD=\$!",
        "echo \$CHILD > \"\$PIDF\" 2>/dev/null",
        "trap 'kill \$CHILD 2>/dev/null; exit 143' TERM INT HUP",
        "while kill -0 \$PARENT 2>/dev/null && kill -0 \$CHILD 2>/dev/null; do sleep 0.25 2>/dev/null || sleep 1; done",
        "kill \$CHILD 2>/dev/null",
        "wait \$CHILD 2>/dev/null",
        "exit \$?"
    ).joinToString("\n")

    private fun pidFile() = File(cacheDir, "ca-llama.pid")

    private fun launchWatched(args: List<String>): Process {
        val sh = File("/system/bin/sh")
        try { pidFile().delete() } catch (_: Exception) {}
        val full = if (sh.canExecute()) {
            listOf(
                sh.path, "-c", WATCHDOG_SH, "ca-watch",
                android.os.Process.myPid().toString(), pidFile().absolutePath
            ) + args
        } else args
        return ProcessBuilder(full).redirectErrorStream(true).start()
    }

    private fun alive(pid: Int): Boolean = try { Os.kill(pid, 0); true } catch (_: Exception) { false }

    private fun stopProc(p: Process?) {
        val pid = try { pidFile().readText().trim().toInt() } catch (_: Exception) { -1 }
        if (pid > 1) try { Os.kill(pid, OsConstants.SIGTERM) } catch (_: Exception) {}
        if (p != null) try { p.destroy() } catch (_: Exception) {}
        if (p != null) try { p.waitFor(1500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        if (pid > 1) {
            var i = 0
            while (alive(pid) && i < 20) { try { Thread.sleep(50) } catch (_: Exception) {}; i++ }
            if (alive(pid)) try { Os.kill(pid, OsConstants.SIGKILL) } catch (_: Exception) {}
        }
        try { pidFile().delete() } catch (_: Exception) {}
    }

    private fun killNow() {
        val pid = try { pidFile().readText().trim().toInt() } catch (_: Exception) { -1 }
        if (pid > 1) try { Os.kill(pid, OsConstants.SIGTERM) } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ca", MODE_PRIVATE)

        Runtime.getRuntime().addShutdownHook(Thread {
            try { proc?.destroy() } catch (_: Exception) {}
            killNow()
        })

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setBackgroundColor(0xFF000000.toInt())

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url ?: return null
                    if (u.scheme != "https" || u.host != APP_HOST) return null
                    val rawPath = (u.encodedPath ?: "/").trimStart('/').ifEmpty { "index.html" }
                    val clean = rawPath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
                    val assetPath = "www/$clean"
                    val headers = mutableMapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "no-store"
                    )
                    return try {
                        val stream = assets.open(assetPath)
                        WebResourceResponse(mimeFor(clean), "utf-8", 200, "OK", headers, stream)
                    } catch (_: Exception) {
                        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(ByteArray(0)))
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url ?: return false
                    if (u.scheme == "https" && u.host == APP_HOST) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "CodeNative")
        }

        setContentView(web)
        web.loadUrl("https://$APP_HOST/index.html")

        // Auto-start last model if still readable
        val savedPath = prefs.getString("model_path", null)
        val savedUri = prefs.getString("model_uri", null)
        when {
            savedPath != null && File(savedPath).canRead() -> {
                modelRealPath = savedPath
                startServer()
            }
            savedUri != null -> {
                thread {
                    try {
                        openFromUri(Uri.parse(savedUri))
                        startServer()
                    } catch (e: Exception) {
                        Log.w(TAG, "reopen saved URI failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "ttf" -> "font/ttf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (backActive) {
            runOnUiThread { web.evaluateJavascript("window.__goBack && window.__goBack()", null) }
        } else {
            super.onBackPressed()
        }
    }

    private fun filesOk(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    private fun rootDir(): File =
        File(prefs.getString("root", null) ?: Environment.getExternalStorageDirectory().path)

    private fun askAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
        }
    }

    private fun pickModel() {
        if (!filesOk()) askAllFilesAccess()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_PICK_MODEL)
    }

    @Deprecated("plain Activity")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val u = data?.data
        if (req != REQ_PICK_MODEL || res != RESULT_OK || u == null) return
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}

        notifyUiStatus(false, "Locating model…")
        thread {
            try {
                openFromUri(u)
                val name = displayName(u)
                val path = modelRealPath
                prefs.edit()
                    .putString("model_uri", u.toString())
                    .putString("model_name", name)
                    .putString("model_path", path)
                    .apply()
                rememberRecent(u.toString(), name, path)
                startServer()
            } catch (e: Exception) {
                notifyUiStatus(false, "Error: ${e.message}")
            }
        }
    }

    // ---- Szop-style zero-copy GGUF URI → filesystem path resolver ----
    private fun displayName(u: Uri): String {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) return it.getString(0) ?: "model.gguf" }
        return u.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
    }

    private fun displaySize(u: Uri): Long = try {
        contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
    } catch (_: Exception) { -1L }

    private fun openFromUri(u: Uri) {
        try { modelPfd?.close() } catch (_: Exception) {}
        modelPfd = null
        modelRealPath = null

        if ("file".equals(u.scheme, true)) {
            val p = u.path ?: throw IllegalStateException("empty path")
            if (!File(p).canRead()) throw IllegalStateException("cannot read $p")
            modelRealPath = p
            return
        }

        pathFromDocumentId(u)?.let {
            if (File(it).canRead()) { modelRealPath = it; return }
        }

        val pfd = contentResolver.openFileDescriptor(u, "r")
            ?: throw IllegalStateException("openFileDescriptor returned null")
        modelPfd = pfd

        try {
            Os.readlink("/proc/self/fd/${pfd.fd}")?.let {
                if (!it.startsWith("/proc/") && !it.startsWith("/dev/") && File(it).canRead()) {
                    modelRealPath = it; return
                }
            }
        } catch (_: Exception) {}

        try {
            val canon = File("/proc/self/fd/${pfd.fd}").canonicalPath
            if (!canon.startsWith("/proc/") && !canon.startsWith("/dev/") && File(canon).canRead()) {
                modelRealPath = canon; return
            }
        } catch (_: Exception) {}

        val name = displayName(u)
        val size = displaySize(u)
        val roots = mutableListOf<File>()
        try { roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) } catch (_: Exception) {}
        listOf("/storage/emulated/0/Download", "/sdcard/Download", "/storage/emulated/0").forEach { roots.add(File(it)) }
        findFileNamed(name, roots, 6, size)?.let { modelRealPath = it; return }

        throw IllegalStateException("Cannot resolve real path for $name. Grant All Files Access and keep .gguf in Download/.")
    }

    private fun pathFromDocumentId(uri: Uri): String? = try {
        val docId = if (DocumentsContract.isDocumentUri(this, uri)) DocumentsContract.getDocumentId(uri)
        else uri.lastPathSegment ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size < 2) null
        else if (parts[0].equals("primary", true)) File(Environment.getExternalStorageDirectory(), parts[1]).absolutePath
        else if (parts[0].matches(Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}"))) File("/storage/${parts[0]}/${parts[1]}").absolutePath
        else null
    } catch (_: Exception) { null }

    private fun findFileNamed(name: String, roots: List<File>, maxDepth: Int, size: Long): String? {
        fun match(f: File) = f.isFile && f.name == name && f.canRead() && (size <= 0L || f.length() == size)
        val q = ArrayDeque<Pair<File, Int>>()
        val seen = HashSet<String>()
        roots.forEach { if ((it.isDirectory || it.isFile) && seen.add(it.absolutePath)) q.add(it to 0) }
        var checked = 0
        while (q.isNotEmpty() && checked < 4000) {
            val (dir, depth) = q.removeFirst()
            checked++
            if (dir.isFile) { if (match(dir)) return dir.absolutePath; continue }
            if (!dir.isDirectory || depth > maxDepth) continue
            val kids = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in kids) {
                if (match(f)) return f.absolutePath
                if (f.isDirectory && depth < maxDepth && seen.add(f.absolutePath)) q.add(f to (depth + 1))
            }
        }
        return null
    }

    private fun rememberRecent(uri: String, name: String, path: String?) {
        val arr = try { JSONArray(prefs.getString("recent", "[]")) } catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        next.put(JSONObject().put("uri", uri).put("name", name).put("path", path ?: ""))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("uri") != uri) next.put(o)
            if (next.length() >= 8) break
        }
        prefs.edit().putString("recent", next.toString()).apply()
    }

    // ---- llama-server lifecycle ----
    private fun startServer() {
        val myGen = genMain.incrementAndGet()
        val path = modelRealPath
        if (path.isNullOrBlank() || !File(path).canRead()) {
            notifyUiStatus(false, "No readable model")
            return
        }
        notifyUiStatus(false, "Loading ${File(path).name}…")

        thread {
            try {
                val exe = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
                if (!exe.exists()) {
                    notifyUiStatus(false, "Missing libllama_server.so")
                    return@thread
                }

                val ramGb = try {
                    val mi = ActivityManager.MemoryInfo()
                    (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
                    mi.totalMem / (1024L * 1024L * 1024L)
                } catch (_: Exception) { 8L }
                val ctxCap = if (ramGb >= 11) 32768 else 16384
                val nCtx = prefs.getInt("n_ctx", 8192).coerceIn(1024, ctxCap)
                val nThreads = prefs.getInt("n_threads", 4).coerceIn(1, 16)
                val gpuLayers = prefs.getInt("gpu_layers", 0).coerceIn(0, 999)

                fun buildArgs(forceSafeKv: Boolean): List<String> {
                    val safe = forceSafeKv || safeKv || prefs.getBoolean("kv_safe", false)
                    val a = mutableListOf(
                        exe.path, "-m", path,
                        "--host", "127.0.0.1", "--port", PORT.toString(),
                        "-c", nCtx.toString(), "-t", nThreads.toString(),
                        "--jinja", "-np", "1",
                        "-fa", if (safe) "auto" else "on",
                        "-ctk", if (safe) "f16" else "q8_0",
                        "-ctv", if (safe) "f16" else "q8_0",
                        "--api-key", localKey
                    )
                    if (gpuLayers > 0) { a.add("-ngl"); a.add(gpuLayers.toString()) }
                    return a
                }

                var retried = false
                var p: Process
                synchronized(serverLock) {
                    if (genMain.get() != myGen) return@thread
                    stopProc(proc); proc = null
                    p = launchWatched(buildArgs(false))
                    proc = p
                }

                fun attachLogger(pr: Process) = thread {
                    try {
                        pr.inputStream.bufferedReader().forEachLine { line ->
                            synchronized(logBuf) {
                                if (logBuf.length > 6000) logBuf.delete(0, logBuf.length - 3000)
                                logBuf.append(line).append('\n')
                            }
                        }
                    } catch (_: Exception) {}
                }
                attachLogger(p)

                repeat(300) {
                    if (genMain.get() != myGen) return@thread
                    if (!p.isAlive) {
                        if (!safeKv && !retried) {
                            retried = true
                            safeKv = true
                            notifyUiStatus(false, "Retrying with f16 KV cache…")
                            synchronized(serverLock) {
                                if (genMain.get() != myGen) return@thread
                                p = launchWatched(buildArgs(true))
                                proc = p
                            }
                            attachLogger(p)
                            return@repeat
                        }
                        if (retried) safeKv = false
                        notifyUiStatus(false, "Server exited (${p.exitValue()})")
                        return@thread
                    }
                    try {
                        val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
                        c.connectTimeout = 500; c.readTimeout = 500
                        if (c.responseCode == 200) {
                            if (retried) prefs.edit().putBoolean("kv_safe", true).apply()
                            notifyUiStatus(true, "Ready · ${File(path).name}")
                            return@thread
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(1000)
                }
                notifyUiStatus(false, "Startup timed out")
            } catch (e: Exception) {
                notifyUiStatus(false, "Error: ${e.message}")
            }
        }
    }

    private fun notifyUiStatus(ready: Boolean, status: String) {
        serverReady = ready
        serverStatus = status
        runOnUiThread {
            web.evaluateJavascript(
                "window.__onNativeStatus && window.__onNativeStatus(${ready}, ${JSONObject.quote(status)})",
                null
            )
        }
    }

    override fun onDestroy() {
        genMain.incrementAndGet()
        killNow()
        try { proc?.destroy() } catch (_: Exception) {}
        try { modelPfd?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun setBackActive(active: Boolean) { backActive = active }

        @JavascriptInterface
        fun localKey(): String = this@MainActivity.localKey

        @JavascriptInterface
        fun info(): String = JSONObject()
            .put("files", filesOk())
            .put("root", rootDir().path)
            .put("base", Environment.getExternalStorageDirectory().path)
            .put("port", PORT)
            .put("server_ready", serverReady && proc?.isAlive == true)
            .put("server_status", serverStatus)
            .put("model_name", prefs.getString("model_name", "") ?: "")
            .put("model_path", modelRealPath ?: "")
            .put("n_ctx", prefs.getInt("n_ctx", 8192))
            .put("n_threads", prefs.getInt("n_threads", 4))
            .put("gpu_layers", prefs.getInt("gpu_layers", 0))
            .put("recent", try { JSONArray(prefs.getString("recent", "[]")) } catch (_: Exception) { JSONArray() })
            .toString()

        @JavascriptInterface
        fun pickModel() { runOnUiThread { pickModel() } }

        @JavascriptInterface
        fun useModel(uriStr: String) {
            notifyUiStatus(false, "Switching model…")
            thread {
                try {
                    openFromUri(Uri.parse(uriStr))
                    val name = displayName(Uri.parse(uriStr))
                    prefs.edit()
                        .putString("model_uri", uriStr)
                        .putString("model_name", name)
                        .putString("model_path", modelRealPath)
                        .apply()
                    startServer()
                } catch (e: Exception) {
                    notifyUiStatus(false, "Failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun setEngine(nCtx: Int, nThreads: Int, gpuLayers: Int) {
            prefs.edit()
                .putInt("n_ctx", nCtx.coerceIn(1024, 32768))
                .putInt("n_threads", nThreads.coerceIn(1, 16))
                .putInt("gpu_layers", gpuLayers.coerceIn(0, 999))
                .apply()
            if (modelRealPath != null) startServer()
        }

        @JavascriptInterface
        fun grantFiles() { runOnUiThread { askAllFilesAccess() } }

        @JavascriptInterface
        fun setRoot(path: String): String {
            val d = File(path.ifBlank { Environment.getExternalStorageDirectory().path })
            if (!d.isDirectory) return JSONObject().put("ok", false).put("error", "Not a folder").toString()
            prefs.edit().putString("root", d.canonicalPath).apply()
            return JSONObject().put("ok", true).put("root", d.canonicalPath).toString()
        }

        @JavascriptInterface
        fun call(id: Int, op: String, argsJson: String) {
            thread {
                try {
                    if (!filesOk()) throw ToolError("Grant All Files Access in Settings first.")
                    val out = FileTools(rootDir()).run(op, JSONObject(argsJson))
                    reply(id, true, out.toString())
                } catch (e: Throwable) {
                    reply(id, false, e.message ?: e.javaClass.simpleName)
                }
            }
        }

        private fun reply(id: Int, ok: Boolean, payload: String) {
            runOnUiThread {
                web.evaluateJavascript("window.__nat($id,$ok,${JSONObject.quote(payload)})", null)
            }
        }
    }
}
