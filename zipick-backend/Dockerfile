# 백엔드 이미지: Gradle로 bootJar 를 만들고, 실행 이미지에는 JRE 와 jar 만 담는다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 의존성만 먼저 받아 두면 소스만 바뀐 재빌드가 빨라진다.
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v plain)" app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 zipick
COPY --from=build /app/app.jar app.jar
USER zipick
ENV TZ=Asia/Seoul \
    JAVA_OPTS="-XX:MaxRAMPercentage=40"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
