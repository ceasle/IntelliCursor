package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.protocol.ModeOption
import com.ceasle.intellicursor.service.ActiveTurn
import com.ceasle.intellicursor.service.CursorSessionManager
import com.ceasle.intellicursor.service.SessionState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

class CursorAgentPanelTest {

    @Test
    fun testMessageComposerSubmitAndClear() {
        val submittedText = AtomicReference<String>()
        val composer = MessageComposer { text ->
            submittedText.set(text)
        }

        assertEquals("", composer.getText())
        composer.setText("   Hello Cursor!   ")
        composer.submit()

        assertEquals("Hello Cursor!", submittedText.get())
        assertEquals("", composer.getText())

        // Blank submit does not trigger callback
        submittedText.set(null)
        composer.setText("    ")
        composer.submit()
        assertNull(submittedText.get())
    }

    @Test
    fun testMessageComposerEnableDisable() {
        val composer = MessageComposer()
        assertTrue(composer.textArea.isEnabled)
        assertTrue(composer.sendButton.isEnabled)

        composer.isEnabled = false
        assertFalse(composer.textArea.isEnabled)
        assertFalse(composer.sendButton.isEnabled)

        composer.isEnabled = true
        assertTrue(composer.textArea.isEnabled)
        assertTrue(composer.sendButton.isEnabled)
    }

    private fun flushEdt() {
        if (SwingUtilities.isEventDispatchThread()) return
        SwingUtilities.invokeAndWait { }
    }

    @Test
    fun testCursorAgentPanelInitialState() {
        val sessionManager = CursorSessionManager(null)
        val panel = CursorAgentPanel(sessionManager)
        flushEdt()

        assertNotNull(panel)
        assertEquals("IntelliCursor", panel.titleLabel.text)
        assertEquals("agent", panel.modePicker.selectedId)
        assertFalse(panel.composer.isRunning, "Composer should not be running when no active turn")
        assertTrue(panel.newSessionButton.isEnabled)
        assertEquals(0, panel.turnsContainer.componentCount)

        panel.dispose()
    }

    @Test
    fun testCursorAgentPanelSyncTitleAndModes() {
        val sessionManager = CursorSessionManager(null)
        val panel = CursorAgentPanel(sessionManager)
        flushEdt()

        val state = SessionState("sess-1", "/test/dir").apply {
            title = "Refactoring Auth Module"
            currentMode = "plan"
            availableModes = listOf(
                ModeOption(id = "agent", name = "Agent", description = "Agent mode"),
                ModeOption(id = "plan", name = "Plan", description = "Plan mode"),
                ModeOption(id = "ask", name = "Ask", description = "Ask mode"),
                ModeOption(id = "debug", name = "Debug", description = "Debug mode")
            )
        }
        sessionManager.sessionState = state
        sessionManager.notifyStateChanged()
        flushEdt()

        assertEquals("Refactoring Auth Module", panel.titleLabel.text)
        assertEquals("plan", panel.modePicker.selectedId)
        assertEquals(4, panel.modePicker.items.size)

        panel.dispose()
    }

    @Test
    fun testCursorAgentPanelStopButtonLifecycle() {
        val sessionManager = CursorSessionManager(null)
        val panel = CursorAgentPanel(sessionManager)
        flushEdt()

        val state = SessionState("sess-2", "/test/dir")
        val turn = ActiveTurn("Implement caching")
        turn.isCompleted = false
        state.turns.add(turn)
        state.activeTurn = turn
        sessionManager.sessionState = state

        panel.updateUiFromState()
        flushEdt()
        assertTrue(panel.composer.isRunning, "Composer should show stop for in-progress turn")

        // Clicking stop button cancels turn
        panel.composer.clickSendOrStop()
        flushEdt()
        assertTrue(turn.isCompleted)
        assertEquals("cancelled", turn.stopReason)

        panel.updateUiFromState()
        flushEdt()
        assertFalse(panel.composer.isRunning, "Composer should return to send after cancellation")

        panel.dispose()
    }

    @Test
    fun testCursorAgentPanelRendersTurns() {
        val sessionManager = CursorSessionManager(null)
        val panel = CursorAgentPanel(sessionManager)
        flushEdt()

        val state = SessionState("sess-3", "/test/dir")
        val turn1 = ActiveTurn("Turn 1 prompt")
        turn1.appendMessage("Turn 1 answer")
        state.turns.add(turn1)
        sessionManager.sessionState = state

        panel.updateUiFromState()
        flushEdt()
        assertEquals(1, panel.turnViews.size)
        assertEquals(1, panel.turnsContainer.componentCount)

        // Add second turn
        val turn2 = ActiveTurn("Turn 2 prompt")
        turn2.appendThought("Thinking about turn 2...")
        state.turns.add(turn2)
        state.activeTurn = turn2

        panel.updateUiFromState()
        flushEdt()
        assertEquals(2, panel.turnViews.size)
        assertEquals(2, panel.turnsContainer.componentCount)

        panel.dispose()
    }

    @Test
    fun testCursorAgentPanelComposerSubmission() {
        val sessionManager = CursorSessionManager(null)
        val panel = CursorAgentPanel(sessionManager)

        panel.composer.setText("Analyze project structure")
        assertEquals("Analyze project structure", panel.composer.getText())

        // Submitting via composer clears text
        panel.composer.submit()
        assertEquals("", panel.composer.getText())

        panel.dispose()
    }

    @Test
    fun testCursorAgentPanelDisposeUnregistersListener() {
        val sessionManager = CursorSessionManager(null)
        val panel = CursorAgentPanel(sessionManager)

        assertEquals(1, sessionManager.stateListeners.size)
        panel.dispose()
        assertEquals(0, sessionManager.stateListeners.size)
    }
}
