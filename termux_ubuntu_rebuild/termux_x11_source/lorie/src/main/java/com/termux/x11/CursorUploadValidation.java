package com.termux.x11;

/**
 * The cursor bitmap is transported through a fixed 512x512 shared buffer.
 * Keep the Java-side contract explicit so test and future callers reject
 * out-of-range dimensions before they reach the native upload path.
 */
final class CursorUploadValidation {
    private CursorUploadValidation() {
    }

    static boolean isValid(int width, int height) {
        return width > 0 && height > 0 && width < 512 && height < 512;
    }
}
