FROM eclipse-temurin:17-jre

WORKDIR /root/chaincode-java/chaincode

COPY target/chaincode.jar /root/chaincode-java/chaincode/chaincode.jar
COPY entrypoint.sh /root/chaincode-java/chaincode/entrypoint.sh
RUN chmod +x /root/chaincode-java/chaincode/entrypoint.sh

ENTRYPOINT ["/root/chaincode-java/chaincode/entrypoint.sh"]
