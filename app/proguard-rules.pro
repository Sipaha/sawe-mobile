# R8 / ProGuard rules for the Sawe Mobile Android app.
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

# Our own @Serializable types live under ru.sipaha.sawe — keep both
# the $$serializer synthetic classes and the Companion factories.
-keep,includedescriptorclasses class ru.sipaha.sawe.**$$serializer { *; }
-keepclassmembers class ru.sipaha.sawe.** {
    *** Companion;
}
-keepclasseswithmembers class ru.sipaha.sawe.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio — these ship their own consumer rules; placeholder for
# future per-app overrides if a release stack trace lands on a stripped
# OkHttp internal.

# Tink is a DIRECT dependency now (com.google.crypto.tink:tink-android),
# behind `data/TinkEncryptedPrefs.kt`. No keep rules are needed for it: the
# tink-android jar ships its own `META-INF/proguard/protobuf.pro`, which AGP
# picks up automatically and which is what stops R8 renaming the fields the
# shaded protobuf-lite reflects over. Verified against the R8 configuration
# dump in `app/build/outputs/mapping/release/configuration.txt`.

# Preventive keep rules for security-sensitive `:core` classes. Neither
# is currently referenced reflectively, but both sit on the trust path
# (TLS fingerprint pinning, HMAC challenge-response) — if a future
# integration ever does load them through `Class.forName` or service
# loaders, an R8-stripped build would silently fall back to OS trust /
# unauth, which is exactly the failure mode we cannot afford.
-keep class ru.sipaha.sawe.core.FingerprintPinningTrustManager { *; }
-keep class ru.sipaha.sawe.core.HmacChallengeAuth { *; }
