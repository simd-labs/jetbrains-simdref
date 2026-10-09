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
     * Builds a fake plugin home and runs the real upgradeOnce.
     * [versionAfterUpgrade] is what the fake isa prints after uv runs;
     * null makes the fake isa fail on the second `--version` call, the
     * unreadable-after-upgrade case; uv still bumps the version file.
     * [failRefresh] makes `isa vaddps --short` exit 1. [failUpgrade] makes
     * the fake uv exit 1 without changing the version file.
     * [markerOffsetDays] 0 plants today's marker, a negative value plants a
     * past day's, null plants none. Returns the upgradeOnce result, the argv
     * log, and the marker names left in the home dir.
     */
    private fun runScenario(
        versionAfterUpgrade: String?,
        markerOffsetDays: Long?,
        failRefresh: Boolean = false,
        failUpgrade: Boolean = false,
    ): Triple<Boolean, List<String>, List<String>> {
        val dir = Files.createTempDirectory("simdref-orch")
        val home = dir.resolve("home/simdref")
        val bin = home.resolve("bin")
        Files.createDirectories(bin)
        val log = dir.resolve("argv.log")
        Files.writeString(log, "")
        // Count isa --version calls; null versionAfterUpgrade fails the second.
        Files.writeString(dir.resolve("isa-count"), "0")
        val failSecond = if (versionAfterUpgrade == null) "true" else "false"
        val failVaddps = if (failRefresh) "true" else "false"
        writeScript(
            bin.resolve("isa"),
            """#!/bin/sh
echo "isa ${'$'}@" >> "$log"
if [ "${'$'}1" = "--version" ]; then
  n=${'$'}(cat "${dir}/isa-count"); n=${'$'}((n+1)); echo "${'$'}n" > "${dir}/isa-count"
  if [ "${'$'}n" = "2" ] && [ "$failSecond" = "true" ]; then exit 1; fi
  cat "${dir}/version.txt" 2>/dev/null
fi
if [ "${'$'}1" = "vaddps" ] && [ "$failVaddps" = "true" ]; then
  echo "refresh failed" >&2
  exit 1
fi
exit 0
"""
        )
        writeScript(bin.resolve("simdref-lsp"), "#!/bin/sh\nexit 0\n")
        // uv always bumps the version file; the unreadable case uses 0.3.2.
        val bump = "printf '%s\\n' \"${versionAfterUpgrade ?: "0.3.2"}\" > \"${dir}/version.txt\""
        // A failed upgrade exits 1 and leaves the version file alone.
        val uvBody = if (failUpgrade) "echo 'uv failed' >&2\nexit 1" else "$bump\nexit 0"
        writeScript(
            home.resolve("uv"),
            "#!/bin/sh\necho \"uv \$@\" >> \"$log\"\n$uvBody\n"
        )
        Files.writeString(dir.resolve("version.txt"), "0.3.1\n")
        if (markerOffsetDays != null) {
            Files.createFile(home.resolve("update-" + (today() + markerOffsetDays)))
        }
        val old = swapSystemPath(dir.resolve("home"))
        val changed = try {
            ServerInstaller.upgradeOnce()
        } finally {
            restoreSystemPath(old)
        }
        val markers = Files.newDirectoryStream(home, "update-*").use { s -> s.map { it.fileName.toString() } }
        return Triple(changed, Files.readAllLines(log), markers)
    }

    @Test
    fun todaysMarkerRunsNothing() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (changed, lines, _) = runScenario("0.3.2", 0)
        assertEquals("today's marker runs nothing: $lines", emptyList<String>(), lines)
        assertFalse(changed)
    }

    @Test
    fun noMarkerRunsAndCleansOldMarkers() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (_, lines, markers) = runScenario("0.3.2", -1)
        assertTrue("no marker of today runs the upgrade: $lines", lines.any { it == "uv tool upgrade simdref" })
        assertTrue("today's marker exists: $markers", markers.contains("update-" + today()))
        assertFalse("yesterday's marker is removed: $markers", markers.contains("update-" + (today() - 1)))
    }

    @Test
    fun sameVersionStillRefreshesButNoRestart() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (changed, lines, _) = runScenario("0.3.1", null)
        assertTrue(lines.any { it == "uv tool upgrade simdref" })
        assertEquals("the refresh always runs: $lines", 1, lines.count { it == "isa vaddps --short" })
        assertFalse("an unchanged version must not restart", changed)
    }

    @Test
    fun changedVersionReportsRestart() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (changed, lines, _) = runScenario("0.3.2", null)
        assertTrue("a changed version must restart", changed)
        assertEquals("one refresh on a version change: $lines", 1, lines.count { it == "isa vaddps --short" })
    }

    @Test
    fun unreadableVersionAfterUpgradeMeansNoRestart() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (changed, _, _) = runScenario(null, null)
        assertFalse("no restart on an unreadable version", changed)
    }

    @Test
    fun refreshFailureKeepsUpgradeAndRestart() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (changed, lines, _) = runScenario("0.3.2", null, failRefresh = true)
        assertTrue("the upgrade still ran: $lines", lines.any { it == "uv tool upgrade simdref" })
        assertTrue("a nonzero refresh is logged only; the version change still restarts", changed)
    }

    @Test
    fun failedUpgradeStillRefreshesButNoRestart() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (changed, lines, _) = runScenario("0.3.1", null, failUpgrade = true)
        assertTrue("the upgrade attempt is logged: $lines", lines.any { it == "uv tool upgrade simdref" })
        assertEquals("the refresh still runs after a failed upgrade: $lines", 1, lines.count { it == "isa vaddps --short" })
        assertFalse("a failed upgrade changes nothing; no restart", changed)
    }

    @Test
    fun pathInstallIsNeverManaged() {
        assertFalse("/usr/local/bin/simdref-lsp should never upgrade", ServerInstaller.managesBin("/usr/local/bin/simdref-lsp"))
        assertTrue(ServerInstaller.managesBin(ServerInstaller.privateBinPrefix() + "simdref-lsp"))
    }

    /**
     * F2: after an upgrade the restart runs only when find() resolves to the
     * managed install. The CI PATH has no simdref-lsp, so find() falls back
     * to the swapped bin dir. Mutation: drop the managesBin gate in
     * upgradeRestartApplies (return true) and noManagedBinNoRestart FAILS.
     */
    @Test
    fun noManagedBinNoRestart() {
        val dir = Files.createTempDirectory("simdref-restart")
        val home = dir.resolve("home/simdref")
        Files.createDirectories(home.resolve("bin"))
        val old = swapSystemPath(dir.resolve("home"))
        try {
            assertFalse("no managed bin, no restart", ServerInstaller.upgradeRestartApplies())
        } finally {
            restoreSystemPath(old)
        }
    }

    @Test
    fun managedBinRestarts() {
        val dir = Files.createTempDirectory("simdref-restart")
        val home = dir.resolve("home/simdref")
        Files.createDirectories(home.resolve("bin"))
        writeScript(home.resolve("bin/simdref-lsp"), "#!/bin/sh\nexit 0\n")
        val old = swapSystemPath(dir.resolve("home"))
        try {
            assertTrue("the managed bin restarts", ServerInstaller.upgradeRestartApplies())
        } finally {
            restoreSystemPath(old)
        }
    }
}
