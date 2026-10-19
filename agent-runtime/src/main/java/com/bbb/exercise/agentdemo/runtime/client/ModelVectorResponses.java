package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.api.model.ModelContracts.Ranked;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static com.bbb.exercise.agentdemo.runtime.client.ModelProviderTransport.protocol;

/** Pure provider response validation. Dispatch, billing and unknown outcomes remain in ModelCallClient. */
final class ModelVectorResponses {
    private ModelVectorResponses() {}
    static List<List<Float>> embeddings(JsonNode result, int count, int dimensions) {
        JsonNode data = result.path("data");

        if (!data.isArray() || data.size() != count) {
            throw protocol("EMBEDDING_CARDINALITY");
        }

        List<List<Float>> vectors = new ArrayList<>(Collections.nCopies(count, null));

        for (JsonNode item : data) {
            if (!item.path("index").isIntegralNumber()) {
                throw protocol("EMBEDDING_INDEX");
            }

            int index = item.path("index").asInt(-1);
            JsonNode vector = item.path("embedding");

            if (index < 0
                    || index >= count
                    || vectors.get(index) != null
                    || !vector.isArray()
                    || vector.size() != dimensions) {
                throw protocol("EMBEDDING_DIMENSION");
            }

            List<Float> values = new ArrayList<>();

            for (JsonNode coordinate : vector) {
                float value = coordinate.floatValue();

                if (!coordinate.isNumber() || !Float.isFinite(value)) {
                    throw protocol("EMBEDDING_VALUE");
                }

                values.add(value);
            }

            vectors.set(index, List.copyOf(values));
        }
        return List.copyOf(vectors);
    }

    static List<Ranked> ranking(JsonNode result, int count) {
        JsonNode results = result.path("results");

        if (!results.isArray() || results.isEmpty()) {
            throw protocol("RERANK_EMPTY");
        }

        List<Ranked> ranked = new ArrayList<>();
        Set<Integer> indices = new HashSet<>();

        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            double score = item.path("relevance_score").asDouble(Double.NaN);

            if (!item.path("index").isIntegralNumber()
                    || index < 0
                    || index >= count
                    || !indices.add(index)
                    || !Double.isFinite(score)
                    || score < 0
                    || score > 1) {
                throw protocol("RERANK_INDEX_OR_SCORE");
            }

            ranked.add(new Ranked(index, score));
        }

        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return List.copyOf(ranked);
    }
}
