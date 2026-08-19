package com.dshmobile.app.data

import com.dshmobile.app.util.TokenEstimate

/**
 * Which slice of a conversation fits in the token budget.
 *
 * @param startIndex first message to send; everything before it is dropped
 * @param usedTokens estimated cost of the system prompt plus the kept messages
 * @param droppedCount how many of the oldest messages fall outside the window
 */
data class ContextWindow(
    val startIndex: Int,
    val usedTokens: Int,
    val droppedCount: Int,
    /** Cost of the whole history, so the meter can show the untrimmed total. */
    val totalTokens: Int,
)

/**
 * Budgets the context in **tokens** rather than in messages: walk backwards from the newest turn,
 * keeping what fits.
 *
 * Two deliberate rules:
 *  - The newest message is always kept, even alone it overflows. Dropping the thing being asked
 *    would be worse than letting the provider object to the size.
 *  - If the window would open on an assistant reply, it is extended *backwards* to that reply's
 *    prompt rather than forward past it — a coherent turn is worth overshooting the budget by at
 *    most one message, and skipping forward would throw away newer content to fix older framing.
 *
 * Shared by the request builder and the context meter so the number shown is the number sent.
 */
fun planContextWindow(
    messages: List<ChatMessage>,
    budget: Int,
    systemCost: Int,
): ContextWindow {
    if (messages.isEmpty()) {
        return ContextWindow(startIndex = 0, usedTokens = systemCost, droppedCount = 0, totalTokens = systemCost)
    }

    val costs = messages.map { TokenEstimate.ofMessage(it.content, it.attachments.tokens) }
    val total = systemCost + costs.sum()

    var running = systemCost
    var startIndex = messages.size
    for (i in messages.indices.reversed()) {
        val isNewest = i == messages.size - 1
        if (!isNewest && running + costs[i] > budget) break
        running += costs[i]
        startIndex = i
    }

    if (messages[startIndex].role != Role.USER) {
        val prompt = (startIndex - 1 downTo 0).firstOrNull { messages[it].role == Role.USER }
        if (prompt != null) {
            for (i in prompt until startIndex) running += costs[i]
            startIndex = prompt
        }
        // No user message anywhere in the transcript: send what we have.
    }

    return ContextWindow(
        startIndex = startIndex,
        usedTokens = running,
        droppedCount = startIndex,
        totalTokens = total,
    )
}
