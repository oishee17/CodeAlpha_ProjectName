package chatbot.ml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multinomial Naive Bayes intent classifier with Laplace (add-one) smoothing.
 *
 * <p>Every FAQ in the knowledge base is a labelled training example: the
 * question is the feature vector (stemmed bag of words) and the intent is the
 * class. Training is a single pass over the corpus; prediction is a posterior
 * over intents converted to a confidence with softmax.</p>
 */
public final class NaiveBayes {

    public static final class Prediction {
        public final String intent;
        public final double confidence;
        public final Map<String, Double> distribution;

        Prediction(String intent, double confidence, Map<String, Double> distribution) {
            this.intent = intent;
            this.confidence = confidence;
            this.distribution = distribution;
        }
    }

    /** One labelled example: stemmed tokens plus the intent they belong to. */
    public static final class Example {
        public final List<String> tokens;
        public final String intent;

        public Example(List<String> tokens, String intent) {
            this.tokens = tokens;
            this.intent = intent;
        }
    }

    private final double alpha;
    private final List<String> classes = new ArrayList<>();
    private final Map<String, Long> classCounts = new LinkedHashMap<>();
    private final Map<String, Map<String, Long>> tokenCounts = new LinkedHashMap<>();
    private final Map<String, Long> tokenTotals = new LinkedHashMap<>();
    private long totalTokens;
    private int vocabularySize;

    private NaiveBayes(double alpha) {
        this.alpha = alpha;
    }

    public static NaiveBayes train(List<Example> examples) {
        NaiveBayes model = new NaiveBayes(1.0);
        java.util.Set<String> vocab = new java.util.HashSet<>();

        // max_df feature selection: terms appearing in most documents are topic-
        // neutral noise, so they are dropped before counting.
        Map<String, Integer> documentFrequency = new java.util.HashMap<>();
        for (Example example : examples) {
            for (String token : new java.util.HashSet<>(example.tokens)) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }
        int maxDf = (int) Math.floor(0.7 * examples.size());

        for (Example example : examples) {
            if (!model.classCounts.containsKey(example.intent)) {
                model.classes.add(example.intent);
                model.classCounts.put(example.intent, 0L);
                model.tokenCounts.put(example.intent, new LinkedHashMap<>());
                model.tokenTotals.put(example.intent, 0L);
            }
            model.classCounts.merge(example.intent, 1L, Long::sum);
            Map<String, Long> counts = model.tokenCounts.get(example.intent);
            for (String token : example.tokens) {
                if (documentFrequency.getOrDefault(token, 0) > maxDf) {
                    continue;
                }
                counts.merge(token, 1L, Long::sum);
                model.tokenTotals.merge(example.intent, 1L, Long::sum);
                model.totalTokens++;
                vocab.add(token);
            }
        }
        model.vocabularySize = vocab.size();
        return model;
    }

    /** Adds one live example to an already-trained model (online learning). */
    public void update(List<String> tokens, String intent) {
        if (!classCounts.containsKey(intent)) {
            classes.add(intent);
            classCounts.put(intent, 0L);
            tokenCounts.put(intent, new LinkedHashMap<>());
            tokenTotals.put(intent, 0L);
        }
        classCounts.merge(intent, 1L, Long::sum);
        Map<String, Long> counts = tokenCounts.get(intent);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String token : tokens) {
            counts.merge(token, 1L, Long::sum);
            tokenTotals.merge(intent, 1L, Long::sum);
            totalTokens++;
            if (seen.add(token)) {
                vocabularySize++;
            }
        }
    }

    public Prediction predict(List<String> tokens) {
        Map<String, Double> joint = new LinkedHashMap<>();
        double max = Double.NEGATIVE_INFINITY;
        if (classes.isEmpty()) {
            return new Prediction("unknown", 0, joint);
        }
        for (String intent : classes) {
            // smoothed class prior
            double score = Math.log((classCounts.get(intent) + alpha) / (totalCount() + alpha * classes.size()));
            long tokensInClass = tokenTotals.getOrDefault(intent, 0L);
            double denominator = tokensInClass + alpha * Math.max(vocabularySize, 1);
            Map<String, Long> counts = tokenCounts.get(intent);
            for (String token : tokens) {
                long count = counts.getOrDefault(token, 0L);
                score += Math.log((count + alpha) / denominator);
            }
            joint.put(intent, score);
            max = Math.max(max, score);
        }
        double sum = 0;
        Map<String, Double> probs = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : joint.entrySet()) {
            double p = Math.exp(e.getValue() - max);
            probs.put(e.getKey(), p);
            sum += p;
        }
        String best = null;
        double bestProbability = -1;
        for (Map.Entry<String, Double> e : probs.entrySet()) {
            double p = sum == 0 ? 0 : e.getValue() / sum;
            e.setValue(p);
            if (p > bestProbability) {
                bestProbability = p;
                best = e.getKey();
            }
        }
        return new Prediction(best, bestProbability, probs);
    }

    private double totalCount() {
        long count = 0;
        for (long c : classCounts.values()) {
            count += c;
        }
        return count;
    }

    public List<String> classes() {
        return java.util.Collections.unmodifiableList(classes);
    }

    public int vocabularySize() {
        return vocabularySize;
    }

    public int exampleCount() {
        return classCounts.values().stream().mapToInt(Long::intValue).sum();
    }

    // ---------------------------------------------------------- persistence

    public void save(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# naive-bayes-model v1\n");
        sb.append("#vocab\t").append(vocabularySize).append('\n');
        for (String intent : classes) {
            sb.append("@class\t").append(intent).append('\t').append(classCounts.get(intent)).append('\n');
        }
        List<String> tokens = new ArrayList<>(tokenCounts.isEmpty()
                ? java.util.List.of()
                : unionKeys());
        tokens.sort(String::compareTo);
        for (String token : tokens) {
            sb.append(token);
            for (String intent : classes) {
                sb.append('\t').append(tokenCounts.get(intent).getOrDefault(token, 0L));
            }
            sb.append('\n');
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private List<String> unionKeys() {
        List<String> tokens = new ArrayList<>();
        for (Map<String, Long> counts : tokenCounts.values()) {
            tokens.addAll(counts.keySet());
        }
        return new ArrayList<>(new java.util.LinkedHashSet<>(tokens));
    }

    public static NaiveBayes load(Path path) throws IOException {
        NaiveBayes model = new NaiveBayes(1.0);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> intents = new ArrayList<>();
        Map<String, long[]> rows = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts[0].equals("@class")) {
                intents.add(parts[1]);
                model.classCounts.put(parts[1], Long.parseLong(parts[2]));
                model.tokenCounts.put(parts[1], new LinkedHashMap<>());
                model.tokenTotals.put(parts[1], 0L);
            } else {
                long[] values = new long[intents.size()];
                for (int i = 0; i < intents.size() && i + 1 < parts.length; i++) {
                    values[i] = Long.parseLong(parts[i + 1]);
                }
                rows.put(parts[0], values);
            }
        }
        model.classes.addAll(intents);
        int col = 0;
        for (String intent : intents) {
            Map<String, Long> counts = model.tokenCounts.get(intent);
            long total = 0;
            for (Map.Entry<String, long[]> e : rows.entrySet()) {
                long value = e.getValue()[col];
                if (value > 0) {
                    counts.put(e.getKey(), value);
                    total += value;
                }
            }
            model.tokenTotals.put(intent, total);
            col++;
        }
        model.vocabularySize = rows.size();
        return model;
    }
}
