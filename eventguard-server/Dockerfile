FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# ponytail: 把 ~/.m2 挂到 BuildKit 缓存，避免每次 docker build 在线重拉全部依赖（曾卡 11 分钟）
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn package -B

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/eventguard-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
