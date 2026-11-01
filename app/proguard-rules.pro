# Media3, Coil and coroutines ship their own consumer rules.
# Guava (pulled in by Media3) references annotation-only classes that are not on the classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
