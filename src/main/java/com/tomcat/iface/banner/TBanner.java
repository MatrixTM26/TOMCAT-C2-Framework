package com.tomcat.iface.banner;

import com.tomcat.core.output.Logger;
import com.tomcat.utils.AnsiColor;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

public final class TBanner {

    private static final List<String> Quotes = List.of(
        "                             ♠  Access Denied? Watch Me ♠                      ",
        "                      ♠  Encrypt Your Fear, Decrypt Your Power ♠               ",
        "                           ♠  Peace Was Never An Option ♠                      ",
        "                                ♠  TRICK OR TRAPPED ♠                          ",
        "                     ♠  Boys Life In Peace, But Man Want A War! ♠              ",
        "                            ♠  Break - Breach - Dominate ♠                     ",
        "                              ♠  Ghost In Your Machine ♠                       ",
        "                               ♠  Hunt. Hack. Conquer ♠                        "
    );

    private TBanner() {}

    public static void Logo() {
        String Msg = Quotes.get(new Random().nextInt(Quotes.size()));
        String Banner =
            "\n" +
            AnsiColor.Bold +
            AnsiColor.Red +
            "\n" +
            "        ___________________      _____  _________     ________________ _________  ________\n" +
            "        \\__    ___/\\_____  \\    /     \\ \\_   ___ \\   /  _  \\__    ___/ \\_   ___ \\ \\_____  \\\n" +
            "          |    |    /   |   \\  /  \\ /  \\/    \\  \\/  /  /_\\  \\|    |    /    \\  \\/  /  ____/\n" +
            "          |    |   /    |    \\/    Y    \\     \\____/    |    \\    |    \\     \\____/       \\\n" +
            "          |____|   \\_______  /\\____|__  /\\______  /\\____|__  /____|     \\______  /\\_______ \\\n" +
            "                           \\/         \\/        \\/         \\/                  \\/         \\/\n" +
            "                                                Framework V3.0\n" +
            AnsiColor.White +
            Msg +
            AnsiColor.Reset +
            "\n";
        Logger.Custom(Banner, 1);
    }
}
