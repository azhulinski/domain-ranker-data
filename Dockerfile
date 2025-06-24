FROM eclipse-temurin:11-jdk AS build

WORKDIR /app

RUN apt-get update && \
    apt-get install -y curl gnupg apt-transport-https && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt

COPY build.sbt /app/
COPY project /app/project/

RUN sbt update

COPY src /app/src/

RUN sbt assembly

FROM eclipse-temurin:11-jre-alpine

WORKDIR /app

COPY --from=build /app/target/scala-2.13/DomainRankerData-assembly.jar /app/domainranker.jar

COPY src/main/resources/logback.xml /app/logback.xml

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN chown -R appuser:appgroup /app
USER appuser

ENV JAVA_OPTS="-Xms512m -Xmx1g"

# EXPOSE 8080

CMD ["java", "-Dlogback.configurationFile=/app/logback.xml", "-jar", "/app/domainranker.jar"]