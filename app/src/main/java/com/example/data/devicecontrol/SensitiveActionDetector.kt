package com.example.data.devicecontrol

enum class SensitiveActionCategory {
    MESSAGING,
    PAYMENT,
    CALLING
}

data class SensitiveActionRequest(
    val originalPrompt: String,
    val category: SensitiveActionCategory
)

enum class ConfirmationDecision {
    CONFIRMED,
    CANCELLED,
    UNDECIDED
}

/**
 * Guards sensitive user requests (such as sending messages, payments, or phone calls)
 * so Aria requires explicit user confirmation before executing or forwarding them.
 */
object SensitiveActionDetector {

    private val paymentKeywords = listOf(
        "send money", "transfer money", "make a payment", "make payment", "pay ",
        "bkash", "nagad", "rocket", "upay", "upi",
        "টাকা পাঠাও", "টাকা পাঠান", "পেমেন্ট", "টাকা ট্রান্সফার", "বিকাশ", "নগদ", "বিল পরিশোধ"
    )

    private val messagingKeywords = listOf(
        "send message", "send a message", "send sms", "send text", "text message",
        "whatsapp message", "send email", "send mail",
        "মেসেজ পাঠাও", "মেসেজ পাঠান", "এসএমএস পাঠাও", "বার্তা পাঠাও", "ইমেইল পাঠাও", "মেইল পাঠাও"
    )

    private val callingKeywords = listOf(
        "call ", "make a call", "dial ",
        "কল করো", "কল করুন", "ফোন করো", "ফোন করুন"
    )

    private val questionPrefixes = listOf(
        "how ", "what ", "why ", "explain ", "can you explain", "tell me about",
        "কীভাবে ", "কিভাবে ", "কেন ", "কি "
    )

    private val confirmTokens = setOf(
        "yes", "yeah", "yep", "confirm", "confirmed", "proceed", "ok", "okay", "do it", "send it",
        "হ্যাঁ", "হ্যা", "জি", "নিশ্চিত", "করো", "করুন", "পাঠাও", "পাঠান", "ঠিক আছে"
    )

    private val cancelTokens = setOf(
        "no", "nope", "cancel", "stop", "abort", "don't", "dont", "nevermind", "never mind",
        "না", "বাতিল", "থামো", "থামুন", "দরকার নেই", "ক্যানসেল"
    )

    fun detect(rawInput: String): SensitiveActionRequest? {
        val normalized = rawInput.trim().lowercase()
        if (normalized.isEmpty()) return null

        // Informational questions (e.g., "How do I make a payment in bKash?") do not execute an action.
        if (questionPrefixes.any { normalized.startsWith(it) }) return null

        val category = when {
            paymentKeywords.any { normalized.contains(it) } -> SensitiveActionCategory.PAYMENT
            messagingKeywords.any { normalized.contains(it) } -> SensitiveActionCategory.MESSAGING
            callingKeywords.any { normalized.startsWith(it) || normalized.contains(" $it") } ->
                SensitiveActionCategory.CALLING
            else -> null
        } ?: return null

        return SensitiveActionRequest(
            originalPrompt = rawInput.trim(),
            category = category
        )
    }

    fun parseConfirmation(reply: String): ConfirmationDecision {
        val clean = reply.trim().lowercase().replace(Regex("[।,.!?\"']+"), " ").trim()
        if (clean.isEmpty()) return ConfirmationDecision.UNDECIDED

        if (cancelTokens.any { clean == it || clean.contains(" $it") || clean.startsWith("$it ") }) {
            return ConfirmationDecision.CANCELLED
        }
        if (confirmTokens.any { clean == it || clean.contains(" $it") || clean.startsWith("$it ") }) {
            return ConfirmationDecision.CONFIRMED
        }
        return ConfirmationDecision.UNDECIDED
    }
}
