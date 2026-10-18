package com.ceasle.intellicursor.actions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class RefactorCodeAction : SendSelectionAction() {

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
            instruction = "Please review the following code and suggest refactoring improvements for better readability, maintainability, and idiomatic quality:"
        )
    }
}
