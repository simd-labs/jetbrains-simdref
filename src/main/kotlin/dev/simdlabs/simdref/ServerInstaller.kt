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
    const val LOCK_MAX_AGE_MS: Long = 30 * 60 * 1000
    const val PROCESS_TIMEOUT_MS: Long = 10 * 60 * 1000

    /** True when [stamp] is missing or its age (now minus stamp) passes the interval. */
    fun stampIsStale(lastCheckMillis: Long?, nowMillis: Long): Boolean =
        lastCheckMillis == null || nowMillis - lastCheckMillis >= UPDATE_CHECK_INTERVAL_MS

    /** Milliseconds since epoch, or null when the metadata is unreadable. */
    fun fileAgeMillis(file: Path): Long? = try {
        Files.getLastModifiedTime(file).toMillis()
    } catch (_: Exception) {
        null
    }

    /**
     * Upgrades the plugin-managed simdref copy once a day, in the background.
     * On a version change the simdref server restarts in every open project,
     * because all projects share the one managed copy. A PATH install is never
     * touched: Provider calls this only for a managed binary.
     */
    fun maybeUpgradeInBackground(project: Project) {
        if (!Files.isExecutable(binDir().resolve("simdref-lsp$exe"))) return
        if (!upgrading.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Upgrading simdref", false) {
            override fun run(indicator: ProgressIndicator) {
                // All open projects share the one install, so each restarts.
                if (!upgradeOnce()) return
                for (p in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) restartOnEdt(p)
            }

            override fun onThrowable(error: Throwable) {
                log.warn("simdref auto-update failed", error)
            }

            override fun onFinished() {
                upgrading.set(false)
            }
        }.queue()
    }

    /**
     * The synchronous auto-update body. Two IDE processes can upgrade the one
     * shared copy at the same time, so the update.lock file gates every
     * process: a fresh lock means skip, a lock older than [LOCK_MAX_AGE_MS]
     * is a leftover and is taken over. Inside the lock, the 24 h stamp is
     * written before the upgrade, so an offline day retries tomorrow, not on
     * every file open. The upgrade always runs `isa vaddps --short` once:
     * the refresh costs 0.4 s and downloads nothing when simdref is current,
     * so the version comparison only gates the restart. Returns true only
     * when the version changed, so the caller restarts the server.
     */
    internal fun upgradeOnce(): Boolean {
        val lock = home().resolve("update.lock")
        try {
            Files.write(lock, byteArrayOf(), java.nio.file.StandardOpenOption.CREATE_NEW)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            val age = fileAgeMillis(lock)
            if (age != null && System.currentTimeMillis() - age < LOCK_MAX_AGE_MS) return false
            try {
                Files.delete(lock)
                Files.write(lock, byteArrayOf(), java.nio.file.StandardOpenOption.CREATE_NEW)
            } catch (e2: Exception) {
                log.warn("simdref auto-update lock takeover failed", e2)
                return false
            }
        } catch (e: Exception) {
            log.warn("simdref auto-update lock failed", e)
            return false
        }
        try {
            val stamp = home().resolve("last-update-check")
            val stampAge = if (Files.exists(stamp)) fileAgeMillis(stamp) else null
            if (!stampIsStale(stampAge, System.currentTimeMillis())) return false
            try {
                Files.writeString(stamp, Instant.now().toString())
            } catch (e: Exception) {
                log.warn("simdref auto-update stamp failed", e)
                return false
            }
            val dir = home()
            val env = uvEnv(dir)
            val isa = binDir().resolve("isa$exe").toString()
            val before = runOutput(listOf(isa, "--version"), env)
            val upgrade = runLogged(listOf(dir.resolve("uv$exe").toString(), "tool", "upgrade", "simdref"), env)
            if (upgrade.isNotEmpty()) log.warn("simdref auto-update upgrade failed: ${upgrade.takeLast(500)}")
            // A nonzero refresh is logged, not hidden: it also appears in the idea log.
            val refresh = runLogged(listOf(isa, "vaddps", "--short"), env)
            if (refresh.isNotEmpty()) log.warn("simdref auto-update refresh failed: ${refresh.takeLast(500)}")
            val after = runOutput(listOf(isa, "--version"), env)
            // Restart only when the after-version is readable and differs.
            return after.isNotEmpty() && after != before
        } finally {
            try {
                Files.deleteIfExists(lock)
            } catch (e: Exception) {
                log.warn("simdref auto-update lock cleanup failed", e)
            }
        }
    }

    private fun restartOnEdt(project: Project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            // ponytail: replacement API starts in 2026.2; migrate when 2026.1 support ends
            @Suppress("DEPRECATION")
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(SimdrefLspServerSupportProvider::class.java)
        }
    }

    /** Empty output on a non-zero exit or timeout, so a failed probe never counts as a version. */
    private fun runOutput(cmd: List<String>, env: Map<String, String>): String =
        runLogged(cmd, env, "")

    /**
     * Runs [cmd] with a [PROCESS_TIMEOUT_MS] limit; a timeout destroys the
     * process. Returns the merged output on exit 0, the output (or a marker)
     * otherwise, so the caller logs it. [failureOutput] replaces the failure
     * marker for the caller that wants the empty-on-failure probe shape.
     */
    private fun runLogged(cmd: List<String>, env: Map<String, String>, failureOutput: String? = null): String {
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
        var out = ""
        val reader = Thread {
            out = try {
                p.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                ""
            }
        }
        reader.isDaemon = true
        reader.start()
        val done = p.waitFor(PROCESS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroyForcibly()
            reader.join(5_000)
            return failureOutput ?: "timeout after ${PROCESS_TIMEOUT_MS} ms"
        }
        reader.join(5_000)
        if (p.exitValue() == 0) return if (failureOutput == null) "" else out
        return failureOutput ?: out
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
