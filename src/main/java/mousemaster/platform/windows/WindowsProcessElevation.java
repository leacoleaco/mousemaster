package mousemaster.platform.windows;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

/**
 * Whether this process token is elevated. A non-elevated mousemaster cannot draw
 * above administrator windows, and UI Automation cannot read those windows either.
 */
public final class WindowsProcessElevation {

    /** WinNT.TOKEN_INFORMATION_CLASS.TokenElevation */
    private static final int TOKEN_ELEVATION = 20;

    private WindowsProcessElevation() {
    }

    public static boolean isElevated() {
        WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
        try {
            if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(),
                    WinNT.TOKEN_QUERY, token))
                return false;
            IntByReference elevation = new IntByReference();
            IntByReference returned = new IntByReference();
            if (!ExtendedAdvapi32.INSTANCE.GetTokenInformation(token.getValue(),
                    TOKEN_ELEVATION, elevation, 4, returned))
                return false;
            return elevation.getValue() != 0;
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
    }
}
