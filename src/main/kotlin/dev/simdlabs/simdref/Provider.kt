package dev.simdlabs.simdref

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor

val SUPPORTED_EXTENSIONS = setOf("s", "S", "asm", "c", "cc", "cpp", "cxx", "c++", "h", "hpp", "hh", "hxx", "cu", "cuh")

fun isSupportedExtension(ext: String?): Boolean = ext in SUPPORTED_EXTENSIONS

class SimdrefLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (!isSupportedExtension(file.extension)) return
        val bin = ServerInstaller.find() ?: run { ServerInstaller.installInBackground(project); return }
        serverStarter.ensureServerStarted(SimdrefLspServerDescriptor(project, bin))
    }
}

class SimdrefLspServerDescriptor(project: Project, private val bin: String) :
    ProjectWideLspServerDescriptor(project, "simdref") {
    override fun isSupportedFile(file: VirtualFile) = isSupportedExtension(file.extension)
    override fun createCommandLine() = GeneralCommandLine(bin)
}
