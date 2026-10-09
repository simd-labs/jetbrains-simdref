package dev.simdlabs.simdref

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real ServerInstaller.upgradeOnce with fake uv and isa scripts
 * that log every argv. The system path is swapped through the PathManager
 * field, so home() points at a temp dir. No copy of the production logic
 * lives here: the test only stamps, calls, and reads the argv log.
 */
class UpgradeOrchestrationTest {
    private fun writeScript(path: Path, body: String) {
        Files.writeString(path, body)
        if (!com.intellij.openapi.util.SystemInfo.isWindows) {
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

    /**
     * Builds a fake plugin home and runs the real upgradeOnce.
     * [versionAfterUpgrade] is what the fake isa prints after uv runs;
     * null makes the fake isa fail on the second `--version` call, the
     * unreadable-after-upgrade case; uv still bumps the version file.
     * [stampAgeMillis] null writes no stamp; otherwise sets the stamp mtime
     * that far in the past. Returns the upgradeOnce result and the argv log.
     */
    private fun runScenario(versionAfterUpgrade: String?, stampAgeMillis: Long?): Pair<Boolean, List<String>> {
        val dir = Files.createTempDirectory("simdref-orch")
        val home = dir.resolve("home/simdref")
        val bin = home.resolve("bin")
        Files.createDirectories(bin)
        val log = dir.resolve("argv.log")
        Files.writeString(log, "")
        // Count isa --version calls; null versionAfterUpgrade fails the second.
        Files.writeString(dir.resolve("isa-count"), "0")
        val failSecond = if (versionAfterUpgrade == null) "true" else "false"
        writeScript(
            bin.resolve("isa"),
            """#!/bin/sh
echo "isa ${'$'}@" >> "$log"
if [ "${'$'}1" = "--version" ]; then
  n=${'$'}(cat "${dir}/isa-count"); n=${'$'}((n+1)); echo "${'$'}n" > "${dir}/isa-count"
  if [ "${'$'}n" = "2" ] && [ "$failSecond" = "true" ]; then exit 1; fi
  cat "${dir}/version.txt" 2>/dev/null
fi
exit 0
"""
        )
        writeScript(bin.resolve("simdref-lsp"), "#!/bin/sh\nexit 0\n")
        // uv always bumps the version file; the unreadable case uses 0.3.2.
        val bump = "printf '%s\\n' \"${versionAfterUpgrade ?: "0.3.2"}\" > \"${dir}/version.txt\""
        writeScript(
            home.resolve("uv"),
            "#!/bin/sh\necho \"uv \$@\" >> \"$log\"\n$bump\nexit 0\n"
        )
        Files.writeString(dir.resolve("version.txt"), "0.3.1\n")
        if (stampAgeMillis != null) {
            val stamp = home.resolve("last-update-check")
            Files.writeString(stamp, "stamp\n")
            assertTrue("stamp mtime set", stamp.toFile().setLastModified(System.currentTimeMillis() - stampAgeMillis))
        }
        val old = swapSystemPath(dir.resolve("home"))
        val changed = try {
            ServerInstaller.upgradeOnce()
        } finally {
            restoreSystemPath(old)
        }
        return Pair(changed, Files.readAllLines(log))
    }

    @Test
    fun staleStampRunsUpgrade() {
        val (_, lines) = runScenario("0.3.2", ServerInstaller.UPDATE_CHECK_INTERVAL_MS + 1_000)
        assertTrue("upgrade must run on a stale stamp: $lines", lines.any { it == "uv tool upgrade simdref" })
    }

    @Test
    fun missingStampRunsUpgrade() {
        val (_, lines) = runScenario("0.3.2", null)
        assertTrue("upgrade must run without a stamp: $lines", lines.any { it == "uv tool upgrade simdref" })
    }

    @Test
    fun freshStampRunsNothing() {
        val (_, lines) = runScenario("0.3.2", 1_000)
        assertEquals("a fresh stamp runs nothing: $lines", emptyList<String>(), lines)
    }

    @Test
    fun sameVersionSkipsRefresh() {
        val (_, lines) = runScenario("0.3.1", null)
        assertTrue(lines.any { it == "uv tool upgrade simdref" })
        assertEquals("no vaddps on the same version: $lines", 0, lines.count { it.contains("vaddps") })
    }

    @Test
    fun changedVersionRefreshesOnce() {
        val (_, lines) = runScenario("0.3.2", null)
        assertEquals("one refresh on a version change: $lines", 1, lines.count { it == "isa vaddps --short" })
    }

    @Test
    fun unreadableVersionAfterUpgradeSkipsRefresh() {
        val (changed, lines) = runScenario(null, null)
        assertEquals("no refresh when isa --version fails: $lines", 0, lines.count { it.contains("vaddps") })
        assertFalse("no restart on an unreadable version", changed)
    }

    @Test
    fun pathInstallIsNeverManaged() {
        assertFalse("/usr/local/bin/simdref-lsp should never upgrade", ServerInstaller.managesBin("/usr/local/bin/simdref-lsp"))
        assertTrue(ServerInstaller.managesBin(ServerInstaller.privateBinPrefix() + "simdref-lsp"))
    }
}
