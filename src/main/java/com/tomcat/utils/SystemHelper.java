package com.tomcat.utils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public final class SystemHelper {

    private SystemHelper() {}

    public static boolean IsWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static String GetPlatform() {
        return IsWindows() ? "Windows" : "Linux/Unix";
    }

    public static void ClearScreen() {
        try {
            if (IsWindows()) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                new ProcessBuilder("clear").inheritIO().start().waitFor();
            }
        } catch (IOException | InterruptedException Ignored) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        }
    }

    public static boolean ValidatePort(int Port) {
        return Port >= 1 && Port <= 65535;
    }

    public static boolean ValidateHost(String Host) {
        try {
            InetAddress.getByName(Host);
            return true;
        } catch (Exception Ignored) {
            return false;
        }
    }

    public static String FormatUptime(long Seconds) {
        long H = Seconds / 3600;
        long M = (Seconds % 3600) / 60;
        long S = Seconds % 60;
        return String.format("%02d:%02d:%02d", H, M, S);
    }

    public static boolean IsPortAvailable(int Port) {
        try (Socket Ignored = new Socket("localhost", Port)) {
            return false;
        } catch (IOException Ignored) {
            return true;
        }
    }
}
