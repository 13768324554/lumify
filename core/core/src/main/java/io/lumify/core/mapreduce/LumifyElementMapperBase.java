package io.lumify.core.mapreduce;

import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.apache.accumulo.core.data.Mutation;
import org.apache.hadoop.io.Text;
import org.securegraph.GraphFactory;
import org.securegraph.accumulo.AccumuloGraph;
import org.securegraph.accumulo.mapreduce.ElementMapper;
import org.securegraph.accumulo.mapreduce.SecureGraphMRUtils;
import org.securegraph.id.IdGenerator;
import org.securegraph.util.MapUtils;

import java.io.IOException;
import java.util.Map;

public abstract class LumifyElementMapperBase<KEYIN, VALUEIN> extends ElementMapper<KEYIN, VALUEIN, Text, Mutation> {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(LumifyElementMapperBase.class);
    private AccumuloGraph graph;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        super.setup(context);
        Map configurationMap = SecureGraphMRUtils.toMap(context.getConfiguration());
        this.graph = (AccumuloGraph) new GraphFactory().createGraph(MapUtils.getAllWithPrefix(configurationMap, "graph"));
    }

    @Override
    protected void map(KEYIN key, VALUEIN line, Context context) {
        try {
            safeMap(key, line, context);
        } catch (Throwable ex) {
            LOGGER.error("failed mapping " + key, ex);
        }
    }

    protected abstract void safeMap(KEYIN key, VALUEIN line, Context context) throws Exception;

    @Override
    protected void saveDataMutation(Context context, Text dataTableName, Mutation m) throws IOException, InterruptedException {
        context.write(getKey(context, dataTableName, m), m);
    }

    @Override
    protected void saveEdgeMutation(Context context, Text edgesTableName, Mutation m) throws IOException, InterruptedException {
        context.write(getKey(context, edgesTableName, m), m);
    }

    @Override
    protected void saveVertexMutation(Context context, Text verticesTableName, Mutation m) throws IOException, InterruptedException {
        context.write(getKey(context, verticesTableName, m), m);
    }

    protected Text getKey(Context context, Text tableName, Mutation m) {
        return tableName;
    }

    @Override
    protected IdGenerator getIdGenerator() {
        return this.graph.getIdGenerator();
    }
}
