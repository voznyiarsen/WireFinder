# WireFinder ProGuard Rules

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltApplication { *; }

# Keep Room entities and DAOs
-keep class com.wirefinder.data.** { *; }

# Keep Timber for logging
-keep class timber.log.** { *; }

# Keep FileProvider
-keep class androidx.core.content.FileProvider { *; }

# SQLCipher uses JNI; keep native method holders and support factory entry point
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.SupportOpenHelperFactory { *; }

# KeePassJava2/Jackson optional StAX/AQute classes are not needed at runtime
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn javax.xml.stream.**
