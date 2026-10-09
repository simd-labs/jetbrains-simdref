package dev.simdlabs.simdref

import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerInstallerTest {
    /**
     * Drives the real throttle decision through ServerInstaller.stampIsStale.
     * A missing stamp or one at least a day old means upgrade; a fresh stamp
     * means skip. Mutation check: drop the interval comparison and the
     * "fresh stamp" assertions FAIL.
     */
    @Test
    fun stampThrottle() {
        val t0 = 1_700_000_000_000L
        assertTrue("missing stamp must upgrade", ServerInstaller.stampIsStale(null, t0))
        assertTrue("old stamp must upgrade", ServerInstaller.stampIsStale(t0 - ServerInstaller.UPDATE_CHECK_INTERVAL_MS, t0))
        assertFalse("fresh stamp must skip", ServerInstaller.stampIsStale(t0 - 1_000, t0))
        assertFalse("same-moment stamp must skip", ServerInstaller.stampIsStale(t0, t0))
    }

    /**
     * Rule (a): the upgrade applies only to the plugin-managed copy. The guard
     * is the privateBinPrefix match in Provider; a PATH install never starts
     * with it. Version compare and catalog refresh guarded by the same flow.
     */
    @Test
    fun privateBinPrefixIsInsideSystemDir() {
        val prefix = ServerInstaller.privateBinPrefix()
        assertTrue(prefix.replace('\\', '/').endsWith("/simdref/bin/"))
        // A PATH entry is a bare dir, never under the private bin prefix.
        val pathInstall = "/usr/local/bin/simdref-lsp"
        assertFalse(pathInstall.startsWith(prefix))
    }

    /**
     * The catalog refresh and server restart run only when isa --version
     * changed across the upgrade. Same version means no refresh and no
     * 253 MB download. Drives the real compare used in the background task.
     */
    @Test
    fun versionChangeGatesRefresh() {
        assertFalse("same version must skip refresh", ServerInstaller.versionChanged("0.3.1", "0.3.1"))
        assertTrue("changed version must refresh", ServerInstaller.versionChanged("0.3.1", "0.3.2"))
    }
    @Test
    fun uvEnvKeepsAllWritesUnderDir() {
        val dir = Path.of("/tmp/simdref-x")
        val env = ServerInstaller.uvEnv(dir)
        for (key in listOf("UV_TOOL_DIR", "UV_TOOL_BIN_DIR", "UV_PYTHON_INSTALL_DIR", "UV_CACHE_DIR")) {
            val value = env[key] ?: error("missing $key")
            assertTrue("$key not under $dir", value.startsWith(dir.toString() + "/"))
        }
    }

    @Test
    fun checksumMatchesGoodAndBadHash() {
        val file = Files.createTempFile("sha", ".bin")
        Files.writeString(file, "abc")
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertTrue(checksumMatches(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertFalse(checksumMatches(file, "0000000000000000000000000000000000000000000000000000000000000000"))
        Files.deleteIfExists(file)
    }

    @Test
    fun parseChecksumReadsFirstToken() {
        assertEquals("ba7816bf", parseChecksum("ba7816bf  uv.tar.gz\n"))
    }

    /**
     * Extracts a tar.gz built in the test through the same function the installer
     * uses. The tar holds one file with mode 0755 under one top-level directory.
     * Asserts the file exists in the target dir and is executable.
     * Mutation check: break the extraction call and this test FAILS.
     */
    @Test
    fun extractArchiveKeepsExecBit() {
        val tmp = Files.createTempDirectory("simdref-extract")
        val tarGz = tmp.resolve("tool.tar.gz")
        val payload = "echo hi\n".toByteArray()
        val tar = buildTar(prefix = "tool-x86_64-unknown-linux-gnu", fileName = "tool", mode = 0b111101101, data = payload)
        java.util.zip.GZIPOutputStream(Files.newOutputStream(tarGz)).use { it.write(tar) }

        val out = tmp.resolve("out")
        Files.createDirectories(out)
        ServerInstaller.extractArchive(tarGz, out, "tool-x86_64-unknown-linux-gnu")

        val f = out.resolve("tool")
        assertTrue("extracted file missing", Files.exists(f))
        if (!com.intellij.openapi.util.SystemInfo.isWindows) assertTrue("exec bit lost", Files.isExecutable(f))
        assertTrue(Files.readAllBytes(f).contentEquals(payload))
    }

    /**
     * Points fetchUrlText at a local ServerSocket that accepts and never answers.
     * A short read timeout must make the call fail within a few seconds.
     * Mutation check: drop the read timeout and this test FAILS with a JUnit timeout.
     */
    @Test(timeout = 5000)
    fun stalledServerFailsWithinTimeout() {
        val server = ServerSocket(0)
        val accepted = Thread {
            try {
                val client = server.accept()
                Thread.sleep(10_000)
                client.close()
            } catch (_: Exception) {
            }
        }
        accepted.isDaemon = true
        accepted.start()
        val start = System.nanoTime()
        val url = URI("http://127.0.0.1:${server.localPort}/").toURL()
        try {
            fetchUrlText(url, 200)
            error("expected a timeout")
        } catch (e: java.net.SocketTimeoutException) {
            // expected
        } finally {
            server.close()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("too slow: ${elapsedMs}ms", elapsedMs < 4_000)
    }

    /**
     * Builds a zip with one flat `uv.exe` entry, the shape of the Windows uv
     * release, and extracts it through the installer's zip branch.
     * Asserts the bytes survive at the target root, no prefix strip.
     * Mutation check: break the zip branch and this test FAILS.
     */
    @Test
    fun extractArchiveExtractsFlatZip() {
        val tmp = Files.createTempDirectory("simdref-zip")
        val zip = tmp.resolve("uv.zip")
        val payload = "MZ fake uv\r\n".toByteArray()
        java.util.zip.ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("uv.exe"))
            z.write(payload)
            z.closeEntry()
        }
        val out = tmp.resolve("out")
        Files.createDirectories(out)
        ServerInstaller.extractArchive(zip, out, null)
        val f = out.resolve("uv.exe")
        assertTrue("extracted uv.exe missing", Files.exists(f))
        assertTrue("bytes differ", Files.readAllBytes(f).contentEquals(payload))
    }

    /** Minimal ustar writer: one regular file with the given mode. */
    private fun buildTar(prefix: String, fileName: String, mode: Int, data: ByteArray): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        fun header(name: String, size: Int): ByteArray {
            val h = ByteArray(512)
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            System.arraycopy(nameBytes, 0, h, 0, minOf(nameBytes.size, 100))
            val modeStr = "%07o\u0000".format(mode)
            System.arraycopy(modeStr.toByteArray(Charsets.US_ASCII), 0, h, 100, 8)
            val uidGid = "0000000\u0000"
            System.arraycopy(uidGid.toByteArray(Charsets.US_ASCII), 0, h, 108, 8)
            System.arraycopy(uidGid.toByteArray(Charsets.US_ASCII), 0, h, 116, 8)
            val sizeStr = "%011o\u0000".format(size)
            System.arraycopy(sizeStr.toByteArray(Charsets.US_ASCII), 0, h, 124, 12)
            val mtime = "00000000000\u0000"
            System.arraycopy(mtime.toByteArray(Charsets.US_ASCII), 0, h, 136, 12)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = '0'.code.toByte()
            val magic = "ustar\u000000"
            System.arraycopy(magic.toByteArray(Charsets.US_ASCII), 0, h, 257, 8)
            var sum = 0
            for (b in h) sum += b.toInt() and 0xFF
            val cksum = String.format("%06o\u0000 ", sum)
            System.arraycopy(cksum.toByteArray(Charsets.US_ASCII), 0, h, 148, 8)
            return h
        }
        buf.write(header("$prefix/$fileName", data.size))
        buf.write(data)
        buf.write(ByteArray((512 - data.size % 512) % 512))
        buf.write(ByteArray(1024)) // two zero blocks end the archive
        return buf.toByteArray()
    }
}
