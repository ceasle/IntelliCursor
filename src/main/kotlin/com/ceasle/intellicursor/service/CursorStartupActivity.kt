package com.ceasle.intellicursor.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Boots the Cursor CLI when the project opens, so the first prompt doesn't wait for it. */
class CursorStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CursorSessionManager>().warmUp()
    }
}
