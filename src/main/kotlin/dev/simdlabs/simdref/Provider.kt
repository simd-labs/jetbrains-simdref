package dev.simdlabs.simdref

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspInlayHintSupport

val SUPPORTED_EXTENSIONS = setOf("s", "S", "asm", "c", "cc", "cpp", "cxx", "c++", "h", "hpp", "hh", "hxx", "cu", "cuh")

fun isSupportedExtension(ext: String?): Boolean = ext in SUPPORTED_EXTENSIONS

// ponytail: replacement API starts in 2026.2; migrate when 2026.1 support ends
@Suppress("DEPRECATION")
class SimdrefLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (!isSupportedExtension(file.extension)) return
        val bin = ServerInstaller.find() ?: run { ServerInstaller.installInBackground(project); return }
        if (bin.startsWith(ServerInstaller.privateBinPrefix())) ServerInstaller.maybeUpgradeInBackground(project)
        serverStarter.ensureServerStarted(SimdrefLspServerDescriptor(project, bin))
    }
}

// ponytail: replacement API starts in 2026.2; migrate when 2026.1 support ends
@Suppress("DEPRECATION")
class SimdrefLspServerDescriptor(project: Project, private val bin: String) :
    ProjectWideLspServerDescriptor(project, "simdref") {
    // The platform cuts LSP inlay hints at 42 chars by default (LspInlayHintSupport.getMaxInlayHintChars,
    // clamped to 100 max in LspInlayHintsProvider). Instruction briefs are up to 60 chars, so raise it.
    override val lspCustomization = object : LspCustomization() {
        override val inlayHintCustomizer = object : LspInlayHintSupport() {
            override fun getMaxInlayHintChars() = 100
        }
    }
    override fun isSupportedFile(file: VirtualFile) = isSupportedExtension(file.extension)
    override fun createCommandLine() = GeneralCommandLine(bin)
}
