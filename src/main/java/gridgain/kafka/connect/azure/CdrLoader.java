package gridgain.kafka.connect.azure;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Load one record into Azure Event Hub with name "cdr".
 */
public final class CdrLoader {
    public static void main(String[] args) throws ParseException {
        Options options = new Options()
                .addOption("c", "connection", true, "mandatory connection string to Azure Event Hub Namespace")
                .addOption("i", "id", true, "an ID embedded in various CDR fields (default 1)");
        CommandLine cmd = new DefaultParser().parse( options, args);

        if (!cmd.hasOption("c")) {
            System.out.println("Specify connection string to Azure Event Hub Namespace");
            System.exit(1);
        }

        String connStr = cmd.getOptionValue("c");
        int id = cmd.hasOption("i") ? Integer.parseInt(cmd.getOptionValue("i")) : 1;

        try (EventHubProducerClient producer = new EventHubClientBuilder()
                .connectionString(connStr, "cdr")
                .buildProducerClient()
        ) {
            loadCdr(producer, Integer.toString(id), createCdr(id));
        }

        System.out.println("CDR #" + id + " loaded OK.");
    }

    private static void loadCdr(EventHubProducerClient producer, String key, String cdr) {
        EventDataBatch batch = producer.createBatch(new CreateBatchOptions().setPartitionKey(key));
        batch.tryAdd(new EventData(cdr.getBytes(StandardCharsets.UTF_8)));
        producer.send(batch);
    }

    private static String createCdr(int id) {
        Map<String, Object> cdr = new HashMap<>();
        cdr.put("SystemIdentity", UUID.randomUUID());
        cdr.put("FileNum", (long) id);
        cdr.put("SwitchNum", (short) (id % 100));
        cdr.put("CallingNum", BigDecimal.valueOf(id));
        cdr.put("CallingIMSI", "CallingIMSI" + id);
        cdr.put("CalledIMSI", Integer.toString(id)); // key field
        cdr.put("CalledNum", BigDecimal.valueOf(id));
        cdr.put("DateS", new Date(System.currentTimeMillis()));
        cdr.put("TimeS", new Time(System.currentTimeMillis()));
        cdr.put("CallPeriod", "CallPeriod" + id);
        cdr.put("CallingCellID", (double) id);
        cdr.put("CalledCellID", (float) id);
        cdr.put("ServiceType", (char) id % 30);
        cdr.put("Transfer", new int[]{id, id + 1});
        cdr.put("IncomingTrunk", new byte[] { 1, 2, 3 });
        cdr.put("OutgoingTrunk", new byte[] { 4, 5, 6 });
        cdr.put("MSRN", Collections.singletonMap("id", id));
        cdr.put("CalledNum2", new Person(id, "Person " + id));
        cdr.put("FCIFlag", id % 2 != 0);
        cdr.put("callrecTime", new Timestamp(System.currentTimeMillis()));
        return gson.toJson(cdr);
    }

    private static final class Person {
        private final int id;
        private final String name;

        public Person(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int id() {
            return id;
        }

        public String name() {
            return name;
        }
    }

    private static final Gson gson = new GsonBuilder().create();
}
