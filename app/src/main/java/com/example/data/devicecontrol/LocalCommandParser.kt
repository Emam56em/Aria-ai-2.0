package com.example.data.devicecontrol

object LocalCommandParser {

    private val openTriggers = listOf(
        "open", "launch", "start",
        "খোলো", "খুলুন", "খুলে দাও", "খোল", "চালু করো", "চালু কর", "ওপেন"
    )

    private val questionBlockers = listOf(
        "how", "what", "why", "explain", "tell me", "write",
        "কীভাবে", "কিভাবে", "কেন", "ব্যাখ্যা", "কী", "কি"
    )

    fun parse(rawInput: String): DeviceCommand? {
        val text = rawInput.trim().lowercase()
        if (text.isEmpty()) return null

        // Flashlight commands (English & Bengali / Banglish)
        val mentionsFlashlight = listOf(
            "flashlight", "torch", "flash light",
            "টর্চ", "ফ্ল্যাশলাইট", "ফ্ল্যাশ", "লাইট"
        ).any { text.contains(it) }

        if (mentionsFlashlight) {
            val turnOn = listOf("turn on", "switch on", "enable", "on", "চালু", "জ্বালাও", "অন")
                .any { text.contains(it) }
            val turnOff = listOf("turn off", "switch off", "disable", "off", "বন্ধ", "নিভাও", "অফ")
                .any { text.contains(it) }
            return when {
                turnOff && !turnOn -> DeviceCommand.SetFlashlight(false)
                turnOn && !turnOff -> DeviceCommand.SetFlashlight(true)
                text.contains("toggle") || text.contains("টগল") -> DeviceCommand.ToggleFlashlight
                else -> DeviceCommand.ToggleFlashlight
            }
        }

        // Open any installed app (English & Bengali)
        val wantsOpen = openTriggers.any { trigger -> text.contains(trigger) }
        val looksLikeQuestion = questionBlockers.any { q ->
            text.startsWith("$q ") || text.contains(" $q ")
        }
        if (wantsOpen && !looksLikeQuestion) {
            val targetQuery = FuzzyAppMatcher.extractTargetQuery(rawInput)
            if (targetQuery.isNotBlank()) {
                // Direct reverse-DNS package name (e.g. "open com.google.android.youtube")
                if (targetQuery.contains('.') && !targetQuery.contains(' ')) {
                    return DeviceCommand.OpenPackage(targetQuery)
                }
                // Check built-in shortcut match first for exact/canonical commands (e.g., Calculator, Maps, Camera)
                val shortcut = FuzzyAppMatcher.matchSupportedShortcut(targetQuery)
                if (shortcut != null) {
                    return DeviceCommand.OpenApp(shortcut)
                }
                // Otherwise use universal fuzzy app-name launcher
                return DeviceCommand.OpenAppByName(targetQuery)
            }
        }

        return null
    }
}
