jmx-cloudwatch-reporter
=======================

javagent that pushes jmx counters to aws cloudwatch

A library to report jmx counters to cloudwatch for an arbitrary java process. Library is a javaagent that needs to be started together with the java process

Usage
=====

start the java process like

java -javaagent:cloudwatch-reporter-0.0.1-SNAPSHOT-jar-with-dependencies.jar -Daws.credentials.file={credentials} -Dbeans.file={beans} -Daws.cloudwatch.endpoint={endpoint} -jar path-to-jar-file

{credential} is a path to a properties file with aws credentials as specified her: http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/PropertiesCredentials.html

{endpoint} is domain name of the endpoint url, could be monitoring.eu-west-1.amazonaws.com 

{beans} is a path to textfile with beans names for each bean where the value should be pushed to cloudwatch

could be voldemort.client:type=ClientThreadPool

all beans registered through jmx will be pushed to stdout

Build
=====

mvn package
