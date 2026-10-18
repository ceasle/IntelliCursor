package com.ceasle.intellicursor.actions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ExplainCodeAction : SendSelectionAction() {

    override fun buildPrompt(
        project: Project,
        file: VirtualFile?,
        editor: Editor,
        selectedText: String
    ): String {
        return buildContextPrompt(
            project,
            file,
            editor,
            selectedText,
            instruction = "Please explain the following code in detail, discussing its logic, potential edge cases, and design implications:"
        )
    }
}
