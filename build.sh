#!/bin/bash
set -e
echo "Custom build script running..."
mvn clean package -DskipTests
cp target/chaincode.jar /chaincode/output/chaincode.jar
touch /chaincode/output/.uberjar
echo "Build finished successfully!"
exit 0
