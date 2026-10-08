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
import java.time.Instant
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
    private val running = AtomicBoolean(false)
    private val upgrading = AtomicBoolean(false)
    private val exe = if (SystemInfo.isWindows) ".exe" else ""

    private fun home(): Path = Path.of(PathManager.getSystemPath(), "simdref")
    private fun binDir(): Path = home().resolve("bin")

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ServerInstaller::class.java)

    /** The prefix that marks the plugin-managed simdref-lsp (rule (a): never upgrade a PATH install). */
    fun privateBinPrefix(): String = binDir().toString() + File.separator

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
        if (!running.compareAndSet(false, true)) return
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
                running.set(false)
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

    const val UPDATE_CHECK_INTERVAL_MS: Long = 24 * 60 * 60 * 1000

    /** True when [stamp] is missing or its age (now minus stamp) passes the interval. */
    fun stampIsStale(lastCheckMillis: Long?, nowMillis: Long): Boolean =
        lastCheckMillis == null || nowMillis - lastCheckMillis >= UPDATE_CHECK_INTERVAL_MS

    /**
     * Upgrades the plugin-managed simdref copy at most once every 24 h. The
     * stamp is written before the upgrade, so an offline day retries tomorrow,
     * not on every file open. A PATH install is never touched. A version change
     * makes isa refresh the catalog once (see simdref cli.py ensure_runtime);
     * an unchanged version downloads nothing.
     */
    fun maybeUpgradeInBackground(project: Project, stamp: Path = home().resolve("last-update-check")) {
        if (!Files.isExecutable(binDir().resolve("simdref-lsp$exe"))) return
        val fresh = try {
            Files.exists(stamp) && !stampIsStale(Files.getLastModifiedTime(stamp).toMillis(), System.currentTimeMillis())
        } catch (e: Exception) {
            log.warn("simdref auto-update check failed", e)
            return
        }
        if (fresh) return
        try {
            Files.writeString(stamp, Instant.now().toString())
        } catch (e: Exception) {
            log.warn("simdref auto-update stamp failed", e)
            return
        }
        if (!upgrading.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Upgrading simdref", false) {
            override fun run(indicator: ProgressIndicator) {
                val dir = home()
                val env = uvEnv(dir)
                val isa = binDir().resolve("isa$exe").toString()
                val before = runOutput(listOf(isa, "--version"), env)
                run(listOf(dir.resolve("uv$exe").toString(), "tool", "upgrade", "simdref"), env)
                if (versionChanged(before, runOutput(listOf(isa, "--version"), env))) {
                    // A lookup triggers ensure_runtime, which re-downloads the catalog
                    // only on a version change. `isa vaddps --short` exits 0.
                    runOutput(listOf(isa, "vaddps", "--short"), env)
                    restartOnEdt(project)
                }
            }

            override fun onThrowable(error: Throwable) {
                log.warn("simdref auto-update failed", error)
            }

            override fun onFinished() {
                upgrading.set(false)
            }
        }.queue()
    }

    /** True when the --version output changed across an upgrade. */
    fun versionChanged(before: String, after: String): Boolean = before != after

    private fun restartOnEdt(project: Project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            // ponytail: replacement API starts in 2026.2; migrate when 2026.1 support ends
            @Suppress("DEPRECATION")
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(SimdrefLspServerSupportProvider::class.java)
        }
    }

    private fun runOutput(cmd: List<String>, env: Map<String, String>): String {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
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
