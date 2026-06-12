package com.tomcat.iface.banner;

import com.tomcat.utils.AnsiColor;

public final class CLIBanner {

    private CLIBanner() {}

    public static void Logo() {
        String R = AnsiColor.Red;
        String W = AnsiColor.White;
        String D = AnsiColor.Dim;
        String X = AnsiColor.Reset;
        System.out.println(
            "\n" +
            "        " +
            W +
            "Server Commands:" +
            X +
            "\n" +
            "            " +
            R +
            "sessions" +
            X +
            "                  - List all active sessions\n" +
            "            " +
            R +
            "use <id>" +
            X +
            "                  - Enter interactive session\n" +
            "            " +
            R +
            "exec <id> <cmd>" +
            X +
            "           - Execute command on one session\n" +
            "            " +
            R +
            "broadcast <id,id,...> <cmd>" +
            X +
            " - Execute on specific sessions\n" +
            "            " +
            R +
            "broadcast all <cmd>" +
            X +
            "       - Execute on ALL sessions\n" +
            "            " +
            R +
            "kill <id>" +
            X +
            "                 - Terminate session\n" +
            "            " +
            R +
            "status" +
            X +
            "                    - Server status + key\n" +
            "            " +
            R +
            "stats" +
            X +
            "                     - Session statistics\n" +
            "            " +
            R +
            "logs" +
            X +
            "                      - Show recent logs\n" +
            "            " +
            R +
            "clear" +
            X +
            "                     - Clear screen\n" +
            "            " +
            R +
            "help" +
            X +
            "                      - Show this help\n" +
            "            " +
            R +
            "exit" +
            X +
            "                      - Stop server and exit\n" +
            "\n" +
            "        " +
            W +
            "Interactive Session Commands:" +
            X +
            "\n" +
            "            " +
            R +
            "back" +
            X +
            "                      - Return to main console\n" +
            "            " +
            R +
            "<any command>" +
            X +
            "             - Execute on current session\n" +
            "\n" +
            "        " +
            W +
            "Broadcast Examples:" +
            X +
            "\n" +
            "            " +
            D +
            "broadcast 1,2,3 whoami" +
            X +
            "\n" +
            "            " +
            D +
            "broadcast all id" +
            X +
            "\n" +
            "            " +
            D +
            "broadcast all uname -a" +
            X +
            "\n"
        );
    }
}
