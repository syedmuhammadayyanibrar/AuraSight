package com.aurasight.app.ai


interface NavigationActionDelegate {
    fun navigateTo(tab: String)
}

class NavigationToolSet(private val delegate: NavigationActionDelegate) : ToolSet {

    @Tool(description = "Navigate the user's screen to a specific tab for sighted observers. Valid tabs are: 'CART', 'KHATA', 'VOICE'. DO NOT use this if the user asks to open the Camera.")
    fun navigateTo(
        @ToolParam(description = "The tab to navigate to ('CART', 'KHATA', or 'VOICE')") tab: String
    ): String {
        val upperTab = tab.uppercase()
        if (upperTab in listOf("CART", "KHATA", "VOICE")) {
            delegate.navigateTo(upperTab)
            return "Navigated to $upperTab"
        }
        return "Invalid tab: $tab"
    }
}
