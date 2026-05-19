ARG FABRIC_VERSION=2.5.9

FROM hyperledger/fabric-javaenv:${FABRIC_VERSION}

WORKDIR /root/chaincode-java/chaincode

COPY target/chaincode.jar /root/chaincode-java/chaincode/chaincode.jar
COPY entrypoint.sh /root/chaincode-java/chaincode/entrypoint.sh
RUN chmod +x /root/chaincode-java/chaincode/entrypoint.sh

ENTRYPOINT ["/root/chaincode-java/chaincode/entrypoint.sh"]
