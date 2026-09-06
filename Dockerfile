# Build en dos etapas: se compila con Maven y se ejecuta solo con el JRE,
# para que la imagen final no arrastre el JDK ni el repositorio de Maven.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Las dependencias se resuelven antes de copiar el código, así Docker
# reutiliza esta capa mientras el pom.xml no cambie.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuario sin privilegios
RUN addgroup -S fluxy && adduser -S fluxy -G fluxy
COPY --from=build /build/target/*.jar app.jar
RUN chown fluxy:fluxy app.jar
USER fluxy

# Render inyecta PORT; application.properties ya lo lee como server.port.
EXPOSE 8080

# MaxRAMPercentage ajusta el heap al contenedor: en instancias chicas un
# heap fijo se queda corto o provoca que el proceso muera por OOM.
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=75 -jar /app/app.jar"]
