<p align="center"><img src="https://github.com/N1nt4nd0/Xenobyte/blob/master/logo.png"></p>

# X3N0BYT3 - Hack Pack for Minecraft 1.7.10 Forge     [![Build status](https://ci.appveyor.com/api/projects/status/qjnhgxe3cmcy0y6y/branch/master?svg=true)](https://ci.appveyor.com/project/N1nt4nd0/xenobyte/branch/master)

# A short video guide to use

  - https://youtu.be/nZAEb3mmXL4
  
# Building (сборка)

Uses [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) (Gradle 8.14.3, wrapper included).

  - Requires JDK 17+ to run Gradle (для запуска Gradle нужен JDK 17+). The mod itself is compiled with Java 8, which Gradle downloads automatically if it is not installed (Java 8 для компиляции скачивается автоматически).
  - IntelliJ IDEA: `File → Open` → select the project folder → trust the Gradle project. In `Settings → Build Tools → Gradle` set **Gradle JVM** to JDK 17 or newer. The first sync downloads and decompiles Minecraft (takes several minutes).
  - `gradlew build` → `build/libs/xenobyte-1.0.0.jar` (ProGuard-obfuscated + reobfuscated, ready to use) and `mainclass.txt`
  - `gradlew runClient` → development client in `run/`
  - `gradlew runObfClient` → test the final jar in a normal (obfuscated) Forge client

# Possible causes of crashes

  - Using an injector that does not match the operating system or java bitness (использование инжекта не соответствующей с операционной системой или java разрядности)
  - Minecraft version does not match the declared (версия Minecraft не соответствует заявленной)
  - Protection against injection in the launcher (защита от инжекта в лаунчере)
  - Using java version below 1.8 (использование java версии ниже 1.8)
  
# Links

  - YouTube https://www.youtube.com/channel/UClXGh0w1BiBEyxn7iFI4dsA
  - Instagram https://www.instagram.com/n1nt4nd0
  - Telegram https://t.me/N1nt4nd0
  - Discord - N1nt4nd0#0613

# Thanks

  - Tenon4ik, Left4Dead, radioegor146, SongJ