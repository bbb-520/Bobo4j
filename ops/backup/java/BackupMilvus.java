package agent.backup;

import com.google.gson.*;
import io.milvus.v2.client.*;
import io.milvus.v2.common.*;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.collection.response.*;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.index.request.*;
import io.milvus.v2.service.index.response.*;
import io.milvus.v2.service.partition.request.*;
import io.milvus.v2.service.utility.request.*;
import io.milvus.v2.service.vector.request.data.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Logical snapshot using installed SDK 2.6.4; never copies live embedded etcd/local files. */
public final class BackupMilvus {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static final int FORMAT = 1;
    static final String SDK = "2.6.4";
    static final int SAMPLE = 10;

    static JsonObject read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    static void write(Path path, Object value) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            JSON.toJson(value, writer);
        }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    static String collectionName(String name) {
        require(name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"), "invalid collection name"); return name;
    }
    static String checkedVerifyName(String name) {
        require(name.matches("backup_verify_[a-f0-9]{32}"), "refusing non-verification mutation"); return name;
    }
    static Set<String> generatedFields(CreateCollectionReq.CollectionSchema schema) {
        Set<String> generated = new HashSet<>();
        if (schema.getFunctionList() != null) for (var function : schema.getFunctionList()) {
            require(function.getFunctionType() == FunctionType.BM25, "unsupported function; external inference is forbidden");
            require(function.getOutputFieldNames().size() == 1, "unsupported BM25 output schema");
            for (String output : function.getOutputFieldNames()) {
                require(schema.getField(output).getDataType() == DataType.SparseFloatVector, "invalid BM25 vector schema");
                generated.add(output);
            }
        }
        return generated;
    }
    static List<String> outputFields(CreateCollectionReq.CollectionSchema schema) {
        Set<String> generated = generatedFields(schema);
        List<String> output = new ArrayList<>();
        require(!schema.isEnableDynamicField(), "dynamic schemas need an explicit lossless exporter before backup");
        for (var field : schema.getFieldSchemaList()) {
            require(!Boolean.TRUE.equals(field.getIsPartitionKey()), "partition-key schemas need explicit restore support");
            require(!Set.of(DataType.BinaryVector, DataType.Float16Vector, DataType.BFloat16Vector,
                    DataType.Int8Vector, DataType.None).contains(field.getDataType()), "unsupported lossless field encoding");
            if (!generated.contains(field.getName())) output.add(field.getName());
        }
        return output;
    }
    static void validateRow(JsonObject row, CreateCollectionReq.CollectionSchema schema) {
        for (String field : outputFields(schema)) require(row.has(field), "export omitted a stored field");
        for (var field : schema.getFieldSchemaList()) {
            JsonElement value = row.get(field.getName());
            if (value == null || value.isJsonNull()) {
                require(Boolean.TRUE.equals(field.getIsNullable()) || generatedFields(schema).contains(field.getName()), "missing non-null field");
            } else if (field.getDataType() == DataType.FloatVector) {
                require(value.isJsonArray() && value.getAsJsonArray().size() == field.getDimension(), "dense vector dimensions mismatch");
                for (var number : value.getAsJsonArray()) require(Float.isFinite(number.getAsFloat()), "nonfinite dense vector");
            } else if (field.getDataType() == DataType.SparseFloatVector) {
                require(value.isJsonObject(), "unsupported sparse vector encoding");
                for (var entry : value.getAsJsonObject().entrySet()) {
                    require(entry.getKey().matches("[0-9]+") && Float.isFinite(entry.getValue().getAsFloat()), "invalid sparse vector entry");
                }
            }
        }
    }
    static long count(MilvusClientV2 client, String collection, String partition) {
        var request = QueryReq.builder().collectionName(collection).outputFields(List.of("count(*)"))
                .consistencyLevel(ConsistencyLevel.STRONG);
        if (partition != null) request.partitionNames(List.of(partition));
        var rows = client.query(request.build()).getQueryResults();
        require(rows.size() == 1, "invalid count response");
        return ((Number) rows.getFirst().getEntity().get("count(*)")).longValue();
    }
    static List<IndexParam> indexes(JsonArray metadata) {
        List<IndexParam> result = new ArrayList<>();
        for (var element : metadata) {
            var desc = JSON.fromJson(element, DescribeIndexResp.IndexDesc.class);
            Map<String,Object> params = new LinkedHashMap<>();
            if (desc.getExtraParams() != null) params.putAll(desc.getExtraParams());
            result.add(IndexParam.builder().fieldName(desc.getFieldName()).indexName(desc.getIndexName())
                    .indexType(desc.getIndexType()).metricType(desc.getMetricType()).extraParams(params).build());
        }
        return result;
    }
    static void export(MilvusClientV2 client, JsonObject cfg, Path directory, int batch) throws Exception {
        var mcfg = cfg.getAsJsonObject("milvus");
        Set<String> names = new LinkedHashSet<>();
        for (var name : mcfg.getAsJsonArray("collections")) require(names.add(collectionName(name.getAsString())), "duplicate collection");
        require(!names.isEmpty(), "empty collection allowlist");
        Set<String> existing = new HashSet<>(client.listCollections().getCollectionNames());
        existing.removeIf(name -> name.startsWith("backup_verify_"));
        if (!mcfg.has("require_all_collections") || mcfg.get("require_all_collections").getAsBoolean())
            require(existing.equals(names), "collection allowlist omits or names unavailable collections");
        var snapshot = new JsonObject();
        snapshot.addProperty("format", FORMAT);
        snapshot.addProperty("sdkVersion", SDK);
        snapshot.addProperty("serverVersion", client.getServerVersion());
        snapshot.addProperty("database", client.currentUsedDatabase());
        snapshot.addProperty("started", Instant.now().toString());
        snapshot.addProperty("consistency", "STRONG iterator per collection/partition; not a cross-store atomic snapshot");
        snapshot.add("modelMetadata", cfg.get("model_metadata"));
        var collections = new JsonArray();
        int colIndex = 0;
        for (String name : names) {
            var described = client.describeCollection(DescribeCollectionReq.builder().collectionName(name).build());
            var schema = described.getCollectionSchema();
            require(schema != null, "SDK did not supply collection schema");
            var fields = outputFields(schema);
            var beforeSchema = JSON.toJsonTree(schema);
            client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
            long before = count(client, name, null);
            var info = new JsonObject();
            info.addProperty("name", name);
            info.add("describe", JSON.toJsonTree(described));
            info.add("derivedBm25Fields", JSON.toJsonTree(generatedFields(schema)));
            info.addProperty("derivedVectorPolicy", "BM25 internal vectors are not outputable; preserved text/analyzer/function rebuild them without model calls");
            var indexMetadata = new JsonArray();
            for (String index : client.listIndexes(ListIndexesReq.builder().collectionName(name).build())) {
                for (var desc : client.describeIndex(DescribeIndexReq.builder().collectionName(name).indexName(index).build()).getIndexDescriptions())
                    indexMetadata.add(JSON.toJsonTree(desc));
            }
            info.add("indexes", indexMetadata);
            var partitions = new JsonArray();
            long total = 0;
            int partIndex = 0;
            for (String partition : client.listPartitions(ListPartitionsReq.builder().collectionName(name).build())) {
                long expected = count(client, name, partition), rows = 0;
                String file = "rows-" + colIndex + "-" + partIndex++ + ".jsonl";
                var iterator = client.queryIterator(QueryIteratorReq.builder().collectionName(name).partitionNames(List.of(partition))
                        .outputFields(fields).consistencyLevel(ConsistencyLevel.STRONG).ignoreGrowing(false).batchSize((long) batch).build());
                try (var writer = Files.newBufferedWriter(directory.resolve(file), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                    while (true) {
                        var page = iterator.next();
                        if (page.isEmpty()) break;
                        require(page.size() <= batch, "iterator batch bound exceeded");
                        for (var record : page) {
                            var row = JSON.toJsonTree(record.getFieldValues()).getAsJsonObject();
                            validateRow(row, schema);
                            writer.write(JSON.toJson(row)); writer.newLine(); rows++;
                        }
                    }
                } finally { iterator.close(); }
                require(rows == expected && count(client, name, partition) == expected, "collection changed or snapshot row count incomplete; quiesce writers");
                var part = new JsonObject(); part.addProperty("name", partition); part.addProperty("file", file); part.addProperty("rows", rows);
                partitions.add(part); total += rows;
            }
            require(total == before && count(client, name, null) == before, "collection count changed; quiesce writers");
            require(beforeSchema.equals(JSON.toJsonTree(client.describeCollection(DescribeCollectionReq.builder().collectionName(name).build()).getCollectionSchema())), "schema changed during export");
            info.addProperty("rows", total); info.add("partitions", partitions); collections.add(info); colIndex++;
        }
        snapshot.add("collections", collections); snapshot.addProperty("ended", Instant.now().toString());
        write(directory.resolve("snapshot.json"), snapshot); // Completion marker written only after every collection succeeds.
    }
    static boolean sameValue(JsonElement left, JsonElement right) {
        if (left == null || right == null) return left == right;
        if (left.isJsonNull() || right.isJsonNull()) return left.equals(right);
        if (left.isJsonObject() && right.isJsonObject()) {
            var a = left.getAsJsonObject(); var b = right.getAsJsonObject();
            if (!a.keySet().equals(b.keySet())) return false;
            for (String key : a.keySet()) if (!sameValue(a.get(key), b.get(key))) return false;
            return true;
        }
        if (left.isJsonArray() && right.isJsonArray()) {
            if (left.getAsJsonArray().size() != right.getAsJsonArray().size()) return false;
            for (int i=0; i<left.getAsJsonArray().size(); i++) if (!sameValue(left.getAsJsonArray().get(i), right.getAsJsonArray().get(i))) return false;
            return true;
        }
        if (left.isJsonPrimitive() && right.isJsonPrimitive() && left.getAsJsonPrimitive().isNumber() && right.getAsJsonPrimitive().isNumber()) {
            BigDecimal a = left.getAsBigDecimal(), b = right.getAsBigDecimal();
            // Integers, particularly int64 primary keys, must compare exactly.
            if (a.stripTrailingZeros().scale() <= 0 && b.stripTrailingZeros().scale() <= 0) return a.compareTo(b) == 0;
            return a.subtract(b).abs().compareTo(a.abs().max(b.abs()).max(BigDecimal.ONE).multiply(new BigDecimal("0.000001"))) <= 0;
        }
        return left.equals(right);
    }
    static void verifySamples(MilvusClientV2 client, String name, CreateCollectionReq.CollectionSchema schema,
                              String primary, List<JsonObject> samples) {
        var fields = outputFields(schema);
        var pkType = schema.getField(primary).getDataType();
        for (var sample : samples) {
            Object id = pkType == DataType.Int64 ? sample.get(primary).getAsLong() : sample.get(primary).getAsString();
            var response = client.query(QueryReq.builder().collectionName(name).ids(List.of(id)).outputFields(fields)
                    .consistencyLevel(ConsistencyLevel.STRONG).build()).getQueryResults();
            require(response.size() == 1, "restored sample lookup failed");
            var found = JSON.toJsonTree(response.getFirst().getEntity()).getAsJsonObject();
            for (String field : fields) require(sameValue(sample.get(field), found.get(field)), "restored sample/vector differs");
        }
    }
    static void verify(MilvusClientV2 client, JsonObject cfg, Path directory, int batch) throws Exception {
        var snapshot = read(directory.resolve("snapshot.json"));
        require(snapshot.get("format").getAsInt() == FORMAT && snapshot.get("sdkVersion").getAsString().equals(SDK), "snapshot format/SDK mismatch");
        require(snapshot.get("modelMetadata").equals(cfg.get("model_metadata")), "model identity metadata mismatch");
        var mcfg = cfg.getAsJsonObject("milvus");
        require(snapshot.get("serverVersion").getAsString().equals(client.getServerVersion()) ||
                (mcfg.has("allow_server_version_mismatch") && mcfg.get("allow_server_version_mismatch").getAsBoolean()), "server version differs; explicitly approve compatibility verification in config");
        for (var value : snapshot.getAsJsonArray("collections")) {
            var info = value.getAsJsonObject();
            var schema = JSON.fromJson(info.getAsJsonObject("describe").get("collectionSchema"), CreateCollectionReq.CollectionSchema.class);
            outputFields(schema);
            String primary = info.getAsJsonObject("describe").get("primaryFieldName").getAsString();
            // Preserve exported IDs in the isolated copy. Original autoID remains in metadata.
            schema.getField(primary).setAutoID(false);
            String name = checkedVerifyName("backup_verify_" + UUID.randomUUID().toString().replace("-", ""));
            require(!client.hasCollection(HasCollectionReq.builder().collectionName(name).build()), "verification collision");
            boolean created = false;
            try {
                client.createCollection(CreateCollectionReq.builder().collectionName(name).collectionSchema(schema)
                        .description("Isolated backup verification").consistencyLevel(ConsistencyLevel.STRONG)
                        .indexParams(indexes(info.getAsJsonArray("indexes"))).build());
                created = true;
                var actualSchema = client.describeCollection(DescribeCollectionReq.builder().collectionName(name).build()).getCollectionSchema();
                require(schema.getFieldSchemaList().equals(actualSchema.getFieldSchemaList())
                        && Objects.equals(schema.getFunctionList(), actualSchema.getFunctionList())
                        && schema.isEnableDynamicField() == actualSchema.isEnableDynamicField(), "restored schema/functions differ");
                List<JsonObject> samples = new ArrayList<>();
                long total = 0;
                for (var partValue : info.getAsJsonArray("partitions")) {
                    var part = partValue.getAsJsonObject();
                    String partition = part.get("name").getAsString();
                    if (!partition.equals("_default")) client.createPartition(CreatePartitionReq.builder().collectionName(name).partitionName(partition).build());
                    String file = part.get("file").getAsString();
                    require(file.matches("rows-[0-9]+-[0-9]+\\.jsonl"), "unsafe row file");
                    List<JsonObject> page = new ArrayList<>();
                    long rows = 0;
                    try (var reader = Files.newBufferedReader(directory.resolve(file), StandardCharsets.UTF_8)) {
                        for (String line; (line = reader.readLine()) != null;) {
                            require(line.length() <= 32 * 1024 * 1024, "row exceeds safety bound");
                            var row = JsonParser.parseString(line).getAsJsonObject(); validateRow(row, schema);
                            if (samples.size() < SAMPLE) samples.add(row.deepCopy());
                            for (String generated : generatedFields(schema)) row.remove(generated);
                            page.add(row); rows++;
                            if (page.size() == batch) {
                                client.insert(InsertReq.builder().collectionName(name).partitionName(partition).data(page).build()); page = new ArrayList<>();
                            }
                        }
                    }
                    if (!page.isEmpty()) client.insert(InsertReq.builder().collectionName(name).partitionName(partition).data(page).build());
                    require(rows == part.get("rows").getAsLong(), "row file count mismatch"); total += rows;
                }
                client.flush(FlushReq.builder().collectionNames(List.of(name)).build());
                client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
                require(total == info.get("rows").getAsLong() && count(client, name, null) == total, "restored collection count mismatch");
                verifySamples(client, name, schema, primary, samples);
                // BM25 output vectors are internal; prove rebuilt function/index can execute on source text.
                if (schema.getFunctionList() != null && !samples.isEmpty()) for (var function : schema.getFunctionList()) {
                    String query = samples.getFirst().get(function.getInputFieldNames().getFirst()).getAsString();
                    if (!query.isBlank()) client.search(SearchReq.builder().collectionName(name)
                            .annsField(function.getOutputFieldNames().getFirst()).data(List.of(new EmbeddedText(query)))
                            .metricType(IndexParam.MetricType.BM25).topK(1).consistencyLevel(ConsistencyLevel.STRONG).build());
                }
            } finally {
                // Never derive deletion target from exported/original collection names.
                if (created) client.dropCollection(DropCollectionReq.builder().collectionName(checkedVerifyName(name)).build());
            }
        }
    }
    public static void main(String[] args) {
        MilvusClientV2 client = null;
        try {
            require(args.length == 3 && Set.of("export", "verify").contains(args[0]), "usage: export|verify CONFIG DIRECTORY");
            var cfg = read(Path.of(args[1])); var milvus = cfg.getAsJsonObject("milvus");
            int batch = milvus.has("batch_size") ? milvus.get("batch_size").getAsInt() : 128;
            require(batch >= 1 && batch <= 512, "batch must be 1..512");
            client = new MilvusClientV2(ConnectConfig.builder().uri(milvus.get("uri").getAsString())
                    .token(milvus.has("token") ? milvus.get("token").getAsString() : "")
                    .dbName(milvus.has("database") ? milvus.get("database").getAsString() : "default")
                    .connectTimeoutMs(10000L).rpcDeadlineMs(120000L).build());
            if (args[0].equals("export")) export(client, cfg, Path.of(args[2]), batch);
            else verify(client, cfg, Path.of(args[2]), batch);
            System.out.println("Milvus " + args[0] + " completed");
        } catch (Exception exception) {
            // SDK error bodies may contain row data/tokens. Do not print exception text or stack traces.
            System.err.println("Milvus backup failed (" + exception.getClass().getSimpleName() + ")");
            System.exit(1);
        } finally { if (client != null) client.close(); }
    }
}
