# Examples

This directory contains minimal sample projects for verifying OSS NOTICE collection.

## Maven example

```bash
java -jar target/oss-notice-collector.jar --config examples/spring-app-mvn/oss-notice-collector-config.yaml
```

Output files are written to:

```
examples/spring-app-mvn/output/
```

## Gradle example

```bash
java -jar target/oss-notice-collector.jar --config examples/spring-app-gradle/oss-notice-collector-config.yaml
```

Output files are written to:

```
examples/spring-app-gradle/output/
```

## Notes

- For Gradle on Linux/WSL, ensure the wrapper script is executable:
  - `chmod +x examples/spring-app-gradle/gradlew`

## Wrapper detection

Both Maven and Gradle resolvers automatically detect and prefer wrapper scripts:

- Maven: `mvnw.cmd` (Windows) or `mvnw` (Unix/Mac) → `mvn` fallback
- Gradle: `gradlew.bat` (Windows) or `gradlew` (Unix/Mac) → `gradle` fallback

To see which command was used, run with debug logging:

```bash
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar target/oss-notice-collector.jar --config examples/spring-app-mvn/oss-notice-collector-config.yaml
```
