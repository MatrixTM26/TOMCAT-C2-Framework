package com.tomcat.core.output;

import com.tomcat.utils.AnsiColor;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class Logger {

    public enum Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    private static final DateTimeFormatter TimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long TypeDelay = 1L;

    private static volatile Level CurrentLevel = Level.INFO;
    private static volatile boolean Verbose = false;
    private static volatile boolean FileEnabled = false;
    private static volatile String LogFilePath = "logs/tomcat-c2.log";
    private static volatile int MaxEntries = 1000;

    private static final BlockingQueue<String> FileQueue = new ArrayBlockingQueue<>(4096);
    private static Thread WriterThread;

    private Logger() {}

    public static void Configure(String Level, boolean IsVerbose, boolean EnableFile, String FilePath, int MaxEnt) {
        CurrentLevel = parseLevel(Level);
        Verbose = IsVerbose;
        FileEnabled = EnableFile;
        LogFilePath = FilePath;
        MaxEntries = MaxEnt;
        if (EnableFile) StartFileWriter(FilePath);
    }

    private static Level parseLevel(String S) {
        try {
            return Level.valueOf(S.toUpperCase());
        } catch (Exception E) {
            return Level.INFO;
        }
    }

    private static void StartFileWriter(String Path) {
        try {
            Files.createDirectories(Paths.get(Path).getParent());
        } catch (Exception Ignored) {}
        WriterThread = new Thread(
            () -> {
                try (BufferedWriter W = new BufferedWriter(new FileWriter(Path, true))) {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            String Line = FileQueue.take();
                            W.write(Line);
                            W.newLine();
                            W.flush();
                        } catch (InterruptedException E) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (IOException E) {
                    System.err.println("[Logger] File writer failed: " + E.getMessage());
                }
            },
            "LogFileWriter"
        );
        WriterThread.setDaemon(true);
        WriterThread.start();
    }

    private static String Timestamp() {
        return LocalDateTime.now().format(TimeFmt);
    }

    private static void Emit(Level MsgLevel, String Tag, String AnsiTag, String Msg) {
        if (MsgLevel.ordinal() < CurrentLevel.ordinal()) return;
        String Plain = "[" + Timestamp() + "] [" + Tag + "] " + Msg;
        String Colored =
            AnsiColor.Bold +
            AnsiColor.White +
            "[" +
            AnsiTag +
            AnsiColor.White +
            "] " +
            AnsiColor.Dim +
            Msg +
            AnsiColor.Reset;
        TypeWrite(Colored);
        if (FileEnabled) FileQueue.offer(Plain);
    }

    private static void TypeWrite(String Text) {
        for (char Ch : Text.toCharArray()) {
            System.out.print(Ch);
            System.out.flush();
            if (TypeDelay > 0) {
                try {
                    Thread.sleep(TypeDelay);
                } catch (InterruptedException Ignored) {}
            }
        }
        System.out.println();
    }

    public static void Info(String Msg) {
        Emit(Level.INFO, "INFO", AnsiColor.Cyan + "INFO", Msg);
    }

    public static void Warn(String Msg) {
        Emit(Level.WARN, "WARN", AnsiColor.Orange + "WARN", Msg);
    }

    public static void Error(String Msg) {
        Emit(Level.ERROR, "ERROR", AnsiColor.BrightRed + "ERROR", Msg);
    }

    public static void Debug(String Msg) {
        Emit(Level.DEBUG, "DEBUG", AnsiColor.Magenta + "DEBUG", Msg);
    }

    public static void Verbose(String Msg) {
        if (!Verbose) return;
        Emit(Level.VERBOSE, "VERBOSE", AnsiColor.Dim + "VERBOSE", Msg);
    }

    public static void Success(String Msg) {
        Emit(Level.INFO, "OK", AnsiColor.Green + "OK", Msg);
    }

    public static void Messages(String Msg) {
        Info(Msg);
    }

    public static void Warnings(String Msg) {
        Warn(Msg);
    }

    public static void ErrorMsg(String Msg) {
        Error(Msg);
    }

    public static void Warning(String Msg) {
        Warn(Msg);
    }

    public static void Shutdown() {
        if (WriterThread != null) WriterThread.interrupt();
    }

    public static void Custom(String Text) {
        TypeWrite(Text);
    }

    public static void Custom(String Text, long DelayMs) {
        for (char Ch : Text.toCharArray()) {
            System.out.print(Ch);
            System.out.flush();
            if (DelayMs > 0) {
                try {
                    Thread.sleep(DelayMs);
                } catch (InterruptedException Ignored) {}
            }
        }
        System.out.println();
    }
}
