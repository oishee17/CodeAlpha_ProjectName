package chatbot.brain;

import chatbot.kb.KnowledgeBase;
import chatbot.ml.NaiveBayes;
import chatbot.nlp.TextProcessor;
import chatbot.nlp.TfIdfIndex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reasoning pipeline of the chatbot. A single utterance is handled in this
 * order:
 *
 * <ol>
 *   <li><b>rule engine</b> - exact conversational patterns (greetings, thanks,
 *       "what can you do", the clock);</li>
 *   <li><b>exact match</b> - the question already in the corpus, verbatim;</li>
 *   <li><b>TF-IDF retrieval</b> - cosine similarity against every FAQ question,
 *       so rephrased questions still land;</li>
 *   <li><b>Naive Bayes classification</b> - a probabilistic intent decision used
 *       when the wording is far from anything seen before;</li>
 *   <li><b>fallback</b> - an honest "I don't know" plus suggestions, which the UI
 *       turns into a teach-the-bot flow.</li>
 * </ol>
 */
public final class ChatBot {

    /** One answer ready to be rendered by the UI. */
    public static final class Reply {
        public final String text;
        public final String intent;
        public final double confidence;
        public final String method;
        public final List<String> suggestions;

        public Reply(String text, String intent, double confidence, String method, List<String> suggestions) {
            this.text = text;
            this.intent = intent;
            this.confidence = confidence;
            this.method = method;
            this.suggestions = suggestions;
        }
    }

    /** Cosine at or above which the closest FAQ is trusted outright. */
    private static final double HIGH = 0.70;
    /** Cosine at or above which a match is still considered plausible. */
    private static final double MID = 0.40;
    /** Posterior probability the classifier must reach before it is believed. */
    private static final double NB_MIN = 0.55;

    private final KnowledgeBase kb;
    private final Path baseDir;
    private final TextProcessor nlp = new TextProcessor();
    private final RuleEngine rules = new RuleEngine();

    private List<KnowledgeBase.Entry> entries = List.of();
    private TfIdfIndex index = new TfIdfIndex(List.of());
    private NaiveBayes model = NaiveBayes.train(List.of());
    private String modelOrigin = "untrained";

    public ChatBot(Path baseDir) {
        this.baseDir = baseDir;
        this.kb = new KnowledgeBase(baseDir);
    }

    // ------------------------------------------------------------- lifecycle

    /** Reloads the corpus, rebuilds the TF-IDF index and refreshes the model. */
    public synchronized void rebuild() throws IOException {
        kb.reload();
        entries = kb.entries();

        List<List<String>> documents = new ArrayList<>(entries.size());
        for (KnowledgeBase.Entry entry : entries) {
            documents.add(nlp.stemAll(entry.question));
        }
        index = new TfIdfIndex(documents);

        Path modelFile = kb.modelFile();
        Path signatureFile = baseDir.resolve("model").resolve("corpus.sig");
        String signature = corpusSignature();
        boolean loaded = false;
        if (Files.exists(modelFile) && Files.exists(signatureFile)) {
            String stored = Files.readString(signatureFile).trim();
            if (stored.equals(signature)) {
                try {
                    model = NaiveBayes.load(modelFile);
                    loaded = true;
                } catch (IOException | RuntimeException ignored) {
                    loaded = false;
                }
            }
        }
        if (!loaded) {
            List<NaiveBayes.Example> examples = new ArrayList<>(entries.size());
            for (KnowledgeBase.Entry entry : entries) {
                examples.add(new NaiveBayes.Example(nlp.features(entry.question), entry.intent));
            }
            model = NaiveBayes.train(examples);
            model.save(modelFile);
            Files.createDirectories(signatureFile.getParent());
            Files.writeString(signatureFile, signature);
        }
        modelOrigin = loaded ? "loaded from model/naive-bayes.tsv" : "trained from data/*.csv";
    }

    private String corpusSignature() {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeBase.Entry entry : entries) {
            sb.append(entry.intent).append('|').append(entry.question).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(sb.toString().hashCode());
        }
    }

    // ------------------------------------------------------------- reasoning

    public synchronized Reply respond(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return new Reply("I didn't catch any text - type a question and press Enter.",
                    "empty", 0, "rule", sampleQuestions(3));
        }

        RuleEngine.Result rule = rules.match(text);
        if (rule != null) {
            return new Reply(rule.reply, rule.intent, 1.0, "rule", sampleQuestions(3));
        }
        if (entries.isEmpty()) {
            return new Reply("My knowledge base is empty. Teach me something with the \"Teach\" tab!",
                    "empty", 0, "fallback", List.of());
        }

        String normalized = nlp.normalize(text);
        for (KnowledgeBase.Entry entry : entries) {
            if (nlp.normalize(entry.question).equals(normalized)) {
                return new Reply(entry.answer, entry.intent, 1.0, "exact",
                        alternatives(entries.indexOf(entry)));
            }
        }

        List<String> query = nlp.stems(text);
        if (query.isEmpty()) {
            return new Reply("I only understood filler words there - could you rephrase?",
                    "unclear", 0, "fallback", sampleQuestions(3));
        }

        List<TfIdfIndex.Hit> hits = index.rank(query);
        double topScore = hits.isEmpty() ? 0 : hits.get(0).score;
        int topIndex = hits.isEmpty() ? -1 : hits.get(0).docIndex;
        String topIntent = topIndex < 0 ? "" : entries.get(topIndex).intent;

        NaiveBayes.Prediction prediction = model.predict(nlp.features(text));
        List<Integer> eligible = eligibleFor(prediction.intent);
        int bestInIntent = -1;
        double bestIntentScore = 0;
        for (int idx : eligible) {
            double score = index.similarity(query, idx);
            if (score > bestIntentScore) {
                bestIntentScore = score;
                bestInIntent = idx;
            }
        }

        if (topScore >= HIGH) {
            return answer(topIndex, topIntent, topScore, "retrieval");
        }
        if (bestInIntent >= MID && prediction.confidence >= NB_MIN) {
            double confidence = 0.45 * prediction.confidence + 0.55 * bestIntentScore;
            return answer(bestInIntent, prediction.intent, Math.min(confidence, 0.99), "classification");
        }
        if (topScore >= MID) {
            return answer(topIndex, topIntent, topScore, "retrieval");
        }

        // Nothing is close: answer with the classifier's best intent when it is
        // genuinely sure, otherwise admit the gap and offer suggestions.
        if (bestInIntent >= 0 && prediction.confidence >= 0.80 && bestIntentScore >= 0.20) {
            double confidence = Math.min(0.45 * prediction.confidence + 0.55 * bestIntentScore, 0.79);
            return answer(bestInIntent, prediction.intent, confidence, "classification");
        }

        return new Reply("I don't have a confident answer for that yet. "
                        + "You can teach me using the panel below - or try one of these:",
                "unknown", Math.max(topScore, 0), "fallback", suggestions(hits, 3));
    }

    /**
     * Entries that would answer a question whose intent the classifier has
     * already committed to. Several FAQs share one canonical answer, so the
     * retrieved entry must not silently answer a *different* intent.
     */
    private List<Integer> eligibleFor(String intent) {
        List<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).intent.equals(intent)) {
                eligible.add(i);
            }
        }
        return eligible;
    }

    private Reply answer(int entryIndex, String intent, double confidence, String method) {
        KnowledgeBase.Entry entry = entries.get(entryIndex);
        return new Reply(entry.answer, intent, round(confidence), method, alternatives(entryIndex));
    }

    private List<String> alternatives(int keepIndex) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < entries.size() && out.size() < 3; i++) {
            if (i == keepIndex) {
                continue;
            }
            out.add(entries.get(i).question);
        }
        if (out.isEmpty()) {
            out.addAll(sampleQuestions(3));
        }
        return out;
    }

    private List<String> suggestions(List<TfIdfIndex.Hit> hits, int limit) {
        List<String> out = new ArrayList<>();
        for (TfIdfIndex.Hit hit : hits) {
            if (out.size() >= limit) {
                break;
            }
            if (hit.score < 0.12) {
                break;
            }
            String question = entries.get(hit.docIndex).question;
            if (!out.contains(question)) {
                out.add(question);
            }
        }
        if (out.isEmpty()) {
            out.addAll(sampleQuestions(limit));
        }
        return out;
    }

    /** Deterministic spread of corpus questions, used for the starter chips. */
    public synchronized List<String> sampleQuestions(int limit) {
        List<String> questions = new ArrayList<>();
        for (KnowledgeBase.Entry entry : entries) {
            questions.add(entry.question);
        }
        if (questions.size() <= limit) {
            return questions;
        }
        List<String> picked = new ArrayList<>();
        int stride = Math.max(1, questions.size() / limit);
        for (int i = 0; i < questions.size() && picked.size() < limit; i += stride) {
            picked.add(questions.get(i));
        }
        return picked;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ------------------------------------------------------------ training

    /** Persists a new Q&A pair and hot-reloads everything that depends on it. */
    public synchronized Reply teach(String question, String answer, String intent) throws IOException {
        String q = question == null ? "" : question.trim();
        String a = answer == null ? "" : answer.trim();
        String i = intent == null || intent.trim().isEmpty() ? "learned" : intent.trim().toLowerCase();
        if (q.isEmpty() || a.isEmpty()) {
            return new Reply("I need both a question and an answer to learn something new.",
                    "invalid", 0, "fallback", sampleQuestions(3));
        }
        kb.teach(i, q, a);
        rebuild();
        return new Reply("Learned it! Ask me \"" + q + "\" again and I'll answer.",
                i, 1.0, "learning", sampleQuestions(3));
    }

    // ------------------------------------------------------------- reporting

    public synchronized Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("faqs", entries.size());
        stats.put("intents", kb.intents().size());
        stats.put("vocabulary", index.vocabularySize());
        stats.put("rules", rules.ruleCount());
        stats.put("classifierExamples", model.exampleCount());
        stats.put("classifierClasses", model.classes().size());
        stats.put("model", modelOrigin);
        return stats;
    }

    public synchronized List<String> intents() {
        return kb.intents();
    }

    public synchronized int faqCount() {
        return entries.size();
    }

    public synchronized NaiveBayes classifier() {
        return model;
    }

    public TextProcessor nlp() {
        return nlp;
    }

    public synchronized List<KnowledgeBase.Entry> corpus() {
        return List.copyOf(entries);
    }
}
