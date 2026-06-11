package com.tomcat.iface.banner;

import com.tomcat.core.output.Logger;
import com.tomcat.utils.AnsiColor;

public final class EndBanner {

    private EndBanner() {}

    public static void EndLogo() {
        String Banner =
            "\n" +
            AnsiColor.Red +
            "      ________                  .______.\n" +
            "     /  _____/  ____   ____   __| _/\\_ |__ ___.__. ____\n" +
            "    /   \\  ___ /  _ \\ /  _ \\ / __ |  | __ <   |  |/ __ \\\n" +
            "    \\    \\_\\  (  <_> |  <_> ) /_/ |  | \\_\\ \\___  \\  ___/\n" +
            "     \\______  /\\____/ \\____/\\____ |  |___  / ____|\\___  >\n" +
            "            \\/                   \\/      \\/\\/          \\/\n" +
            AnsiColor.Reset;
        Logger.Custom(Banner, 1);
    }
}
