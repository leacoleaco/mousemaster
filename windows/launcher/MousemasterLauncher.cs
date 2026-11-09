using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

internal static class Program
{
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(IntPtr hWnd, string text, string caption, uint type);

    private static int Main()
    {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        if (dir.EndsWith("\\") || dir.EndsWith("/"))
            dir = dir.Substring(0, dir.Length - 1);

        string java = Path.Combine(dir, "runtime", "bin", "javaw.exe");
        string config = Path.Combine(dir, "mousemaster.properties");
        string jar = NewestJar(dir);
        if (!File.Exists(java) || jar == null || !File.Exists(config))
        {
            MessageBoxW(IntPtr.Zero,
                    "找不到运行所需的文件。请把本程序放在安装目录中，并确认同目录下有 runtime、mousemaster.properties，以及 mousemaster 的 jar。",
                    "Mousemaster",
                    0x00000010);
            return 1;
        }

        try
        {
            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = java;
            start.Arguments = "--enable-native-access=ALL-UNNAMED -jar \"" + jar
                    + "\" \"--configuration-file=" + config + "\"";
            start.WorkingDirectory = dir;
            start.UseShellExecute = false;
            Process.Start(start);
            return 0;
        }
        catch (Exception ex)
        {
            MessageBoxW(IntPtr.Zero, ex.Message, "Mousemaster", 0x00000010);
            return 1;
        }
    }

    private static string NewestJar(string dir)
    {
        string[] jars = Directory.GetFiles(dir, "mousemaster-*-jar-with-dependencies.jar");
        string best = null;
        DateTime bestTime = DateTime.MinValue;
        for (int i = 0; i < jars.Length; i++)
        {
            DateTime time = File.GetLastWriteTimeUtc(jars[i]);
            if (best == null || time > bestTime)
            {
                best = jars[i];
                bestTime = time;
            }
        }
        return best;
    }
}
