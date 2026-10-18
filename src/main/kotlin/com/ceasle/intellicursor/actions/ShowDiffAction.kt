package com.ceasle.intellicursor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction as IdeaShowDiffAction

class ShowDiffAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vcsManager = ProjectLevelVcsManager.getInstance(project)

        if (!vcsManager.hasActiveVcss() && !git4idea.GitUtil.hasGitRepositories(project)) {
            VcsNotifier.getInstance(project).notifyWarning(
                NOTIFICATION_ID,
                NOTIFICATION_TITLE,
                "No active version control system found for this project."
            )
            return
        }

        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file == null) {
            VcsNotifier.getInstance(project).notifyMinorInfo(
                NOTIFICATION_ID,
                NOTIFICATION_TITLE,
                "No file selected to compare."
            )
            return
        }

        val isUnderVcs = vcsManager.getVcsFor(file) != null ||
            try {
                git4idea.GitUtil.getRepositoryForFile(project, file)
                true
            } catch (_: Exception) {
                false
            }

        if (!isUnderVcs) {
            VcsNotifier.getInstance(project).notifyWarning(
                NOTIFICATION_ID,
                NOTIFICATION_TITLE,
                "File '${file.name}' is not under version control."
            )
            return
        }

        val changeListManager = ChangeListManager.getInstance(project)
        val change = changeListManager.getChange(file)

        if (change == null) {
            val fileName = file.name
            VcsNotifier.getInstance(project).notifyMinorInfo(
                NOTIFICATION_ID,
                NOTIFICATION_TITLE,
                "File '$fileName' has no uncommitted changes."
            )
            return
        }

        IdeaShowDiffAction.showDiffForChange(project, listOf(change))
    }

    companion object {
        const val NOTIFICATION_ID = "cursor.diff.notification"
        const val NOTIFICATION_TITLE = "Cursor Diff"
    }
}
