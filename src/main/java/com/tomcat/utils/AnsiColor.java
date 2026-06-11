package com.tomcat.utils;

public final class AnsiColor {

    public static final String Reset = "\033[0m";
    public static final String Black = "\033[30m";
    public static final String Red = "\033[31m";
    public static final String Green = "\033[32m";
    public static final String DarkGreen = "\033[38;5;22m";
    public static final String Orange = "\033[38;5;208m";
    public static final String Yellow = "\033[33m";
    public static final String Blue = "\033[34m";
    public static final String Magenta = "\033[35m";
    public static final String Cyan = "\033[36m";
    public static final String White = "\033[37m";
    public static final String BrightBlack = "\033[90m";
    public static final String BrightRed = "\033[91m";
    public static final String BrightGreen = "\033[92m";
    public static final String BrightCyan = "\033[96m";
    public static final String BrightWhite = "\033[97m";
    public static final String Bold = "\033[1m";
    public static final String Dim = "\033[2m";
    public static final String Italic = "\033[3m";
    public static final String Underline = "\033[4m";
    public static final String Blink = "\033[5m";

    private AnsiColor() {}

    public static String Colorize(String Text, String... Colors) {
        StringBuilder Sb = new StringBuilder();
        for (String C : Colors) Sb.append(C);
        Sb.append(Text).append(Reset);
        return Sb.toString();
    }
}
