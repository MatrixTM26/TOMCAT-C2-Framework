package com.tomcat.iface.banner;

import com.tomcat.core.output.Logger;
import com.tomcat.utils.AnsiColor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AUTHBanner {
    private AUTHBanner() {}

    public static void Logo() {
        LocalDateTime Now = LocalDateTime.now();
        String Time = Now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String Date = Now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String Banner =
            "\n" +
            "            " + AnsiColor.Red + "[" + AnsiColor.White + "+" + AnsiColor.Red + "]  " + AnsiColor.Red + "Author       :   " + AnsiColor.White + "MatrixTM26\n" +
            "            " + AnsiColor.Red + "[" + AnsiColor.White + "+" + AnsiColor.Red + "]  " + AnsiColor.Red + "Github       :   " + AnsiColor.White + "MatrixTM26\n" +
            "            " + AnsiColor.Red + "[" + AnsiColor.White + "+" + AnsiColor.Red + "]  " + AnsiColor.Red + "Version      :   " + AnsiColor.White + "2.0 (Java)\n" +
            "            " + AnsiColor.Red + "[" + AnsiColor.White + "+" + AnsiColor.Red + "]  " + AnsiColor.Red + "Time         :   " + AnsiColor.White + Time + "\n" +
            "            " + AnsiColor.Red + "[" + AnsiColor.White + "+" + AnsiColor.Red + "]  " + AnsiColor.Red + "Date         :   " + AnsiColor.White + Date + "\n" +
            AnsiColor.Reset;
        Logger.Custom(Banner, 1);
    }
}
