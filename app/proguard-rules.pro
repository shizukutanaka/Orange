# Orange — R8/ProGuard rules
#
# Rams #10: "As little design as possible." Applied to APK: as little
# code as possible ships to the user. R8's default rules handle 95% of
# what we need; the rules below cover the remaining 5% specific to
# Orange's surface area.
#
# Target: <200KB release APK. Competitors (Truecaller 40MB+, Whoscall 50MB+)
# ship entire SDK jungles. Orange ships one app.

# --- Android framework components referenced from the manifest ---------------
# R8 already keeps these via manifest scanning, but we make it explicit so a
# future package rename doesn't silently break screening or notifications.
-keep class com.orange.apple.SilentBlockerService { *; }
-keep class com.orange.apple.OnboardingActivity { *; }
-keep class com.orange.apple.RestoreReceiver { *; }
-keep class com.orange.apple.OrangeWidget { *; }
-keep class com.orange.apple.PauseTile { *; }
-keep class com.orange.apple.RoleMonitorReceiver { *; }
-keep class com.orange.apple.CallStateObserver { *; }
-keep class com.orange.apple.WeeklyDigest { *; }
-keep class com.orange.apple.FamilyCallbackTile { *; }
-keep class com.orange.apple.EngineWarmup { *; }
# HistoryActivity and SettingsActivity were missing from this list until 2026-07
# while the other ten were present — so the "future package rename doesn't
# silently break" guarantee above had a hole in exactly the two screens a user
# reaches from the widget and the family tile. R8's manifest scanning meant no
# live bug, but the stated protection was not what the comment claimed.
# check_comprehensive.sh 13/14 now asserts this list covers every manifest
# component, so the two can't drift apart again.
-keep class com.orange.apple.HistoryActivity { *; }
-keep class com.orange.apple.SettingsActivity { *; }

# --- Kotlin reflection: we don't use it. If code below breaks, that's the
# bug, not a missing keep rule. Keeping this comment as a tripwire for
# future contributors.
# -keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# --- Strip debug/log calls from release builds --------------------------------
# Rams #6 (honest): shipping debug log spew leaks implementation to logcat
# snooping apps. Remove them at link time.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# --- Remove Kotlin intrinsics null checks in release ---------------------------
# These are belt-and-suspenders checks the compiler inserts; Orange's hot path
# is a call-screening callback that should allocate nothing.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(java.lang.Object);
    static void checkNotNull(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}

# --- Compose keeps itself mostly; we just need the entry points ----------------
-keep class androidx.compose.runtime.** { *; }

# --- Obfuscation: on, but don't rename emergency numbers class ---
# Being able to read a crash report and see "EmergencyWhitelist.isEmergency"
# is more valuable than the 40 bytes saved by renaming it to "a.b".
-keep class com.orange.apple.EmergencyWhitelist { *; }
