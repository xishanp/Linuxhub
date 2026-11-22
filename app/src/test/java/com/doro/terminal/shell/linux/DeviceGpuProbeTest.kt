package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceGpuProbeTest {
    @Test fun productDefaultIsSoftwareRendering() {
        assertEquals(GpuRenderMode.SOFTWARE, GpuRenderMode.DEFAULT)
    }

    @Test fun completeAdrenoKgslStackUsesNativeOpenGl() {
        assertEquals(
            GpuRenderMode.ZINK,
            DeviceGpuProbe.select("qti adreno 825", hasKgslDevice = true, hasDriverStack = true),
        )
    }

    @Test fun incompleteDriverStackFallsBackToSoftware() {
        assertEquals(
            GpuRenderMode.SOFTWARE,
            DeviceGpuProbe.select("qti adreno 825", hasKgslDevice = true, hasDriverStack = false),
        )
    }

    @Test fun inaccessibleKgslDeviceFallsBackToSoftware() {
        assertEquals(
            GpuRenderMode.SOFTWARE,
            DeviceGpuProbe.select("qti adreno 825", hasKgslDevice = false, hasDriverStack = true),
        )
    }

    @Test fun nonAdrenoDeviceNeverUsesHardwareRendering() {
        assertEquals(
            GpuRenderMode.SOFTWARE,
            DeviceGpuProbe.select("arm mali g715", hasKgslDevice = true, hasDriverStack = true),
        )
    }

    @Test fun completeMediatekVirtioGuestStackUsesVirgl() {
        assertEquals(
            GpuRenderMode.VIRGL,
            DeviceGpuProbe.select(
                "mediatek mt6893 mali-g77",
                hasKgslDevice = false,
                hasDriverStack = false,
                hasVirglGuestStack = true,
            ),
        )
    }

    @Test fun mediatekWithoutGuestDriverFallsBackToSoftware() {
        assertEquals(
            GpuRenderMode.SOFTWARE,
            DeviceGpuProbe.select(
                "mediatek mt6893 mali-g77",
                hasKgslDevice = false,
                hasDriverStack = false,
                hasVirglGuestStack = false,
            ),
        )
    }

    @Test fun modeMapsBackToThePersistedVendorProfile() {
        assertEquals(GpuProfile.QUALCOMM, GpuRenderMode.ZINK.toProfile())
        assertEquals(GpuProfile.MEDIATEK, GpuRenderMode.VIRGL.toProfile())
        assertEquals(GpuProfile.SOFTWARE, GpuRenderMode.SOFTWARE.toProfile())
    }

    @Test fun readableWritableCharacterDeviceIsAcceptedAsGpuHandle() {
        assertTrue(DeviceGpuProbe.isUsableKgslDevice(File("/dev/null")))
    }
}
