package com.tomcat.core.output;

import com.tomcat.utils.AnsiColor;

public final class Logger {
    private static final long DefaultDelay = 1L;
    private Logger() {}

    public static void TypeWriter(String Text, long DelayMs) {
        for (char Ch : Text.toCharArray()) {
            System.out.print(Ch);
            System.out.flush();
            try { Thread.sleep(DelayMs); } catch (InterruptedException Ignored) {}
        }
        System.out.println();
    }

    public static void Custom(String Text) {
        TypeWriter(Text, DefaultDelay);
    }

    public static void Custom(String Text, long DelayMs) {
        TypeWriter(Text, DelayMs);
    }

    public static void Info(String Text) {
        TypeWriter(
            AnsiColor.Bold + AnsiColor.White + "[" +
            AnsiColor.Cyan + "INFO" +
            AnsiColor.White + "] " +
            AnsiColor.Dim + Text + AnsiColor.Reset,
            DefaultDelay
        );
    }

    public static void Warning(String Text) {
        TypeWriter(
            AnsiColor.Bold + AnsiColor.White + "[" +
            AnsiColor.Orange + "WARNING" +
            AnsiColor.White + "] " +
            AnsiColor.Dim + Text + AnsiColor.Reset,
            DefaultDelay
        );
    }

    public static void Error(String Text) {
        TypeWriter(
            AnsiColor.Bold + AnsiColor.White + "[" +
            AnsiColor.BrightRed + "ERROR" +
            AnsiColor.White + "] " +
            AnsiColor.Dim + Text + AnsiColor.Reset,
            DefaultDelay
        );
    }

    public static void Success(String Text) {
        TypeWriter(
            AnsiColor.Bold + AnsiColor.White + "[" +
            AnsiColor.Green + "SUCCESS" +
            AnsiColor.White + "] " +
            AnsiColor.Dim + Text + AnsiColor.Reset,
            DefaultDelay
        );
    }

    public static void Messages(String Msg) {
        TypeWriter(
            AnsiColor.Green + "[" + AnsiColor.White + "INFO" +
            AnsiColor.Green + "]: " + Msg + AnsiColor.Reset,
            DefaultDelay
        );
    }

    public static void Warnings(String Msg) {
        TypeWriter(
            AnsiColor.Red + "[" + AnsiColor.Yellow + "WARNING" +
            AnsiColor.Red + "]: " + AnsiColor.Yellow + Msg + AnsiColor.Reset,
            DefaultDelay
        );
    }

    public static void ErrorMsg(String Msg) {
        TypeWriter(
            AnsiColor.Red + "[" + AnsiColor.White + "ERROR" +
            AnsiColor.Red + "]: " + Msg + AnsiColor.Reset,
            DefaultDelay
        );
    }
}