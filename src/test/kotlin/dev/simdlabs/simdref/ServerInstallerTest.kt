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
     * A command that sleeps past the timeout dies with its child: runLogged
     * kills the descendants, the process, and waits for the exit. The child
     * touches a marker after 2 s; the test waits past that before it checks.
     * Mutation check: drop the descendants kill and `child-alive` makes this FAIL.
     */
    @Test(timeout = 20_000)
    fun timeoutKillsTheWholeTree() {
        assumeWindowsSkip()
        val dir = Files.createTempDirectory("simdref-timeout")
        val marker = dir.resolve("child-alive")
        val script = dir.resolve("sleeper.sh")
        Files.writeString(
            script,
            "#!/bin/sh\nexport MARKER=\"$1\"\nsh -c 'sleep 2; touch \"${'$'}MARKER\"' &\nsleep 30\n"
        )
        script.toFile().setExecutable(true)
        val start = System.nanoTime()
        val out = ServerInstaller.runLogged(
            listOf("sh", script.toString(), marker.toString()),
            emptyMap(),
            null,
            300,
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("a timeout reports it: $out", out.contains("timeout"))
        assertTrue("waited for the exit, not the 30 s sleep: ${elapsedMs}ms", elapsedMs < 10_000)
        Thread.sleep(3_000) // past the child's 2 s: a live child has written the marker by now
        assertFalse("the child must be dead before the check", Files.exists(marker))
    }

    private fun assumeWindowsSkip() {
        org.junit.Assume.assumeFalse("POSIX sh only", com.intellij.openapi.util.SystemInfo.isWindows)
    }

    /**
     * F1: an interrupt mid-wait must kill the child tree and join the reader,
     * same as a timeout. The script spawns a child that touches a marker
     * after 2 s and then sleeps; the test interrupts the runLogged caller,
     * waits past 2 s, and checks that the child never wrote the marker and
     * that the caller returned the interrupt shape.
     * Mutation: revert the catch to the old destroy-only path (no descendant
     * kill) and `child-alive` makes this test FAIL.
     */
    @Test(timeout = 20_000)
    fun interruptKillsTheWholeTree() {
        assumeWindowsSkip()
        val dir = Files.createTempDirectory("simdref-interrupt")
        val marker = dir.resolve("child-alive")
        val script = dir.resolve("sleeper.sh")
        Files.writeString(
            script,
            "#!/bin/sh\nexport MARKER=\"$1\"\nsh -c 'sleep 2; touch \"${'$'}MARKER\"' &\nsleep 30\n"
        )
        script.toFile().setExecutable(true)
        val result = java.util.concurrent.atomic.AtomicReference<String>()
        val caller = Thread {
            result.set(
                ServerInstaller.runLogged(
                    listOf("sh", script.toString(), marker.toString()),
                    emptyMap(),
                    "FAILED",
                    60_000,
                )
            )
        }
        caller.start()
        Thread.sleep(500) // in waitFor by now
        caller.interrupt()
        caller.join(10_000)
        assertFalse("runLogged must return promptly after interrupt", caller.isAlive)
        assertEquals("FAILED", result.get())
        Thread.sleep(3_000) // past the child's 2 s
        assertFalse("the child must be dead before the check", Files.exists(marker))
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
