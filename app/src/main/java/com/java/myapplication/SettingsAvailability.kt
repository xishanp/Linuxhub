package com.java.myapplication

internal data class SettingsAvailability(
    val showDisplayScale: Boolean,
    val showExactResolution: Boolean,
    val showCustomResolution: Boolean,
    val showDisplaySizingControls: Boolean,
    val showScaleTouchpad: Boolean,
)

internal fun settingsAvailability(
    displayResolutionMode: String?,
    touchMode: String?,
): SettingsAvailability {
    val displayMode = displayResolutionMode ?: "native"
    return SettingsAvailability(
        showDisplayScale = displayMode == "scaled",
        showExactResolution = displayMode == "exact",
        showCustomResolution = displayMode == "custom",
        showDisplaySizingControls = displayMode == "exact" || displayMode == "custom",
        showScaleTouchpad = touchMode == "1" && displayMode != "native",
    )
}
