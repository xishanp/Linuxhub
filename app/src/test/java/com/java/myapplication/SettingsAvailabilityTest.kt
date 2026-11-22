package com.java.myapplication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAvailabilityTest {
    @Test fun nativeResolutionOnlyShowsAlwaysRelevantControls() {
        val availability = settingsAvailability("native", "1")

        assertFalse(availability.showDisplayScale)
        assertFalse(availability.showExactResolution)
        assertFalse(availability.showCustomResolution)
        assertFalse(availability.showDisplaySizingControls)
        assertFalse(availability.showScaleTouchpad)
    }

    @Test fun scaledResolutionOnlyShowsScaleAndTouchpadScaling() {
        val availability = settingsAvailability("scaled", "1")

        assertTrue(availability.showDisplayScale)
        assertFalse(availability.showExactResolution)
        assertFalse(availability.showCustomResolution)
        assertFalse(availability.showDisplaySizingControls)
        assertTrue(availability.showScaleTouchpad)
    }

    @Test fun exactResolutionShowsExactValueAndSizingControls() {
        val availability = settingsAvailability("exact", "1")

        assertFalse(availability.showDisplayScale)
        assertTrue(availability.showExactResolution)
        assertFalse(availability.showCustomResolution)
        assertTrue(availability.showDisplaySizingControls)
        assertTrue(availability.showScaleTouchpad)
    }

    @Test fun customResolutionShowsCustomValueAndSizingControls() {
        val availability = settingsAvailability("custom", "1")

        assertFalse(availability.showDisplayScale)
        assertFalse(availability.showExactResolution)
        assertTrue(availability.showCustomResolution)
        assertTrue(availability.showDisplaySizingControls)
        assertTrue(availability.showScaleTouchpad)
    }

    @Test fun touchpadScalingRequiresTrackpadMode() {
        assertFalse(settingsAvailability("scaled", "2").showScaleTouchpad)
        assertFalse(settingsAvailability("scaled", "3").showScaleTouchpad)
    }

    @Test fun missingPreferencesFallBackToNative() {
        val availability = settingsAvailability(null, null)

        assertFalse(availability.showDisplayScale)
        assertFalse(availability.showExactResolution)
        assertFalse(availability.showCustomResolution)
        assertFalse(availability.showDisplaySizingControls)
        assertFalse(availability.showScaleTouchpad)
    }
}
