# R8 / ProGuard rules for the SPK Remote Android app.
#
# Most third-party deps (OkHttp, Okio, AndroidX) already ship their own
# consumer rules — we only need to add the bits R8 can't infer from
# kotlinx.serialization's compiler-generated companions.

# kotlinx.serialization — keep generated $serializer companions reachable.
# Without these, R8 prunes the synthetic serializer classes and any
# `decodeFromJsonElement` / `encodeToJsonElement` call crashes at runtime
# with `SerializationException: Class X is not registered for polymorphic
# serialization` or similar.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Our own @Serializable types live under ru.sipaha.spkremote — keep both
# the $$serializer synthetic classes and the Companion factories.
-keep,includedescriptorclasses class ru.sipaha.spkremote.**$$serializer { *; }
-keepclassmembers class ru.sipaha.spkremote.** {
    *** Companion;
}
-keepclasseswithmembers class ru.sipaha.spkremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio — these ship their own consumer rules; placeholder for
# future per-app overrides if a release stack trace lands on a stripped
# OkHttp internal.

# AndroidX security-crypto pulls in Tink, which carries compile-only
# references to com.google.errorprone.annotations.*. These are NOT
# present at runtime (they're build-time `@CheckReturnValue` / `@Immutable`
# markers), so R8 just needs to be told to ignore the missing references.
# The androidx.security:security-crypto:1.1.0-alpha06 consumer rules
# don't ship these `-dontwarn`s, hence we add them here.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Preventive keep rules for security-sensitive `:core` classes. Neither
# is currently referenced reflectively, but both sit on the trust path
# (TLS fingerprint pinning, HMAC challenge-response) — if a future
# integration ever does load them through `Class.forName` or service
# loaders, an R8-stripped build would silently fall back to OS trust /
# unauth, which is exactly the failure mode we cannot afford.
-keep class ru.sipaha.spkremote.core.FingerprintPinningTrustManager { *; }
-keep class ru.sipaha.spkremote.core.HmacChallengeAuth { *; }
