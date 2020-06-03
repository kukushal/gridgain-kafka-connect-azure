# gridgain-kafka-connect-azure
Integrating various MS Azure resources with GridGain using Kafka Connect

## Prerequisites
- This repo in `$HOME/Dev/gridgain-kafka-connect-azure`
- GridGain Enterprise or Ultimate 8.7.17 in `$IGNITE_HOME`
- Kafka 2.2.0 Scala 2.11 in `$KAFKA_HOME`
- Azure Event Hubs Source Connector for Confluent Platform 1.0.3 in 
  `/opt/kafka/connect/confluentinc-kafka-connect-azure-event-hubs-1.0.3`
- Azure Event Hub with name `cdr`.

## How To Run
Set `KEY_NAME`, `KEY` and `NAMESPACE` in `kafka-connect-azure-event-hub.json` and 
`AZURE_EVENT_HUB_CONNECTION_STRING` on this page below to your Azure Event Hub settings.

Execute shell commands below.

```shell script
# Install GridGain Kafka Connector 
cd $IGNITE_HOME/integration/gridgain-kafka-connect
./copy-dependencies.sh
cp -r . /opt/kafka/connect/gridgain-kafka-connect-8.7.17

# Prepare the demo work directory
mkdir /tmp/azure-gridgain
ln -s $HOME/Dev/gridgain-kafka-connect-azure/ignite-server.xml /tmp/azure-gridgain/ignite-server.xml

# Use this repository as a current directory for the following commands 
cd $HOME/Dev/gridgain-kafka-connect-azure

# Build and add this JAR to GridGain Sink Connector's classpath to use JsonToIgniteConverter from this project  
gradle jar
ln -s $HOME/Dev/gridgain-kafka-connect-azure/build/libs/gridgain-kafka-connect-azure-1.0-SNAPSHOT.jar /opt/kafka/connect/gridgain-kafka-connect-8.7.17/gridgain-kafka-connect-azure-1.0-SNAPSHOT.jar

# Run single-node Zookeeper, Kafka, Kafka Connect and GridGain clusters. Both the source and sink side will use the 
# same Kafka Connect cluster.
$KAFKA_HOME/bin/zookeeper-server-start.sh zookeeper.properties
$KAFKA_HOME/bin/kafka-server-start.sh kafka-server.properties
$KAFKA_HOME/bin/connect-distributed.sh kafka-connect-worker.properties
$IGNITE_HOME/bin/ignite.sh ignite-server.xml

# Deploy Azure Event Hub source and GridGain sink connectors
curl -s -d "@kafka-connect-azure-event-hub.json" -H "Content-Type: application/json" -X POST http://localhost:8083/connectors | jq
curl -s -d "@kafka-connect-gridgain.json" -H "Content-Type: application/json" -X POST http://localhost:8083/connectors | jq

# Produce CDR with ID = 1
java -jar build/libs/gridgain-kafka-connect-azure-1.0-SNAPSHOT.jar -c 'AZURE_EVENT_HUB_CONNECTION_STRING' -i 1
```

### Monitoring & Management

Useful commands:

```shell script
# Connector status
curl -s http://localhost:8083/connectors/kafka-connect-azure-event-hub/status | jq
curl -s http://localhost:8083/connectors/kafka-connect-gridgain/status | jq

# Remove connector
curl -s -X DELETE http://localhost:8083/connectors/kafka-connect-azure-event-hub | jq
curl -s -X DELETE http://localhost:8083/connectors/kafka-connect-gridgain | jq

# View Kafka topics
$KAFKA_HOME/bin/kafka-topics.sh --list --zookeeper localhost:2181

# View data in a Kafka topic
$KAFKA_HOME/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic cdr --from-beginning
```