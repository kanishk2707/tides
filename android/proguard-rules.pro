# libGDX
-keep class com.badlogic.gdx.** { *; }
-keep class com.badlogic.gdx.backends.android.** { *; }
-keepclassmembers class com.badlogic.gdx.backends.android.AndroidInput* { <init>(...); }
-dontwarn com.badlogic.gdx.**
-dontwarn com.badlogic.gdx.jnigen.**

# Java-WebSocket
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
-dontwarn org.slf4j.**

# Shared simulation is reflected over by the snapshot codec's enum tables
-keep enum com.polariz.aethertides.shared.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses
