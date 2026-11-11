package mousemaster.platform.windows;

import mousemaster.*;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.win32.StdCallLibrary;

public interface ExtendedKernel32 extends StdCallLibrary {
    ExtendedKernel32 INSTANCE = Native.load("kernel32", ExtendedKernel32.class);

    long GetTickCount64();

    void GetStartupInfoW(WinBase.STARTUPINFO startupinfo);

    String GetCommandLineA();

    int GetCurrentDirectoryW(int nBufferLength, char[] lpBuffer);

    boolean SetConsoleCtrlHandler(PhandlerRoutine handlerRoutine, boolean add);

    /** Number of processes attached to this console, or the required length if the array is short. */
    int GetConsoleProcessList(int[] processIds, int processCount);

    int GMEM_MOVEABLE = 0x0002;

    Pointer GlobalAlloc(int flags, long bytes);

    Pointer GlobalLock(Pointer memory);

    boolean GlobalUnlock(Pointer memory);

    interface PhandlerRoutine extends StdCallCallback {
        boolean callback(int dwCtrlType);
    }
}