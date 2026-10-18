# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontobfuscate
-keep,allowoptimization class is.xyz.mpv.** { public protected *; }

# Optional integrations referenced by SMBJ and its event bus are not available
# on Android. The app uses password/anonymous SMB authentication, not SPNEGO.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**

# smbj wires transport/auth/exception classes through factories; R8 strips them otherwise
# (AuthenticationContext, DirectTcpTransportFactory, SMBException, BCSecurityProvider, ...),
# which surfaces as "socket closed" during authenticate on release builds.
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**

# smbj event bus (mbassador/mbassy) reflectively instantiates ReflectiveHandlerInvocation
# via its SubscriptionContext constructor; R8 removes the constructor otherwise.
-keep class net.engio.** { *; }
-dontwarn net.engio.**

# jcifs-ng share enumeration protocol, NTLM and signing classes are reached dynamically.
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# jcifs-ng needs MD4 (NTLM hash) and AESCMAC (SMB3 signing); these Spis are registered
# into BouncyCastle by class name and instantiated reflectively, so keep them under R8.
-keep class live.mehiz.mpvkt.network.smb.SmbCrypto$* { *; }

-keep class is.xyz.mpv.MPV { *; }
-keep class is.xyz.mpv.BaseMPVView { *; }