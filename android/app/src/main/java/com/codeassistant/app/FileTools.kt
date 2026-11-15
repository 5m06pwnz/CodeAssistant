package com.codeassistant.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToolError(msg: String) : Exception(msg)

class FileTools(rootDir: File) {
    private val root: File = rootDir.canonicalFile
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun rel(f: File): String = if (f == root) "." else f.relativeTo(root).path

    fun safe(p: String?): File {
        val s = (p ?: ".").trim().ifEmpty { "." }
        val f = if (s.startsWith("/")) File(s) else File(root, s)
        val c = f.canonicalFile
        if (c != root && !c.path.startsWith(root.path + File.separator))
            throw ToolError("refused: $s is outside workspace (${root.path})")
        return c
    }

    private class Ent(val f: File, val dir: Boolean, val size: Long, val mt: Long, val lname: String)
    private fun ent(f: File): Ent = try {
        val at = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        Ent(f, at.isDirectory, if (at.isDirectory) 0L else at.size(), at.lastModifiedTime().toMillis(), f.name.lowercase())
    } catch (_: Exception) {
        val d = f.isDirectory
        Ent(f, d, if (d) 0L else f.length(), f.lastModified(), f.name.lowercase())
    }

    private fun entryJson(e: Ent): JSONObject = JSONObject()
        .put("n", e.f.name).put("p", rel(e.f)).put("d", e.dir).put("s", e.size)
        .put("m", dateFmt.format(Date(e.mt))).put("mt", e.mt)

    private fun result(text: String, data: JSONObject? = null): JSONObject =
        JSONObject().put("text", text).apply { if (data != null) put("data", data) }

    fun listDir(a: JSONObject): JSONObject {
        val d = safe(a.optString("path", "."))
        if (!d.isDirectory) throw ToolError("not a directory: ${rel(d)}")
        val limit = a.optInt("limit", 300).coerceIn(1, 5000)
        val off = a.optInt("offset", 0).coerceAtLeast(0)
        val hidden = a.optBoolean("hidden", false)
        val kids = (d.listFiles() ?: throw ToolError("cannot read ${rel(d)}"))
            .filter { hidden || !it.name.startsWith(".") }.map { ent(it) }
        val sorted = when (a.optString("sort", "name")) {
            "size" -> kids.sortedByDescending { if (it.dir) -1L else it.size }
            "date" -> kids.sortedByDescending { it.mt }
            else -> kids.sortedWith(compareBy({ !it.dir }, { it.lname }))
        }
        val page = sorted.drop(off).take(limit)
        val more = (sorted.size - off - page.size).coerceAtLeast(0)
        val data = JSONObject()
            .put("entries", JSONArray(page.map { entryJson(it) }))
            .put("total", sorted.size)
            .put("more", more)
        return result("${rel(d)}: ${sorted.size} entries", data)
    }

    fun find(a: JSONObject): JSONObject {
        val q = a.optString("query").trim()
        if (q.isEmpty()) throw ToolError("query is required")
        val base = safe(a.optString("path", "."))
        val max = a.optInt("max", 60).coerceIn(1, 150)
        val re = if ('*' in q || '?' in q)
            Regex(q.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString("", "^", "$"), RegexOption.IGNORE_CASE)
        else Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
        val out = ArrayList<Ent>()
        var seen = 0
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles() ?: return
            for (k in kids) {
                if (out.size >= max || seen > 30000) return
                seen++
                if (k.name.startsWith(".")) continue
                if (re.containsMatchIn(k.name)) out.add(ent(k))
                if (k.isDirectory && depth < 8 && !Files.isSymbolicLink(k.toPath())) walk(k, depth + 1)
            }
        }
        walk(base, 1)
        return result("${out.size} matches", JSONObject().put("entries", JSONArray(out.map { entryJson(it) })).put("total", out.size))
    }

    fun searchText(a: JSONObject): JSONObject {
        val q = a.optString("query").trim()
        if (q.isEmpty()) throw ToolError("query is required")
        val base = safe(a.optString("path", "."))
        val max = a.optInt("max", 80).coerceIn(1, 200)
        val skipDirs = setOf("node_modules", "build", ".git", "__pycache__")
        val hits = JSONArray()
        var scanned = 0
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles() ?: return
            for (k in kids) {
                if (hits.length() >= max || scanned > 5000) return
                if (k.name.startsWith(".") || k.name in skipDirs) continue
                if (k.isDirectory) {
                    if (depth < 10 && !Files.isSymbolicLink(k.toPath())) walk(k, depth + 1)
                    continue
                }
                if (k.length() > 1_000_000L) continue
                scanned++
                try {
                    val head = ByteArray(512)
                    val n = k.inputStream().use { it.read(head) }
                    if ((0 until maxOf(n, 0)).any { head[it].toInt() == 0 }) continue
                    k.bufferedReader(Charsets.UTF_8).use { rd ->
                        var ln = 0
                        while (true) {
                            val line = rd.readLine() ?: break
                            ln++
                            if (line.contains(q, ignoreCase = true)) {
                                hits.put(JSONObject().put("p", rel(k)).put("l", ln).put("t", line.trim().take(160)))
                                if (hits.length() >= max) break
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        walk(base, 1)
        return result("${hits.length()} hits", JSONObject().put("hits", hits))
    }

    fun read(a: JSONObject): JSONObject {
        val f = safe(a.optString("path"))
        if (!f.isFile) throw ToolError("not a file: ${rel(f)}")
        if (f.length() > 2L * 1024 * 1024) throw ToolError("file too large (>2MB)")
        val bytes = f.readBytes()
        if (bytes.any { it.toInt() == 0 }) {
            return result("binary file", JSONObject().put("binary", true).put("size", f.length()))
        }
        return result(String(bytes, Charsets.UTF_8), JSONObject().put("mtime", f.lastModified()).put("size", f.length()))
    }

    fun write(a: JSONObject): JSONObject {
        val path = a.optString("path").trim()
        if (path.isEmpty()) throw ToolError("path is required")
        val content = a.optString("content", "")
        val overwrite = a.optBoolean("overwrite", false)
        val f = safe(path)
        if (f == root) throw ToolError("cannot overwrite workspace root")
        if (f.exists() && !overwrite) throw ToolError("file already exists: ${rel(f)}")
        val expect = a.optLong("expect_mtime", 0L)
        if (expect > 0L && f.exists() && f.lastModified() != expect)
            throw ToolError("CONFLICT: ${rel(f)} changed on disk since opened")
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.ca-tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            f.writeText(content, Charsets.UTF_8)
        }
        return result("wrote ${f.length()} bytes", JSONObject().put("mtime", f.lastModified()).put("size", f.length()))
    }

    fun stat(a: JSONObject): JSONObject {
        val f = safe(a.optString("path"))
        if (!f.exists()) throw ToolError("not found: ${rel(f)}")
        return result(rel(f), JSONObject().put("mtime", f.lastModified()).put("size", if (f.isDirectory) 0L else f.length()).put("dir", f.isDirectory))
    }

    fun mkdir(a: JSONObject): JSONObject {
        val d = safe(a.optString("path"))
        d.mkdirs()
        return result("ok ${rel(d)}/")
    }

    fun rename(a: JSONObject): JSONObject {
        val src = safe(a.optString("path"))
        val name = a.optString("name").trim()
        if (name.isEmpty() || name.contains('/') || name.contains('\\')) throw ToolError("invalid name")
        val dst = File(src.parentFile, name)
        if (dst.exists()) throw ToolError("already exists")
        if (!src.renameTo(dst)) throw ToolError("rename failed")
        return result("renamed", JSONObject().put("path", rel(dst)))
    }

    fun delete(a: JSONObject): JSONObject {
        val f = safe(a.optString("path"))
        if (f == root || !f.exists()) throw ToolError("invalid path")
        f.deleteRecursively()
        return result("deleted ${rel(f)}")
    }

    fun run(op: String, a: JSONObject): JSONObject = when (op) {
        "list_dir" -> listDir(a)
        "find_files" -> find(a)
        "search_text" -> searchText(a)
        "read_file" -> read(a)
        "write_file" -> write(a)
        "stat" -> stat(a)
        "make_dir" -> mkdir(a)
        "rename" -> rename(a)
        "delete" -> delete(a)
        else -> throw ToolError("unknown op: $op")
    }
}
