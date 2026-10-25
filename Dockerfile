FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
RUN mkdir -p /root/.m2 && printf '<?xml version="1.0" encoding="UTF-8"?>\n<toolchains>\n  <toolchain>\n    <type>jdk</type>\n    <provides>\n      <version>21</version>\n      <vendor>temurin</vendor>\n    </provides>\n    <configuration>\n      <jdkHome>%s</jdkHome>\n    </configuration>\n  </toolchain>\n</toolchains>\n' "$JAVA_HOME" > /root/.m2/toolchains.xml
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ledger && adduser -S ledger -G ledger
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN chown -R ledger:ledger /app
USER ledger
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
