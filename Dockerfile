# ---- Stage 1: Build ----
# The project targets Java 26 (see pom.xml), so the build stage must use JDK 26.
FROM eclipse-temurin:26-jdk AS build

WORKDIR /app

# Copy Maven wrapper first for layer caching
COPY mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw

# Copy pom.xml and fetch dependencies (cached layer)
COPY pom.xml ./
RUN ./mvnw dependency:go-offline -B

# Copy source and build the fat jar
COPY src ./src
RUN ./mvnw package -q -DskipTests

# ---- Stage 2: Runtime ----
# Bytecode is compiled for Java 26, so the runtime JRE must also be 26.
FROM eclipse-temurin:26-jre

WORKDIR /app

# Interactive terminal sessions need a TTY
ENV TERM=xterm-256color

# Copy the runnable jar from the build stage
COPY --from=build /app/target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar ./p2p-chat.jar

ENTRYPOINT ["java", "-jar", "p2p-chat.jar"]
