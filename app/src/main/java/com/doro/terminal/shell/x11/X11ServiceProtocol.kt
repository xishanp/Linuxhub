package com.doro.terminal.shell.x11

internal const val X11_DESCRIPTOR = "com.doro.terminal.shell.x11.IX11Server"
internal const val X11_TRANSACTION_START = android.os.IBinder.FIRST_CALL_TRANSACTION
internal const val X11_TRANSACTION_CONNECTION = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
internal const val X11_TRANSACTION_STATUS = android.os.IBinder.FIRST_CALL_TRANSACTION + 2
internal const val X11_TRANSACTION_CONTINUOUS_FRAMES = android.os.IBinder.FIRST_CALL_TRANSACTION + 3
