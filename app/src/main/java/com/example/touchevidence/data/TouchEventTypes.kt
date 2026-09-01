package com.example.touchevidence.data

object TouchEventTypes {
    const val ScreenTouchClick = "SCREEN_TOUCH_CLICK"
    const val ScreenSwipeScroll = "SCREEN_SWIPE_SCROLL"
    const val AppSwitch = "APP_SWITCH"
    const val ViewFocused = "VIEW_FOCUSED"

    val touchEvents = listOf(ScreenTouchClick, ScreenSwipeScroll, ViewFocused)
}
