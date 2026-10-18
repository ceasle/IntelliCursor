package com.ceasle.intellicursor.protocol

data class CursorAskQuestionParams(
    val toolCallId: String,
    val title: String?,
    val questions: List<CursorQuestion>
)

data class CursorQuestion(
    val id: String,
    val prompt: String,
    val options: List<CursorQuestionOption>,
    val allowMultiple: Boolean = false
)

data class CursorQuestionOption(val id: String, val label: String)

data class CursorCreatePlanParams(
    val toolCallId: String,
    val name: String?,
    val overview: String?,
    val plan: String,
    val todos: List<CursorTodo>,
    val phases: List<CursorPlanPhase>?
)

data class CursorTodo(val id: String, val content: String, val status: String)
data class CursorPlanPhase(val name: String, val todos: List<CursorTodo>)
