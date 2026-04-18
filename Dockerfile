ARG FABRIC_VERSION=2.5.9

FROM hyperledger/fabric-javaenv:${FABRIC_VERSION}

WORKDIR /root/chaincode-java/chaincode

# Use local build context like kv-node Dockerfile.
COPY target/chaincode.jar /root/chaincode-java/chaincode/chaincode.jar

ENTRYPOINT ["java", "-jar", "/root/chaincode-java/chaincode/chaincode.jar"]
