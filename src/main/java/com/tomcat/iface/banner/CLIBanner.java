package com.tomcat.iface.banner;

import com.tomcat.utils.AnsiColor;

public final class CLIBanner {

    private CLIBanner() {}

    public static void Logo() {
        String R = AnsiColor.Red;
        String W = AnsiColor.White;
        String X = AnsiColor.Reset;
        System.out.println(
            "\n" +
            "        " +
            W +
            "Server Command:" +
            X +
            "\n" +
            "            " +
            R +
            "sessions" +
            X +
            "             - List all active sessions\n" +
            "            " +
            R +
            "use <id>" +
            X +
            "             - Interact with session\n" +
            "            " +
            R +
            "exec <id> <cmd>" +
            X +
            "      - Execute command on session\n" +
            "            " +
            R +
            "logs" +
            X +
            "                 - Show recent logs\n" +
            "            " +
            R +
            "status" +
            X +
            "               - Server status\n" +
            "            " +
            R +
            "stats" +
            X +
            "                - Session statistics\n" +
            "            " +
            R +
            "kill <id>" +
            X +
            "            - Kill session\n" +
            "            " +
            R +
            "clear" +
            X +
            "                - Clear screen\n" +
            "            " +
            R +
            "help" +
            X +
            "                 - Show this help\n" +
            "            " +
            R +
            "exit" +
            X +
            "                 - Stop server and exit\n" +
            "\n" +
            "        " +
            W +
            "Agent Command:" +
            X +
            "\n" +
            "            " +
            R +
            "back" +
            X +
            "                 - Exit interactive session\n" +
            "            " +
            R +
            "<command>" +
            X +
            "            - Execute on current session\n" +
            "            " +
            R +
            "SYSINFO" +
            X +
            "              - Show complete victim info\n" +
            "            " +
            R +
            "SCREENSHOT" +
            X +
            "           - Take screenshot from victim\n" +
            "            " +
            R +
            "ELEVATE" +
            X +
            "              - Elevating check\n" +
            "            " +
            R +
            "UPLOAD" +
            X +
            "               - Upload file to victim\n" +
            "            " +
            R +
            "DOWNLOAD" +
            X +
            "             - Download file from victim\n"
        );
    }
}
