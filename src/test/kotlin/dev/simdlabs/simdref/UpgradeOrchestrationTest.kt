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
 * lives here: the test only stamps, locks, calls, and reads the argv log.
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

    /**
     * Builds a fake plugin home and runs the real upgradeOnce.
     * [versionAfterUpgrade] is what the fake isa prints after uv runs;
     * null makes the fake isa fail on the second `--version` call, the
     * unreadable-after-upgrade case; uv still bumps the version file.
     * [failRefresh] makes `isa vaddps --short` exit 1. [failUpgrade] makes
     * the fake uv exit 1 without changing the version file.
     * [stampAgeMillis] null writes no stamp; otherwise sets the stamp mtime
     * that far in the past. [lockAgeMillis] null writes no lock; otherwise
     * plants update.lock with that mtime before the call. Returns the
     * upgradeOnce result, the argv log, and the leftover lock flag.
     */
    private fun runScenario(
        versionAfterUpgrade: String?,
        stampAgeMillis: Long?,
        failRefresh: Boolean = false,
        failUpgrade: Boolean = false,
        lockAgeMillis: Long? = null,
    ): Triple<Boolean, List<String>, Boolean> {
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
        if (stampAgeMillis != null) {
            val stamp = home.resolve("last-update-check")
            Files.writeString(stamp, "stamp\n")
            assertTrue("stamp mtime set", stamp.toFile().setLastModified(System.currentTimeMillis() - stampAgeMillis))
        }
        if (lockAgeMillis != null) {
            val lock = home.resolve("update.lock")
            Files.write(lock, byteArrayOf())
            assertTrue("lock mtime set", lock.toFile().setLastModified(System.currentTimeMillis() - lockAgeMillis))
        }
        val old = swapSystemPath(dir.resolve("home"))
        val changed = try {
            ServerInstaller.upgradeOnce()
        } finally {
            restoreSystemPath(old)
        }
        return Triple(changed, Files.readAllLines(log), Files.exists(home.resolve("update.lock")))
    }

    @Test
    fun staleStampRunsUpgradeThenRefresh() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (_, lines, _) = runScenario("0.3.2", ServerInstaller.UPDATE_CHECK_INTERVAL_MS + 1_000)
        assertTrue("upgrade must run on a stale stamp: $lines", lines.any { it == "uv tool upgrade simdref" })
        assertEquals("one refresh after the upgrade: $lines", 1, lines.count { it == "isa vaddps --short" })
    }

    @Test
    fun missingStampRunsUpgrade() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (_, lines, _) = runScenario("0.3.2", null)
        assertTrue("upgrade must run without a stamp: $lines", lines.any { it == "uv tool upgrade simdref" })
    }

    @Test
    fun freshStampRunsNothing() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (_, lines, _) = runScenario("0.3.2", 1_000)
        assertEquals("a fresh stamp runs nothing: $lines", emptyList<String>(), lines)
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
    fun freshLockRunsNothing() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (changed, lines, lockLeft) = runScenario("0.3.2", null, lockAgeMillis = 1_000)
        assertEquals("a fresh lock runs nothing: $lines", emptyList<String>(), lines)
        assertFalse(changed)
        assertTrue("the fresh lock is not ours; it stays", lockLeft)
    }

    @Test
    fun staleLockIsTakenOver() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (_, lines, lockLeft) = runScenario("0.3.2", null, lockAgeMillis = ServerInstaller.LOCK_MAX_AGE_MS + 1_000)
        assertTrue("a stale lock is taken over and the upgrade runs: $lines", lines.any { it == "uv tool upgrade simdref" })
        assertFalse("the taken lock is removed at the end", lockLeft)
    }

    @Test
    fun lockRemovedAfterAFailure() {
        assumeFalse("the fake uv and isa scripts are POSIX sh; no Windows CI exists", SystemInfo.isWindows)
        val (changed, _, lockLeft) = runScenario(null, null, failRefresh = true)
        assertFalse(changed)
        assertFalse("the lock is removed even when the refresh fails", lockLeft)
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
}
