package com.ceasle.intellicursor.ui

import com.ceasle.intellicursor.client.AcpClient
import com.ceasle.intellicursor.protocol.*
import com.ceasle.intellicursor.service.CursorSessionManager
import com.ceasle.intellicursor.transport.StdioJsonRpcTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class PermissionRequestDialogTest {

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeAndWait(action)
        }
    }

    private fun createSamplePermissionParams(): RequestPermissionParams {
        val toolCall = ToolCallUpdateEvent(
            toolCallId = "tc-101",
            title = "Execute terminal command",
            kind = "execute",
            locations = listOf(ToolLocation(path = "/workspace/src/Main.kt", line = 15)),
            rawInput = mapOf("command" to "rm -rf build")
        )
        val options = listOf(
            PermissionOption(optionId = "allow_once", name = "Allow Once", kind = "allow_once"),
            PermissionOption(optionId = "allow_always", name = "Allow Always", kind = "allow_always"),
            PermissionOption(optionId = "deny", name = "Deny", kind = "deny")
        )
        return RequestPermissionParams(
            sessionId = "sess-1",
            toolCall = toolCall,
            options = options
        )
    }

    @Test
    fun testPermissionRequestDialogConstructionAndActions() {
        runOnEdt {
            val params = createSamplePermissionParams()
            val dialog = PermissionRequestDialog(null, params)

            assertEquals("Cursor Permission Request", dialog.title)

            // Center panel verification
            val center = dialog.createCenterPanel()
            assertNotNull(center)
            val scrollPane = center.getComponent(1) as? JScrollPane
            assertNotNull(scrollPane, "Center panel should contain a JScrollPane")
            val textArea = scrollPane!!.viewport.view as? JTextArea
            assertNotNull(textArea)
            val text = textArea!!.text
            assertTrue(text.contains("Tool: EXECUTE"))
            assertTrue(text.contains("Action: Execute terminal command"))
            assertTrue(text.contains("Locations:"))
            assertTrue(text.contains("/workspace/src/Main.kt:15"))
            assertTrue(text.contains("Input:"))
            assertTrue(text.contains("rm -rf build"))

            // Actions verification: 3 option actions + 1 cancel action
            val actions = dialog.createActions()
            assertEquals(4, actions.size)
            assertEquals("Allow Once", actions[0].getValue(javax.swing.Action.NAME))
            assertEquals("Allow Always", actions[1].getValue(javax.swing.Action.NAME))
            assertEquals("Deny", actions[2].getValue(javax.swing.Action.NAME))
            assertEquals("Cancel", actions[3].getValue(javax.swing.Action.NAME))

            // Trigger allow_once action
            actions[0].actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "Allow Once"))
            assertEquals("allow_once", dialog.selectedOptionId)
            assertFalse(dialog.isCancelled)
        }
    }

    @Test
    fun testPermissionRequestDialogCancelAction() {
        runOnEdt {
            val params = createSamplePermissionParams()
            val dialog = PermissionRequestDialog(null, params)

            val actions = dialog.createActions()
            // Trigger Cancel action
            actions[3].actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "Cancel"))
            assertNull(dialog.selectedOptionId)
            assertTrue(dialog.isCancelled)
        }
    }

    @Test
    fun testPermissionRequestDialogSelectOptionAndDoCancel() {
        runOnEdt {
            val params = createSamplePermissionParams()
            val dialog = PermissionRequestDialog(null, params)

            dialog.selectOption("allow_always")
            assertEquals("allow_always", dialog.selectedOptionId)
            assertFalse(dialog.isCancelled)

            dialog.doCancelAction()
            assertNull(dialog.selectedOptionId)
            assertTrue(dialog.isCancelled)
        }
    }

    @Test
    fun testPermissionRequestDialogWithContentList() {
        runOnEdt {
            val toolCall = ToolCallUpdateEvent(
                toolCallId = "tc-102",
                title = "Read Configuration",
                kind = "read",
                content = listOf("config.json content line 1", "config.json content line 2")
            )
            val params = RequestPermissionParams(
                sessionId = "sess-2",
                toolCall = toolCall,
                options = listOf(PermissionOption("opt-1", "Approve", "allow"))
            )

            val dialog = PermissionRequestDialog(null, params)
            val center = dialog.createCenterPanel()
            val scrollPane = center.getComponent(1) as JScrollPane
            val textArea = scrollPane.viewport.view as JTextArea
            assertTrue(textArea.text.contains("Content:"))
            assertTrue(textArea.text.contains("config.json content line 1"))
        }
    }

    @Test
    fun testCursorQuestionDialogSingleChoice() {
        runOnEdt {
            val q1 = CursorQuestion(
                id = "q-framework",
                prompt = "Choose a web framework:",
                options = listOf(
                    CursorQuestionOption("ktor", "Ktor"),
                    CursorQuestionOption("spring", "Spring Boot"),
                    CursorQuestionOption("micronaut", "Micronaut")
                ),
                allowMultiple = false
            )
            val params = CursorAskQuestionParams(
                toolCallId = "tc-q1",
                title = "Project Setup",
                questions = listOf(q1)
            )

            val dialog = CursorQuestionDialog(null, params)
            assertEquals("Project Setup", dialog.title)

            val center = dialog.createCenterPanel()
            assertNotNull(center)
            assertEquals(1, dialog.questionHolders.size)

            // Default selection is first option (ktor)
            var answers = dialog.collectAnswers()
            assertEquals(listOf("ktor"), answers["q-framework"])

            // Programmatically select second option (spring)
            dialog.setAnswer("q-framework", listOf("spring"))
            answers = dialog.collectAnswers()
            assertEquals(listOf("spring"), answers["q-framework"])

            // Set custom answer
            dialog.setCustomAnswer("q-framework", "Quarkus")
            answers = dialog.collectAnswers()
            assertEquals(listOf("Quarkus"), answers["q-framework"])

            // Test OK action
            dialog.doOKAction()
            assertFalse(dialog.isCancelled)
            assertEquals(listOf("Quarkus"), dialog.answers["q-framework"])

            // Test Cancel action
            dialog.doCancelAction()
            assertTrue(dialog.isCancelled)
            assertTrue(dialog.answers.isEmpty())
        }
    }

    @Test
    fun testCursorQuestionDialogMultipleChoiceAndFreeForm() {
        runOnEdt {
            val qMulti = CursorQuestion(
                id = "q-features",
                prompt = "Select features to enable:",
                options = listOf(
                    CursorQuestionOption("auth", "Authentication"),
                    CursorQuestionOption("metrics", "Prometheus Metrics"),
                    CursorQuestionOption("openapi", "OpenAPI Docs")
                ),
                allowMultiple = true
            )
            val qFreeForm = CursorQuestion(
                id = "q-name",
                prompt = "Enter package name:",
                options = emptyList(),
                allowMultiple = false
            )
            val params = CursorAskQuestionParams(
                toolCallId = "tc-q2",
                title = null,
                questions = listOf(qMulti, qFreeForm)
            )

            val dialog = CursorQuestionDialog(null, params)
            assertEquals("Cursor Question", dialog.title) // Fallback title
            assertEquals(2, dialog.questionHolders.size)

            // Select multi-choice options
            dialog.setAnswer("q-features", listOf("auth", "openapi"))
            // Set free-form answer
            dialog.setCustomAnswer("q-name", "com.example.service")

            val answers = dialog.collectAnswers()
            assertEquals(listOf("auth", "openapi"), answers["q-features"])
            assertEquals(listOf("com.example.service"), answers["q-name"])

            dialog.doOKAction()
            assertFalse(dialog.isCancelled)
            assertEquals(listOf("auth", "openapi"), dialog.answers["q-features"])
            assertEquals(listOf("com.example.service"), dialog.answers["q-name"])
        }
    }

    @Test
    fun testAcpClientResponseHelpers() {
        val inStream = ByteArrayInputStream(ByteArray(0))
        val outStream = ByteArrayOutputStream()
        val transport = StdioJsonRpcTransport(inStream, outStream)
        val client = AcpClient(transport)

        // 1. respondAskQuestion with answers
        client.respondAskQuestion(50L, mapOf("q1" to listOf("optA", "optB")), cancelled = false)
        val askOutput = outStream.toString(StandardCharsets.UTF_8)
        assertTrue(askOutput.contains(""""id":50"""))
        assertTrue(askOutput.contains(""""answers""""))
        assertTrue(askOutput.contains(""""q1":["optA","optB"]"""))

        // 2. respondAskQuestion cancelled
        outStream.reset()
        client.respondAskQuestion(51L, emptyMap(), cancelled = true)
        val askCancelledOutput = outStream.toString(StandardCharsets.UTF_8)
        assertTrue(askCancelledOutput.contains(""""id":51"""))
        assertTrue(askCancelledOutput.contains(""""cancelled":true"""))

        // 3. respondCreatePlan approved = true
        outStream.reset()
        client.respondCreatePlan(52L, approved = true)
        val planApprovedOutput = outStream.toString(StandardCharsets.UTF_8)
        assertTrue(planApprovedOutput.contains(""""id":52"""))
        assertTrue(planApprovedOutput.contains(""""approved":true"""))

        // 4. respondCreatePlan approved = false
        outStream.reset()
        client.respondCreatePlan(53L, approved = false)
        val planRejectedOutput = outStream.toString(StandardCharsets.UTF_8)
        assertTrue(planRejectedOutput.contains(""""id":53"""))
        assertTrue(planRejectedOutput.contains(""""approved":false"""))

        client.close()
    }

    @Test
    fun testCursorSessionManagerListenersWiring() {
        runOnEdt {
            val inStream = ByteArrayInputStream(ByteArray(0))
            val outStream = ByteArrayOutputStream()
            val transport = StdioJsonRpcTransport(inStream, outStream)
            val client = AcpClient(transport)

            val manager = CursorSessionManager(null)
            manager.client = client

            // With Run everything on, a permission request is answered without opening a dialog.
            // (Question and plan requests always open a modal dialog, so they aren't exercised here.)
            manager.runEverything = true
            assertDoesNotThrow {
                manager.onPermissionRequest(100L, createSamplePermissionParams())
            }

            val written = outStream.toString(StandardCharsets.UTF_8)
            assertTrue(written.contains("\"id\":100"), "Expected a response to request 100: $written")
            assertTrue(written.contains("\"optionId\":\"allow_once\""), "Expected allow_once to be selected: $written")

            manager.dispose()
        }
    }
}
