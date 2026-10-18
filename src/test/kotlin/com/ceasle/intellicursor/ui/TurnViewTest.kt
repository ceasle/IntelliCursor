package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.protocol.PlanEntry
import com.ceasle.intellicursor.protocol.ToolLocation
import com.ceasle.intellicursor.service.ActiveTurn
import com.ceasle.intellicursor.service.ToolCallState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TurnViewTest {

    @Test
    fun testTurnViewInitialState() {
        val turn = ActiveTurn("Explain the architecture")
        val turnView = TurnView(turn)

        assertNotNull(turnView)
        assertFalse(turnView.thinkingPanel.isVisible, "Thinking panel should be hidden when thought text is blank")
        assertFalse(turnView.planPanel.isVisible, "Plan panel should be hidden when plan entries are empty")
        assertEquals(0, turnView.toolActivityPanel.componentCount, "No tool cards should be displayed initially")
        assertEquals("", turnView.finalResponsePanel.getText())
    }

    @Test
    fun testTurnViewSyncThinking() {
        val turn = ActiveTurn("Refactor service")
        val turnView = TurnView(turn)

        turn.appendThought("Analyzing dependencies...")
        turnView.syncState()

        assertTrue(turnView.thinkingPanel.isVisible, "Thinking panel should become visible")
        assertEquals("Analyzing dependencies...", turnView.thinkingPanel.getContentText())
        val header = turnView.thinkingPanel.getHeaderText()
        assertTrue(header == "Reasoning" || header.startsWith("Thought for"), "Unexpected thinking header: $header")

        // Test toggle expansion
        assertFalse(turnView.thinkingPanel.isExpanded)
        turnView.thinkingPanel.toggle()
        assertTrue(turnView.thinkingPanel.isExpanded)

        turnView.thinkingPanel.toggle()
        assertFalse(turnView.thinkingPanel.isExpanded)
    }

    @Test
    fun testTurnViewSyncPlan() {
        val turn = ActiveTurn("Implement feature")
        val turnView = TurnView(turn)

        val entries = listOf(
            PlanEntry(content = "Design API", priority = "high", status = "completed"),
            PlanEntry(content = "Implement endpoint", priority = "high", status = "in_progress"),
            PlanEntry(content = "Add tests", priority = "medium", status = "pending")
        )
        turn.planEntries = entries
        turnView.syncState()

        assertTrue(turnView.planPanel.isVisible)
        // The panel holds one body; the body has a header + 3 items = 4 components
        val body = turnView.planPanel.getComponent(0) as java.awt.Container
        assertEquals(4, body.componentCount)
    }

    @Test
    fun testTurnViewSyncTools() {
        val turn = ActiveTurn("Search for symbol")
        val turnView = TurnView(turn)

        val tc1 = ToolCallState(
            toolCallId = "tc-1",
            title = "Ripgrep search",
            kind = "search",
            status = "completed",
            locations = listOf(ToolLocation(path = "src/Main.kt", line = 42)),
            rawOutput = mapOf("stdout" to "Match found at line 42")
        )
        val tc2 = ToolCallState(
            toolCallId = "tc-2",
            title = "Run compiler",
            kind = "terminal",
            status = "failed",
            rawOutput = mapOf("stderr" to "Compilation error")
        )
        turn.toolCalls[tc1.toolCallId] = tc1
        turn.toolCalls[tc2.toolCallId] = tc2
        turnView.syncState()

        assertEquals(2, turnView.toolActivityPanel.componentCount)
    }

    @Test
    fun testTurnViewSyncFinalResponse() {
        val turn = ActiveTurn("What is 2+2?")
        val turnView = TurnView(turn)

        turn.appendMessage("The answer is 4.")
        turnView.syncState()

        assertEquals("The answer is 4.", turnView.finalResponsePanel.getText())
        assertFalse(turn.isThinking)
    }
}
