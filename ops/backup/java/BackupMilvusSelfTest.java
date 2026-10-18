package agent.backup;

import com.google.gson.*;
import io.milvus.v2.common.*;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.service.collection.request.*;
import java.util.*;

/** Offline tests. Never opens a Milvus connection or invokes an embedding model. */
public final class BackupMilvusSelfTest {
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        var schema = CreateCollectionReq.CollectionSchema.builder().enableDynamicField(false).build();
        schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder().fieldName("text").dataType(DataType.VarChar).maxLength(512).enableAnalyzer(true).build());
        schema.addField(AddFieldReq.builder().fieldName("dense").dataType(DataType.FloatVector).dimension(2).build());
        schema.addField(AddFieldReq.builder().fieldName("sparse").dataType(DataType.SparseFloatVector).build());
        schema.addFunction(CreateCollectionReq.Function.builder().name("bm25").functionType(FunctionType.BM25)
                .inputFieldNames(List.of("text")).outputFieldNames(List.of("sparse")).build());
        require(BackupMilvus.generatedFields(schema).equals(Set.of("sparse")));
        require(BackupMilvus.outputFields(schema).equals(List.of("id", "text", "dense")));
        var row = JsonParser.parseString("{\"id\":9223372036854775806,\"text\":\"fixture\",\"dense\":[0.1,0.2]}").getAsJsonObject();
        BackupMilvus.validateRow(row, schema);
        require(row.get("id").getAsLong() == 9223372036854775806L);
        var bad = row.deepCopy(); bad.remove("dense");
        rejects(() -> BackupMilvus.validateRow(bad, schema));
        var wrong = row.deepCopy(); wrong.add("dense", JsonParser.parseString("[0.1]"));
        rejects(() -> BackupMilvus.validateRow(wrong, schema));
        require(BackupMilvus.sameValue(JsonParser.parseString("[0.1,0.2]"), JsonParser.parseString("[0.100000001,0.200000002]")));
        require(!BackupMilvus.sameValue(JsonParser.parseString("9223372036854775806"), JsonParser.parseString("9223372036854775805")));
        rejects(() -> BackupMilvus.checkedVerifyName("agent_rag_v2"));
        require(BackupMilvus.checkedVerifyName("backup_verify_" + "a".repeat(32)).startsWith("backup_verify_"));
        schema.getField("dense").setDataType(DataType.BinaryVector);
        rejects(() -> BackupMilvus.outputFields(schema));
        System.out.println("BackupMilvusSelfTest: 11 assertions passed");
    }
}
