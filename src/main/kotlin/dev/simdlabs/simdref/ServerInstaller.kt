package dev.simdlabs.simdref

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.util.io.Decompressor
import com.intellij.util.system.CpuArch
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

const val DOCS_URL = "https://github.com/simd-labs/simdref"
const val UV_TAG = "0.12.23"
const val NOT_FOUND_MESSAGE =
    "simdref not found. Install it: uv tool install simdref (or pip install simdref), then run isa update. Instructions: $DOCS_URL"

fun sha256Hex(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Parses the first hash token of a "hash  name" checksum file. */
fun parseChecksum(text: String): String = text.trim().split(Regex("\\s+")).first()

/** True when the sha256 of [file] equals [expected], case-insensitive. */
fun checksumMatches(file: Path, expected: String): Boolean =
    sha256Hex(file).equals(expected, ignoreCase = true)

/**
 * Opens [url] with a connect and read timeout of [timeoutMillis] and returns its bytes.
 * Without timeouts a stalled server hangs the download task forever.
 * A timeout throws SocketTimeoutException, which the installer surfaces in the notification.
 */
fun fetchUrlBytes(url: URL, timeoutMillis: Int): ByteArray {
    val conn = url.openConnection()
    conn.connectTimeout = timeoutMillis
    conn.readTimeout = timeoutMillis
    return conn.getInputStream().use { it.readBytes() }
}

/** Reads [url] as UTF-8 text through [fetchUrlBytes]. */
fun fetchUrlText(url: URL, timeoutMillis: Int): String =
    String(fetchUrlBytes(url, timeoutMillis), StandardCharsets.UTF_8)

object ServerInstaller {
    // One guard for install and upgrade: both write the one managed install.
    private val busy = AtomicBoolean(false)
    private val exe = if (SystemInfo.isWindows) ".exe" else ""

    private fun home(): Path = Path.of(PathManager.getSystemPath(), "simdref")
    private fun binDir(): Path = home().resolve("bin")

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ServerInstaller::class.java)

    /** The prefix that marks the plugin-managed simdref-lsp (rule (a): never upgrade a PATH install). */
    fun privateBinPrefix(): String = binDir().toString() + File.separator

    /** True only for the plugin-managed binary; a PATH install is never upgraded. */
    fun managesBin(bin: String): Boolean = bin.startsWith(privateBinPrefix())

    /** All uv writes stay inside [dir]. */
    fun uvEnv(dir: Path): Map<String, String> = mapOf(
        "UV_TOOL_DIR" to dir.resolve("tools").toString(),
        "UV_TOOL_BIN_DIR" to dir.resolve("bin").toString(),
        "UV_PYTHON_INSTALL_DIR" to dir.resolve("python").toString(),
        "UV_CACHE_DIR" to dir.resolve("uv-cache").toString(),
    )

    /** 1. PATH, 2. previous install. Null when neither exists. */
    fun find(): String? {
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "simdref-lsp$exe") }.firstOrNull { it.canExecute() }
        if (onPath != null) return onPath.path
        return binDir().resolve("simdref-lsp$exe").toFile().takeIf { it.canExecute() }?.path
    }

    /** 3. Download uv, install simdref, run isa update. 4. Notify on any failure. Retries on the next file open after a failure. */
    fun installInBackground(project: Project) {
        if (!busy.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Installing simdref", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    install(indicator)
                } catch (e: Exception) {
                    notifyFailure(project, e)
                }
            }

            override fun onSuccess() {
                // ponytail: replacement API starts in 2026.2; migrate when 2026.1 support ends
                @Suppress("DEPRECATION")
                LspServerManager.getInstance(project).stopAndRestartIfNeeded(SimdrefLspServerSupportProvider::class.java)
            }

            override fun onFinished() {
                busy.set(false)
            }
        }.queue()
    }

    private fun install(indicator: ProgressIndicator) {
        val dir = home()
        Files.createDirectories(dir)
        indicator.text = "Downloading uv"
        val uv = downloadUv(dir)
        val env = uvEnv(dir)
        indicator.text = "Running uv tool install simdref"
        run(listOf(uv.toString(), "tool", "install", "simdref"), env)
        indicator.text = "Running isa update"
        run(listOf(binDir().resolve("isa$exe").toString(), "update"), env)
        if (find() == null) error("simdref-lsp is missing in ${binDir()} after install")
    }

    const val PROCESS_TIMEOUT_MS: Long = 10 * 60 * 1000
    private const val OUTPUT_CAP: Int = 64 * 1024

    /**
     * Upgrades the plugin-managed simdref copy once a day, in the background.
     * A new version applies at the next server start. A PATH install is never
     * touched: Provider calls this only for a managed binary.
     */
    fun maybeUpgradeInBackground(project: Project) {
        if (!Files.isExecutable(binDir().resolve("simdref-lsp$exe"))) return
        if (!busy.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Upgrading simdref", false) {
            override fun run(indicator: ProgressIndicator) {
                upgradeOnce()
            }

            override fun onThrowable(error: Throwable) {
                log.warn("simdref auto-update failed", error)
            }

            override fun onFinished() {
                busy.set(false)
            }
        }.queue()
    }

    /**
     * The synchronous auto-update body. Once a day, per UTC day: the
     * update-<day> marker exists or the whole check is skipped. shortcut: a
     * check still running at midnight UTC can overlap the next day's check,
     * add an OS lock if that is ever reported. After the upgrade it always
     * runs `isa vaddps --short`: the call costs ~0.4 s and downloads nothing
     * when the catalog is current, and it downloads the catalog when the
     * version changed. A new simdref version applies at the next server
     * start.
     */
    internal fun upgradeOnce() {
        val dir = home()
        val marker = dir.resolve("update-" + System.currentTimeMillis() / 86_400_000L)
        try {
            Files.createFile(marker)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            return
        } catch (e: Exception) {
            log.warn("simdref auto-update marker failed", e)
            return
        }
        try {
            Files.newDirectoryStream(dir, "update-*").use { stream ->
                for (f in stream) if (f != marker) {
                    try { Files.deleteIfExists(f) } catch (_: Exception) {}
                }
            }
            val env = uvEnv(dir)
            val isa = binDir().resolve("isa$exe").toString()
            val upgrade = runLogged(listOf(dir.resolve("uv$exe").toString(), "tool", "upgrade", "simdref"), env)
            if (upgrade.isNotEmpty()) log.warn("simdref auto-update upgrade failed: ${upgrade.takeLast(500)}")
            // A nonzero refresh is logged, not hidden: it also appears in the idea log.
            val refresh = runLogged(listOf(isa, "vaddps", "--short"), env)
            if (refresh.isNotEmpty()) log.warn("simdref auto-update refresh failed: ${refresh.takeLast(500)}")
        } catch (e: Exception) {
            log.warn("simdref auto-update failed", e)
        }
    }

    /**
     * Runs [cmd] with a [timeoutMs] limit; a timeout kills the process and
     * its descendants, then waits for exit. Returns the merged output on
     * exit 0, the output (or a marker) otherwise, so the caller logs it.
     * [failureOutput] replaces both output and marker for the caller that
     * wants the empty-on-failure probe shape. Output keeps only the last
     * [OUTPUT_CAP] bytes; a runaway writer cannot exhaust memory.
     */
    internal fun runLogged(
        cmd: List<String>,
        env: Map<String, String>,
        failureOutput: String? = null,
        timeoutMs: Long = PROCESS_TIMEOUT_MS,
    ): String {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            log.warn("simdref auto-update process failed to start: ${cmd.take(2).joinToString(" ")}", e)
            return failureOutput ?: "start failed: ${e.message}"
        }
        // Read the output on a daemon thread: a stalled writer must not block
        // the timeout below, and a dead child must not leave a full pipe.
        val buf = java.io.ByteArrayOutputStream()
        var done = false
        var interrupted = false
        val reader = Thread {
            try {
                val input = p.inputStream
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buf) {
                        buf.write(chunk, 0, n)
                        if (buf.size() > OUTPUT_CAP) buf.dropHead()
                    }
                }
            } catch (_: Exception) {
                // the stream closed or the read failed; done either way
            }
        }
        reader.isDaemon = true
        reader.start()
        // Track the descendants while the parent runs: once the parent
        // exits, its children re-parent to init and descendants() on the
        // dead handle returns nothing. The watcher collects what it sees;
        // the finally below kills what is still alive from that list.
        // shortcut: a parent that exits in microseconds orphans a child
        // before the first sample runs; the plugin's real children (uv,
        // isa) run for seconds, so this never fires in production. Switch
        // to a setsid kill if it ever does.
        val handle = p.toHandle()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<ProcessHandle>()
        val watcher = Thread {
            while (handle.isAlive) {
                handle.descendants().forEach { seen.add(it) }
                try { Thread.sleep(5) } catch (_: InterruptedException) { return@Thread }
            }
        }
        watcher.isDaemon = true
        watcher.start()
        try {
            done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Keep the flag clear here: reader.join below throws at once on an
            // interrupted thread. The flag is restored after the finally.
            interrupted = true
        } finally {
            // Always: kill what the watcher last saw, kill the process when
            // it still lives, wait for the exit, and join the reader so no
            // thread or process outlives the call. A reader still alive
            // after its join holds the stream; close it so the read ends.
            seen.forEach { if (it.isAlive) it.destroyForcibly() }
            p.toHandle().descendants().forEach { it.destroyForcibly() }
            if (p.isAlive) {
                p.destroyForcibly()
                p.waitFor()
            }
            reader.join(5_000)
            if (reader.isAlive) {
                try { p.inputStream.close() } catch (_: Exception) {}
                reader.join(1_000)
            }
        }
        val out = synchronized(buf) { buf.toString(Charsets.UTF_8) }
        if (interrupted) {
            Thread.currentThread().interrupt()
            return failureOutput ?: "interrupted"
        }
        if (!done) return failureOutput ?: "timeout after $timeoutMs ms"
        if (p.exitValue() == 0) return if (failureOutput == null) "" else out
        return failureOutput ?: out.ifEmpty { "exit ${p.exitValue()}" }
    }

    /** Drops bytes from the head so the buffer keeps at most [OUTPUT_CAP]. */
    private fun java.io.ByteArrayOutputStream.dropHead() {
        val keep = size() - OUTPUT_CAP / 2
        val all = toByteArray()
        reset()
        write(all, all.size - keep, keep)
    }

    private fun downloadUv(dir: Path): Path = downloadUv(dir, 30_000)

    fun downloadUv(dir: Path, timeoutMillis: Int): Path {
        val uv = dir.resolve("uv$exe")
        if (Files.isExecutable(uv)) return uv
        val arch = if (CpuArch.CURRENT == CpuArch.ARM64) "aarch64" else "x86_64"
        val triple = when {
            SystemInfo.isWindows -> "$arch-pc-windows-msvc"
            SystemInfo.isMac -> "$arch-apple-darwin"
            else -> "$arch-unknown-linux-gnu"
        }
        val suffix = if (SystemInfo.isWindows) "zip" else "tar.gz"
        val base = "https://github.com/astral-sh/uv/releases/download/$UV_TAG/uv-$triple.$suffix"
        val archive = dir.resolve("uv.$suffix")
        val part = dir.resolve("uv.$suffix.part")
        try {
            Files.write(part, fetchUrlBytes(URI(base).toURL(), timeoutMillis))
            val expected = parseChecksum(fetchUrlText(URI("$base.sha256").toURL(), timeoutMillis))
            if (!checksumMatches(part, expected)) error("uv checksum mismatch: expected $expected, got ${sha256Hex(part)}")
            Files.move(part, archive, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(part)
        }
        // The tar.gz holds uv-<triple>/uv; the Windows zip holds uv.exe at its root.
        extractArchive(archive, dir, if (SystemInfo.isWindows) null else "uv-$triple")
        Files.deleteIfExists(archive)
        if (!Files.isExecutable(uv)) error("uv download did not produce $uv")
        return uv
    }

    /**
     * Extracts [archive] (uv-release tar.gz or zip) into [dir].
     * Uses the platform Decompressor, so no external tar is needed on any OS.
     * [stripPrefix] drops one top-level directory from a tar; the uv tarball wraps
     * everything in `uv-<triple>/`. Tar entries keep their exec bits.
     */
    fun extractArchive(archive: Path, dir: Path, stripPrefix: String? = null) {
        if (archive.fileName.toString().endsWith(".zip")) {
            Decompressor.Zip(archive).extract(dir)
            return
        }
        val tar = Decompressor.Tar(archive)
        if (stripPrefix != null) tar.removePrefixPath(stripPrefix)
        tar.extract(dir)
    }

    private fun run(cmd: List<String>, env: Map<String, String>) {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) error("${cmd.take(3).joinToString(" ")} failed: ${out.takeLast(500)}")
    }

    fun notifyFailure(project: Project, e: Throwable) {
        NotificationGroupManager.getInstance().getNotificationGroup("simdref")
            .createNotification(NOT_FOUND_MESSAGE, e.message ?: e.toString(), NotificationType.ERROR)
            .addAction(NotificationAction.createSimpleExpiring("Open instructions") { BrowserUtil.browse(DOCS_URL) })
            .notify(project)
    }
}
