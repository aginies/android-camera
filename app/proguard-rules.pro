# JmDNS relies on reflection; keep it.
-keep class org.jmdns.** { *; }
-dontwarn org.jmdns.**

# Ktor/Netty ship their own consumer ProGuard rules.
-dontwarn io.netty.**
-dontwarn io.ktor.**
