package gridgain.kafka.connect.azure;

import com.azure.core.util.CoreUtils;
import com.azure.core.util.logging.ClientLogger;
import com.azure.messaging.eventhubs.CheckpointStore;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventProcessorClient;
import com.azure.messaging.eventhubs.EventProcessorClientBuilder;
import com.azure.messaging.eventhubs.models.Checkpoint;
import com.azure.messaging.eventhubs.models.ErrorContext;
import com.azure.messaging.eventhubs.models.EventContext;
import com.azure.messaging.eventhubs.models.PartitionOwnership;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Read records from Azure Event Hub with name "cdr". The connection string is specified as the first command line
 * argument. Specify --from-beginning as the 2nd argument to read data already existing in the event hub.
 */
public class CdrReader {
    public static void main(String[] args) throws IOException, ParseException {
        Options options = new Options()
                .addOption("c", "connection", true, "mandatory connection string to Azure Event Hub Namespace")
                .addOption(
                        "o",
                        "from-beginning",
                        false,
                        "read data already existing in the event hub (read new data by default)"
                );
        CommandLine cmd = new DefaultParser().parse( options, args);


        if (!cmd.hasOption("c")) {
            System.out.println("Specify connection string to Azure Event Hub Namespace");
            System.exit(1);
        }

        String connStr = cmd.getOptionValue("c");
        boolean fromBeg = cmd.hasOption("o");

        Consumer<EventContext> processEvent = eventContext -> {
            System.out.print("Received event: ");
            System.out.println(eventContext.getEventData().getBodyAsString());
            eventContext.updateCheckpoint();
        };

        Consumer<ErrorContext> processError = errorContext ->
                System.out.println(errorContext.getThrowable().getMessage());

        EventProcessorClient eventProcessorClient = new EventProcessorClientBuilder()
                .connectionString(connStr, "cdr")
                .processEvent(processEvent)
                .processError(processError)
                .consumerGroup(EventHubClientBuilder.DEFAULT_CONSUMER_GROUP_NAME)
                .checkpointStore(new SampleCheckpointStore(fromBeg))
                .buildEventProcessorClient();

        System.out.println("Starting event processor");
        eventProcessorClient.start();

        System.out.println("Press enter to stop.");
        System.in.read();

        System.out.println("Stopping event processor");
        eventProcessorClient.stop();
        System.out.println("Event processor stopped.");

        System.out.println("Exiting process");
    }

    /**
     * A simple in-memory implementation of a {@link CheckpointStore}. This implementation keeps track of partition
     * ownership details including checkpointing information in-memory. Using this implementation will only facilitate
     * checkpointing and load balancing of Event Processors running within this process.
     */
    private static class SampleCheckpointStore implements CheckpointStore {
        private static final String OWNERSHIP = "ownership";
        private static final String SEPARATOR = "/";
        private static final String CHECKPOINT = "checkpoint";
        private final Map<String, PartitionOwnership> partitionOwnershipMap = new ConcurrentHashMap<>();
        private final Map<String, Checkpoint> checkpointsMap = new ConcurrentHashMap<>();
        private final ClientLogger logger = new ClientLogger(SampleCheckpointStore.class);
        private final boolean fromBeg;

        public SampleCheckpointStore(boolean fromBeg) {
            this.fromBeg = fromBeg;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Flux<PartitionOwnership> listOwnership(
                String fullyQualifiedNamespace,
                String eventHubName,
                String consumerGroup
        ) {
            logger.info("Listing partition ownership");

            String prefix = prefixBuilder(fullyQualifiedNamespace, eventHubName, consumerGroup, OWNERSHIP);
            return Flux.fromIterable(partitionOwnershipMap.keySet())
                    .filter(key -> key.startsWith(prefix))
                    .map(partitionOwnershipMap::get);
        }

        /**
         * Returns a {@link Flux} of partition ownership details for successfully claimed partitions. If a partition is
         * already claimed by an instance or if the ETag in the request doesn't match the previously stored ETag, then
         * ownership claim is denied.
         *
         * @param requestedPartitionOwnerships List of partition ownerships this instance is requesting to own.
         * @return Successfully claimed partition ownerships.
         */
        @Override
        public Flux<PartitionOwnership> claimOwnership(List<PartitionOwnership> requestedPartitionOwnerships) {
            if (CoreUtils.isNullOrEmpty(requestedPartitionOwnerships)) {
                return Flux.empty();
            }

            PartitionOwnership firstEntry = requestedPartitionOwnerships.get(0);
            String prefix = prefixBuilder(
                    firstEntry.getFullyQualifiedNamespace(),
                    firstEntry.getEventHubName(),
                    firstEntry.getConsumerGroup(),
                    OWNERSHIP
            );

            return Flux.fromIterable(requestedPartitionOwnerships)
                    .filter(partitionOwnership ->
                            !partitionOwnershipMap.containsKey(partitionOwnership.getPartitionId())
                                    || partitionOwnershipMap.get(partitionOwnership.getPartitionId()).getETag()
                                    .equals(partitionOwnership.getETag()))
                    .doOnNext(partitionOwnership -> logger.info(
                            "Ownership of partition {} claimed by {}",
                            partitionOwnership.getPartitionId(),
                            partitionOwnership.getOwnerId()
                    ))
                    .map(partitionOwnership -> {
                        partitionOwnership
                                .setETag(UUID.randomUUID().toString())
                                .setLastModifiedTime(System.currentTimeMillis());

                        String key = prefix + SEPARATOR + partitionOwnership.getPartitionId();

                        partitionOwnershipMap.put(key, partitionOwnership);

                        if (fromBeg) {
                            Checkpoint cp = new Checkpoint()
                                    .setConsumerGroup(firstEntry.getConsumerGroup())
                                    .setEventHubName(firstEntry.getEventHubName())
                                    .setFullyQualifiedNamespace(firstEntry.getFullyQualifiedNamespace())
                                    .setPartitionId(firstEntry.getPartitionId())
                                    .setOffset(0L);
                            String cpPrefix = prefixBuilder(
                                    cp.getFullyQualifiedNamespace(),
                                    cp.getEventHubName(),
                                    cp.getConsumerGroup(),
                                    CHECKPOINT
                            );

                            checkpointsMap.putIfAbsent(cpPrefix + SEPARATOR + cp.getPartitionId(), cp);
                        }

                        return partitionOwnership;
                    });
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Flux<Checkpoint> listCheckpoints(
                String fullyQualifiedNamespace,
                String eventHubName,
                String consumerGroup
        ) {
            String prefix = prefixBuilder(fullyQualifiedNamespace, eventHubName, consumerGroup, CHECKPOINT);
            return Flux.fromIterable(checkpointsMap.keySet())
                    .filter(key -> key.startsWith(prefix))
                    .map(checkpointsMap::get);
        }

        /**
         * Updates the in-memory storage with the provided checkpoint information.
         *
         * @param checkpoint The checkpoint containing the information to be stored in-memory.
         * @return A {@link Mono} that completes when the checkpoint is updated.
         */
        @Override
        public Mono<Void> updateCheckpoint(Checkpoint checkpoint) {
            if (checkpoint == null)
                return Mono.error(logger.logExceptionAsError(new NullPointerException("checkpoint cannot be null")));

            String prefix = prefixBuilder(
                    checkpoint.getFullyQualifiedNamespace(),
                    checkpoint.getEventHubName(),
                    checkpoint.getConsumerGroup(),
                    CHECKPOINT
            );
            checkpointsMap.put(prefix + SEPARATOR + checkpoint.getPartitionId(), checkpoint);

            logger.info(
                    "Updated checkpoint for partition {} with sequence number {}",
                    checkpoint.getPartitionId(),
                    checkpoint.getSequenceNumber()
            );

            return Mono.empty();
        }

        private static String prefixBuilder(
                String fullyQualifiedNamespace,
                String eventHubName,
                String consumerGroup,
                String type
        ) {
            return (fullyQualifiedNamespace +
                    SEPARATOR +
                    eventHubName +
                    SEPARATOR +
                    consumerGroup +
                    SEPARATOR +
                    type
            ).toLowerCase(Locale.ROOT);
        }
    }
}
