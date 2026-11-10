FROM maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
COPY pom.xml /seed/pom.xml
COPY src /seed/src
RUN sed -i 's/return subtotalCents + discountCents;/return Math.max(0L, subtotalCents - discountCents);/' /seed/src/main/java/demo/PriceCalculator.java \
    && mvn -B -ntp -f /seed/pom.xml -Dmaven.repo.local=/opt/jdda-m2 test \
    && chmod -R a+rX /opt/jdda-m2
