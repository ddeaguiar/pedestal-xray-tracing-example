FROM java:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/rentals-0.0.1-SNAPSHOT-standalone.jar /rentals/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/rentals/app.jar"]
