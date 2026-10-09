package dev.simdlabs.simdref

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * Drives the real ServerInstaller.upgradeOnce with fake uv and isa scripts
 * that log every argv. The system path is swapped through the PathManager
 * field, so home() points at a temp dir. No copy of the production logic
 * lives here: the test only plants markers, calls, and reads the argv log.
 */
class UpgradeOrchestrationTest {
    private fun writeScript(path: Path, body: String) {
        Files.writeString(path, body)
        if (!SystemInfo.isWindows) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    private fun swapSystemPath(newPath: Path): Any? {
        val f = PathManager::class.java.getDeclaredField("ourSystemPath")
        f.isAccessible = true
        val old = f.get(null)
        f.set(null, newPath)
        return old
    }

    private fun restoreSystemPath(old: Any?) {
        val f = PathManager::class.java.getDeclaredField("ourSystemPath")
        f.isAccessible = true
        f.set(null, old)
    }

    private fun today(): Long = System.currentTimeMillis() / 86_400_000L

    /**
     * Builds a fake plugin home and runs the real upgradeOnce. [failRefresh]
     * makes `isa vaddps --short` exit 1. [failUpgrade] makes the fake uv
     * exit 1. [markerOffsetDays] 0 plants today's marker, a negative value
     * plants a past day's, null plants none. Returns the argv log and the
     * marker names left in the home dir.
     */
    private fun runScenario(
        markerOffsetDays: Long?,
        failRefresh: Boolean = false,
        failUpgrade: Boolean = false,
    ): Pair<List<String>, List<String>> {
        val dir = Files.createTempDirectory("simdref-orch")
        val home = dir.resolve("home/simdref")
        val bin = home.resolve("bin")
        Files.createDirectories(bin)
        val log = dir.resolve("argv.log")
        Files.writeString(log, "")
        val failVaddps = if (failRefresh) "true" else "false"
        writeScript(
            bin.resolve("isa"),
            """#!/bin/sh
echo "isa ${'$'}@" >> "$log"
if [ "${'$'}1" = "vaddps" ] && [ "$failVaddps" = "true" ]; then
  echo "refresh failed" >&2
  exit 1
fi
exit 0
"""
        )
        writeScript(bin.resolve("simdref-lsp"), "#!/bin/sh\nexit 0\n")
        val uvBody = if (failUpgrade) "echo 'uv failed' >&2\nexit 1" else "exit 0"
        writeScript(
            home.resolve("uv"),
            "#!/bin/sh\necho \"uv \$@\" >> \"$log\"\n$uvBody\n"
        )
        if (markerOffsetDays != null) {
            Files.createFile(home.resolve("update-" + (today() + markerOffsetDays)))
        }
        val old = swapSystemPath(dir.resolve("home"))
        try {
            ServerInstaller.upgradeOnce()
        } finally {
            restoreSystemPath(old)
        }
        val markers = Files.newDirectoryStream(home, "update-*").use { s -> s.map { it.fileName.toString() } }
        return Pair(Files.readAllLines(log), markers)
    }

    @Test
    fun todaysMarkerRunsNothing() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (lines, _) = runScenario(0)
        assertEquals("today's marker runs nothing: $lines", emptyList<String>(), lines)
    }

    @Test
    fun noMarkerRunsAndCleansOldMarkers() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (lines, markers) = runScenario(-1)
        assertTrue("no marker of today runs the upgrade: $lines", lines.any { it == "uv tool upgrade simdref" })
        assertTrue("the refresh runs after the upgrade: $lines", lines.any { it == "isa vaddps --short" })
        assertTrue("today's marker exists: $markers", markers.contains("update-" + today()))
        assertFalse("yesterday's marker is removed: $markers", markers.contains("update-" + (today() - 1)))
    }

    @Test
    fun failedUpgradeStillRefreshes() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (lines, _) = runScenario(null, failUpgrade = true)
        assertTrue("the upgrade attempt is logged: $lines", lines.any { it == "uv tool upgrade simdref" })
        assertEquals("the refresh still runs after a failed upgrade: $lines", 1, lines.count { it == "isa vaddps --short" })
    }

    @Test
    fun failedRefreshIsLoggedNotFatal() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (lines, _) = runScenario(null, failRefresh = true)
        assertTrue("the upgrade ran: $lines", lines.any { it == "uv tool upgrade simdref" })
        assertTrue("the refresh ran: $lines", lines.any { it == "isa vaddps --short" })
    }

    @Test
    fun pathInstallIsNeverManaged() {
        assertFalse("/usr/local/bin/simdref-lsp should never upgrade", ServerInstaller.managesBin("/usr/local/bin/simdref-lsp"))
        assertTrue(ServerInstaller.managesBin(ServerInstaller.privateBinPrefix() + "simdref-lsp"))
    }
}
