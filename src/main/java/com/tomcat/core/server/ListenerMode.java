package com.tomcat.core.server;

/**
 * ListenerMode — defines what connection types a server socket accepts.
 *
 *  multi  → accepts all non-TLS connections (raw TCP, TOMCAT agent, HTTP, Meterpreter-style)
 *  http   → accepts HTTP beacon agents only
 *  https  → accepts HTTPS beacon agents only (TLS, no client cert)
 *  tls    → accepts TOMCAT agents over TLS only (no client cert)
 *  mtls   → accepts TOMCAT agents over mTLS only (client cert required)
 *  fmtls  → accepts TOMCAT agents over mTLS + HTTPS/mTLS beacon (full mutual TLS)
 *  raw    → accepts raw TCP reverse shells only (no protocol detection, plaintext)
 */
public enum ListenerMode {
    MULTI,
    HTTP,
    HTTPS,
    TLS,
    MTLS,
    FMTLS,
    RAW;

    public static ListenerMode FromString(String Value) {
        if (Value == null) return MULTI;
        return switch (Value.trim().toLowerCase()) {
            case "multi", "meterpreter" -> MULTI;
            case "http" -> HTTP;
            case "https" -> HTTPS;
            case "tls" -> TLS;
            case "mtls" -> MTLS;
            case "fmtls", "full-mtls" -> FMTLS;
            case "raw", "plain", "tcp" -> RAW;
            default -> MULTI;
        };
    }

    public boolean RequiresTls() {
        return this == TLS || this == MTLS || this == FMTLS || this == HTTPS;
    }

    public boolean RequiresClientCert() {
        return this == MTLS || this == FMTLS;
    }

    public boolean AcceptsRawShell() {
        return this == MULTI || this == RAW;
    }

    public boolean AcceptsHttp() {
        return this == MULTI || this == HTTP || this == HTTPS || this == FMTLS;
    }

    public boolean AcceptsTomcatAgent() {
        return this == MULTI || this == TLS || this == MTLS || this == FMTLS || this == RAW;
    }
}
