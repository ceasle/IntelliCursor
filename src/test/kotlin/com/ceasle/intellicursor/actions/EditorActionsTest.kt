package com.ceasle.intellicursor.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.LightVirtualFile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy

class EditorActionsTest {

    private class TestActionManager : ActionManager() {
        override fun createActionPopupMenu(place: String, group: com.intellij.openapi.actionSystem.ActionGroup) =
            throw UnsupportedOperationException()
        override fun createActionToolbar(place: String, group: com.intellij.openapi.actionSystem.ActionGroup, horizontal: Boolean) =
            throw UnsupportedOperationException()
        override fun getAction(actionId: String): AnAction? = null
        override fun getId(action: AnAction): String? = null
        override fun registerAction(actionId: String, action: AnAction) {}
        override fun registerAction(actionId: String, action: AnAction, pluginId: PluginId?) {}
        override fun unregisterAction(actionId: String) {}
        override fun replaceAction(actionId: String, newAction: AnAction) {}
        override fun getActionIds(idPrefix: String): Array<String> = emptyArray()
        override fun getActionIdList(idPrefix: String): List<String> = emptyList()
        override fun isGroup(actionId: String): Boolean = false
        override fun getActionOrStub(id: String): AnAction? = null
        override fun addTimerListener(listener: com.intellij.openapi.actionSystem.TimerListener) {}
        override fun removeTimerListener(listener: com.intellij.openapi.actionSystem.TimerListener) {}
        override fun tryToExecute(action: AnAction, inputEvent: java.awt.event.InputEvent?, contextComponent: java.awt.Component?, place: String?, now: Boolean): com.intellij.openapi.util.ActionCallback =
            com.intellij.openapi.util.ActionCallback.DONE
        @Deprecated("Deprecated in Java")
        override fun addAnActionListener(listener: com.intellij.openapi.actionSystem.ex.AnActionListener) {}
        override fun getKeyboardShortcut(actionId: String) = null
    }

    private class TestDataContext : DataContext {
        private val data = mutableMapOf<String, Any?>()

        fun put(key: com.intellij.openapi.actionSystem.DataKey<*>, value: Any?) {
            data[key.name] = value
        }

        override fun getData(dataId: String): Any? = data[dataId]
    }

    private val actionManager = TestActionManager()

    private fun createActionEvent(
        editor: Editor?,
        file: com.intellij.openapi.vfs.VirtualFile?,
        project: com.intellij.openapi.project.Project? = null
    ): AnActionEvent {
        val dc = TestDataContext()
        if (editor != null) dc.put(CommonDataKeys.EDITOR, editor)
        if (file != null) dc.put(CommonDataKeys.VIRTUAL_FILE, file)
        if (project != null) dc.put(CommonDataKeys.PROJECT, project)

        val presentation = Presentation()
        return AnActionEvent(
            null,
            dc,
            ActionPlaces.EDITOR_POPUP,
            presentation,
            actionManager,
            0
        )
    }

    private fun createMockEditor(
        text: String,
        hasSelection: Boolean = false,
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
        selectedText: String? = null
    ): Editor {
        val doc: Document = DocumentImpl(text)

        val sm = Proxy.newProxyInstance(
            SelectionModel::class.java.classLoader,
            arrayOf(SelectionModel::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "hasSelection" -> hasSelection
                "getSelectedText" -> selectedText
                "getSelectionStart" -> selectionStart
                "getSelectionEnd" -> selectionEnd
                else -> null
            }
        } as SelectionModel

        return Proxy.newProxyInstance(
            Editor::class.java.classLoader,
            arrayOf(Editor::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getSelectionModel" -> sm
                "getDocument" -> doc
                else -> null
            }
        } as Editor
    }

    @Test
    fun testActionUpdateThreadIsBgt() {
        val sendAction = SendSelectionAction()
        val explainAction = ExplainCodeAction()
        val refactorAction = RefactorCodeAction()
        val showDiffAction = ShowDiffAction()

        assertEquals(ActionUpdateThread.BGT, sendAction.actionUpdateThread)
        assertEquals(ActionUpdateThread.BGT, explainAction.actionUpdateThread)
        assertEquals(ActionUpdateThread.BGT, refactorAction.actionUpdateThread)
        assertEquals(ActionUpdateThread.BGT, showDiffAction.actionUpdateThread)
    }

    @Test
    fun testSendSelectionActionEnabledOnlyWhenSelectionExists() {
        val action = SendSelectionAction()

        // 1. No editor present -> disabled
        val noEditorEvent = createActionEvent(null, null)
        action.update(noEditorEvent)
        assertFalse(noEditorEvent.presentation.isEnabledAndVisible)

        // 2. Editor present with empty/no selection -> disabled
        val editorNoSel = createMockEditor("val x = 10\nval y = 20\n", hasSelection = false)
        val noSelectionEvent = createActionEvent(editorNoSel, null)
        action.update(noSelectionEvent)
        assertFalse(noSelectionEvent.presentation.isEnabledAndVisible)

        // 3. Editor present with selection -> enabled
        val editorWithSel = createMockEditor(
            "val x = 10\nval y = 20\n",
            hasSelection = true,
            selectionStart = 0,
            selectionEnd = 10,
            selectedText = "val x = 10"
        )
        val hasSelectionEvent = createActionEvent(editorWithSel, null)
        action.update(hasSelectionEvent)
        assertTrue(hasSelectionEvent.presentation.isEnabledAndVisible)
    }

    @Test
    fun testExplainCodeActionEnabledOnlyWhenSelectionExists() {
        val action = ExplainCodeAction()

        val editorNoSel = createMockEditor("fun calculate() = 42\n", hasSelection = false)
        val eventNoSel = createActionEvent(editorNoSel, null)
        action.update(eventNoSel)
        assertFalse(eventNoSel.presentation.isEnabledAndVisible)

        val editorWithSel = createMockEditor(
            "fun calculate() = 42\n",
            hasSelection = true,
            selectionStart = 0,
            selectionEnd = 15,
            selectedText = "fun calculate()"
        )
        val eventWithSel = createActionEvent(editorWithSel, null)
        action.update(eventWithSel)
        assertTrue(eventWithSel.presentation.isEnabledAndVisible)
    }

    @Test
    fun testRefactorCodeActionEnabledOnlyWhenSelectionExists() {
        val action = RefactorCodeAction()

        val editorNoSel = createMockEditor("fun calculate() = 42\n", hasSelection = false)
        val eventNoSel = createActionEvent(editorNoSel, null)
        action.update(eventNoSel)
        assertFalse(eventNoSel.presentation.isEnabledAndVisible)

        val editorWithSel = createMockEditor(
            "fun calculate() = 42\n",
            hasSelection = true,
            selectionStart = 0,
            selectionEnd = 15,
            selectedText = "fun calculate()"
        )
        val eventWithSel = createActionEvent(editorWithSel, null)
        action.update(eventWithSel)
        assertTrue(eventWithSel.presentation.isEnabledAndVisible)
    }

    @Test
    fun testShowDiffActionPresentation() {
        val action = ShowDiffAction()

        // 1. Missing project -> disabled
        val noProjectEvent = createActionEvent(null, null, project = null)
        action.update(noProjectEvent)
        assertFalse(noProjectEvent.presentation.isEnabledAndVisible)

        // 2. Project present but missing virtual file -> disabled
        val pico = com.intellij.util.pico.DefaultPicoContainer()
        val mockProject = com.intellij.mock.MockProject(pico, com.intellij.openapi.Disposable {})
        val noFileEvent = createActionEvent(null, null, project = mockProject)
        action.update(noFileEvent)
        assertFalse(noFileEvent.presentation.isEnabledAndVisible)

        // 3. Project and file present -> enabled
        val file = LightVirtualFile("Sample.kt", "fun sample() = true")
        val withFileEvent = createActionEvent(null, file, project = mockProject)
        action.update(withFileEvent)
        assertTrue(withFileEvent.presentation.isEnabledAndVisible)
    }

    @Test
    fun testSendSelectionPromptFormation() {
        val fullText = "line 1\nline 2: val answer = 42\nline 3\n"
        val startOffset = fullText.indexOf("line 2")
        val endOffset = startOffset + "line 2: val answer = 42".length
        val selectedText = "line 2: val answer = 42"

        val editor = createMockEditor(
            text = fullText,
            hasSelection = true,
            selectionStart = startOffset,
            selectionEnd = endOffset,
            selectedText = selectedText
        )
        val file = LightVirtualFile("AnswerService.kt", fullText)

        val pico = com.intellij.util.pico.DefaultPicoContainer()
        val project = com.intellij.mock.MockProject(pico, com.intellij.openapi.Disposable {})

        val prompt = SendSelectionAction.buildContextPrompt(
            project = project,
            file = file,
            editor = editor,
            selectedText = selectedText,
            instruction = null
        )

        assertTrue(prompt.contains("File: AnswerService.kt (lines 2-2)"), "Expected file and line numbers in prompt: $prompt")
        assertTrue(prompt.contains("```kt"), "Expected markdown code block: $prompt")
        assertTrue(prompt.contains("line 2: val answer = 42"), "Expected selected code text: $prompt")
        assertFalse(prompt.contains("Please explain"))
    }

    @Test
    fun testExplainCodePromptFormation() {
        val fullText = "class OrderProcessor {\n    fun process() {}\n}\n"
        val editor = createMockEditor(
            text = fullText,
            hasSelection = true,
            selectionStart = 0,
            selectionEnd = fullText.length - 1,
            selectedText = fullText.trimEnd()
        )
        val file = LightVirtualFile("OrderProcessor.java", fullText)

        val pico = com.intellij.util.pico.DefaultPicoContainer()
        val project = com.intellij.mock.MockProject(pico, com.intellij.openapi.Disposable {})

        val prompt = SendSelectionAction.buildContextPrompt(
            project = project,
            file = file,
            editor = editor,
            selectedText = fullText.trimEnd(),
            instruction = "Please explain the following code in detail, discussing its logic, potential edge cases, and design implications:"
        )

        assertTrue(prompt.startsWith("Please explain the following code in detail"))
        assertTrue(prompt.contains("File: OrderProcessor.java (lines 1-3)"), "Expected line range 1-3 in prompt: $prompt")
        assertTrue(prompt.contains("```java"))
        assertTrue(prompt.contains("class OrderProcessor"))
    }

    @Test
    fun testRefactorCodePromptFormation() {
        val fullText = "def old_style():\n    pass\n"
        val editor = createMockEditor(
            text = fullText,
            hasSelection = true,
            selectionStart = 0,
            selectionEnd = fullText.length - 1,
            selectedText = fullText.trimEnd()
        )
        val file = LightVirtualFile("script.py", fullText)

        val pico = com.intellij.util.pico.DefaultPicoContainer()
        val project = com.intellij.mock.MockProject(pico, com.intellij.openapi.Disposable {})

        val prompt = SendSelectionAction.buildContextPrompt(
            project = project,
            file = file,
            editor = editor,
            selectedText = fullText.trimEnd(),
            instruction = "Please review the following code and suggest refactoring improvements for better readability, maintainability, and idiomatic quality:"
        )

        assertTrue(prompt.startsWith("Please review the following code and suggest refactoring improvements"))
        assertTrue(prompt.contains("File: script.py (lines 1-2)"), "Expected line range 1-2 in prompt: $prompt")
        assertTrue(prompt.contains("```py"))
        assertTrue(prompt.contains("def old_style():"))
    }

    @Test
    fun testPluginXmlRegistration() {
        val pluginXmlFile = File("src/main/resources/META-INF/plugin.xml")
        assertTrue(pluginXmlFile.exists(), "plugin.xml must exist")

        val rootElement = JDOMUtil.load(pluginXmlFile)
        val actionsElement = rootElement.getChild("actions")
        assertNotNull(actionsElement, "<actions> tag must exist in plugin.xml")

        val group = actionsElement.getChild("group")
        assertNotNull(group, "<group> must exist inside <actions>")
        assertEquals("Cursor.EditorPopupMenu", group.getAttributeValue("id"))
        assertEquals("IntelliCursor", group.getAttributeValue("text"))

        val addToGroup = group.getChild("add-to-group")
        assertNotNull(addToGroup)
        assertEquals("EditorPopupMenu", addToGroup.getAttributeValue("group-id"))

        val actions = group.getChildren("action")
        val actionIds = actions.mapNotNull { it.getAttributeValue("id") }
        val actionClasses = actions.mapNotNull { it.getAttributeValue("class") }

        assertTrue(actionIds.contains("Cursor.SendSelection"))
        assertTrue(actionIds.contains("Cursor.ExplainCode"))
        assertTrue(actionIds.contains("Cursor.RefactorCode"))
        assertTrue(actionIds.contains("Cursor.ShowDiff"))

        assertTrue(actionClasses.contains("com.ceasle.intellicursor.actions.SendSelectionAction"))
        assertTrue(actionClasses.contains("com.ceasle.intellicursor.actions.ExplainCodeAction"))
        assertTrue(actionClasses.contains("com.ceasle.intellicursor.actions.RefactorCodeAction"))
        assertTrue(actionClasses.contains("com.ceasle.intellicursor.actions.ShowDiffAction"))
    }
}
