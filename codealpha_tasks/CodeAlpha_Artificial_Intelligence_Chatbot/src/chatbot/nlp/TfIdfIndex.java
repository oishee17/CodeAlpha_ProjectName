package chatbot.nlp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TF-IDF vector space over the knowledge base plus cosine similarity search.
 *
 * <p>Each FAQ question is one document. A user utterance is projected into the
 * same space and compared with every document; the cosine of the two vectors is
 * the "relevance" score used by the brain to find the closest question.</p>
 */
public final class TfIdfIndex {

    public static final class Hit {
        public final int docIndex;
        public final double score;

        Hit(int docIndex, double score) {
            this.docIndex = docIndex;
            this.score = score;
        }
    }

    private final List<String> vocabulary = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final double[][] vectors;   // one normalised vector per document
    private final int docCount;

    public TfIdfIndex(List<List<String>> documents) {
        this.docCount = documents.size();
        this.vectors = new double[docCount][];

        for (List<String> doc : documents) {
            for (String token : new java.util.HashSet<>(doc)) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }
        for (String token : docFreq.keySet()) {
            vocabulary.add(token);
        }
        vocabulary.sort(String::compareTo);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < vocabulary.size(); i++) {
            index.put(vocabulary.get(i), i);
        }

        for (int d = 0; d < docCount; d++) {
            Map<String, Integer> tf = new HashMap<>();
            for (String token : documents.get(d)) {
                tf.merge(token, 1, Integer::sum);
            }
            double[] vector = new double[vocabulary.size()];
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                int idx = index.get(e.getKey());
                vector[idx] = termFrequency(e.getValue()) * idf(e.getKey());
            }
            vectors[d] = normalize(vector);
        }
    }

    private double termFrequency(int count) {
        return 1.0 + Math.log(count);
    }

    private double idf(String token) {
        int df = docFreq.getOrDefault(token, 0);
        return Math.log((1.0 + docCount) / (1.0 + df)) + 1.0;
    }

    private static double[] normalize(double[] v) {
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
        return v;
    }

    /** Cosine similarity between a query and one document. */
    public double similarity(Collection<String> queryStems, int docIndex) {
        double[] vector = vectors[docIndex];
        if (vector.length == 0) {
            return 0;
        }
        Map<String, Integer> tf = new HashMap<>();
        for (String token : queryStems) {
            tf.merge(token, 1, Integer::sum);
        }
        double dot = 0;
        double norm = 0;
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            Integer idx = indexOf(e.getKey());
            if (idx == null) {
                continue;
            }
            double weight = termFrequency(e.getValue()) * idf(e.getKey());
            dot += weight * vector[idx];
            norm += weight * weight;
        }
        if (norm == 0) {
            return 0;
        }
        return dot / Math.sqrt(norm);
    }

    private Integer indexOf(String token) {
        int i = java.util.Arrays.binarySearch(vocabulary.toArray(), token);
        return i >= 0 ? i : null;
    }

    /** All documents scored against the query, best first. */
    public List<Hit> rank(Collection<String> queryStems) {
        List<Hit> hits = new ArrayList<>(docCount);
        for (int i = 0; i < docCount; i++) {
            hits.add(new Hit(i, similarity(queryStems, i)));
        }
        hits.sort((a, b) -> Double.compare(b.score, a.score));
        return hits;
    }

    public int vocabularySize() {
        return vocabulary.size();
    }

    public int documentCount() {
        return docCount;
    }
}
