package gridgain.kafka.connect.azure;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteBinary;
import org.apache.ignite.Ignition;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.binary.BinaryObjectBuilder;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enhances {@link JsonConverter} to convert schemaless JSONs to Ignite Binary objects.
 * NB! The {@link JsonToIgniteConverter} must be created in a JVM having a running Ignite instance: it will use the
 * first running Ignite instance to access Ignite Binary Object API.
 */
public final class JsonToIgniteConverter extends JsonConverter {
    @Override
    public SchemaAndValue toConnectData(String topic, byte[] value) {
        SchemaAndValue rec = super.toConnectData(topic, value);

        // JsonConverter processes schemaless JSONs and Map<Object, Object> and sets schema to null
        if (rec.schema() != null)
            return rec;

        Object val = rec.value();
        if (!(val instanceof Map<?, ?>))
            return rec;

        Map<?, ?> map = (Map<?, ?>) val;
        Object obj = toIgniteBinary(topic, map);

        return new SchemaAndValue(null, obj);
    }

    private Object toIgniteBinary(String typeName, Object obj) {
        if (obj == null || obj instanceof String || obj instanceof Number || obj instanceof Boolean)
            return obj;
        else if (obj instanceof Map<?, ?>)
            return toIgniteBinary(typeName, (Map<?, ?>) obj);
        else if (obj instanceof List<?>)
            return toIgniteBinary(typeName, (List<?>) obj);

        throw new DataException("Failed to convert [" + obj + "]. Unsupported data type: " + obj.getClass().getName());
    }

    private BinaryObject toIgniteBinary(String typeName, Map<?, ?> map) {
        BinaryObjectBuilder res = igniteBinary().builder(typeName);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = entry.getKey().toString();
            res.setField(name, toIgniteBinary(name, entry.getValue()));
        }
        return res.build();
    }

    private List<?> toIgniteBinary(String typeName, List<?> lst) {
        List<Object> res = new ArrayList<>();
        for (Object o : lst)
            res.add(toIgniteBinary(typeName, o));
        return res;
    }

    private static IgniteBinary igniteBinary() {
        if (igniteBin == null) {
            synchronized (igniteBinLock) {
                if (igniteBin == null) {
                    List<Ignite> allGrids = Ignition.allGrids();
                    if (allGrids.size() == 0)
                        throw new ConnectException(
                                JsonToIgniteConverter.class.getSimpleName() + " failed: no running Ignite in this JVM"
                        );
                    igniteBin = allGrids.get(0).binary();
                }
            }
        }

        return igniteBin;
    }

    private static IgniteBinary igniteBin;
    private static final Object igniteBinLock = new Object();
}
