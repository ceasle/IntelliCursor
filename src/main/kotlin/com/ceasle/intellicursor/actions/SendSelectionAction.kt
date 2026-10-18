package com.ceasle.intellicursor.actions

import com.ceasle.intellicursor.service.CursorSessionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File

open class SendSelectionAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val selectedText = editor.selectionModel.selectedText ?: return
        if (selectedText.isBlank()) return

        val prompt = buildPrompt(project, file, editor, selectedText)
        submitToAgent(project, prompt)
    }

    protected open fun buildPrompt(
        project: Project,
        file: VirtualFile?,
        editor: Editor,
        selectedText: String
    ): String {
        return buildContextPrompt(project, file, editor, selectedText, instruction = null)
    }

    companion object {
        fun buildContextPrompt(
            project: Project,
            file: VirtualFile?,
            editor: Editor,
            selectedText: String,
            instruction: String?
        ): String {
            val baseDir = project.basePath?.let { File(it) }?.let { VfsUtil.findFileByIoFile(it, false) }
            val relativePath = if (file != null && baseDir != null) {
                VfsUtilCore.getRelativePath(file, baseDir) ?: file.path.removePrefix("/")
            } else {
                file?.path?.removePrefix("/") ?: "unknown"
            }

            val doc = editor.document
            val startLine = doc.getLineNumber(editor.selectionModel.selectionStart) + 1
            val endLine = doc.getLineNumber(editor.selectionModel.selectionEnd) + 1

            val ext = file?.extension ?: ""

            val sb = StringBuilder()
            if (!instruction.isNullOrBlank()) {
                sb.append(instruction).append("\n\n")
            }
            sb.append("File: ").append(relativePath)
                .append(" (lines ").append(startLine).append("-").append(endLine).append(")\n")
                .append("```").append(ext).append("\n")
                .append(selectedText).append("\n")
                .append("```")

            return sb.toString()
        }

        fun submitToAgent(project: Project, prompt: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("IntelliCursor")
            toolWindow?.activate(null)

            val sessionManager = project.getService(CursorSessionManager::class.java)
                ?: CursorSessionManager(project)

            val app = ApplicationManager.getApplication()
            if (app != null) {
                app.executeOnPooledThread {
                    sessionManager.submitPrompt(prompt)
                }
            } else {
                sessionManager.submitPrompt(prompt)
            }
        }
    }
}
