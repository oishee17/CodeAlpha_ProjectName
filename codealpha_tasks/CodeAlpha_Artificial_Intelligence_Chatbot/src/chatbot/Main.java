package chatbot;

import chatbot.brain.ChatBot;
import chatbot.kb.KnowledgeBase;
import chatbot.ml.NaiveBayes;
import chatbot.nlp.PorterStemmer;
import chatbot.nlp.TextProcessor;
import chatbot.server.ChatServer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Entry point of the chatbot.
 *
 * <pre>
 *   java -cp out chatbot.Main                 # start the web interface
 *   java -cp out chatbot.Main --port 9090     # start on another port
 *   java -cp out chatbot.Main --train         # train + cross-validate the model
 *   java -cp out chatbot.Main --ask "..."     # one-shot question from the CLI
 *   java -cp out chatbot.Main --selftest      # run the NLP unit checks
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path baseDir = Paths.get("").toAbsolutePath();
        String mode = "serve";
        String question = null;
        int port = 8080;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--train":
                    mode = "train";
                    break;
                case "--selftest":
                    mode = "selftest";
                    break;
                case "--ask":
                    mode = "ask";
                    question = i + 1 < args.length ? args[++i] : "";
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--help":
                case "-h":
                    usage();
                    return;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    usage();
                    System.exit(2);
            }
        }

        switch (mode) {
            case "selftest":
                System.exit(selfTest() ? 0 : 1);
                break;
            case "train":
                train(baseDir);
                break;
            case "ask":
                ask(baseDir, question);
                break;
            default:
                serve(baseDir, port);
        }
    }

    private static void usage() {
        System.out.println("Usage: java -cp out chatbot.Main [--port N] [--train] [--ask \"q\"] [--selftest]");
    }

    // ----------------------------------------------------------------- modes

    private static void serve(Path baseDir, int port) throws Exception {
        ChatBot bot = new ChatBot(baseDir);
        bot.rebuild();
        ChatServer server = new ChatServer(port, baseDir.resolve("web"), bot);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.println("\nShutting down Fenix. Goodbye!")));

        Map<String, Object> stats = bot.stats();
        System.out.println("======================================================");
        System.out.println("  Fenix - Java NLP chatbot");
        System.out.println("======================================================");
        System.out.println("  Interface   : http://localhost:" + server.port());
        System.out.println("  FAQ entries : " + stats.get("faqs") + " across " + stats.get("intents") + " intents");
        System.out.println("  Vocabulary  : " + stats.get("vocabulary") + " stemmed terms");
        System.out.println("  Classifier  : Naive Bayes, " + stats.get("classifierExamples")
                + " examples / " + stats.get("classifierClasses") + " classes");
        System.out.println("  Model       : " + stats.get("model"));
        System.out.println("  Rule engine : " + stats.get("rules") + " patterns");
        System.out.println("======================================================");
        System.out.println("  Press Ctrl+C to stop.");
    }

    private static void ask(Path baseDir, String question) throws Exception {
        ChatBot bot = new ChatBot(baseDir);
        bot.rebuild();
        ChatBot.Reply reply = bot.respond(question);
        System.out.println("Q: " + question);
        System.out.println("A: " + reply.text.replace("\n", "\n   "));
        System.out.println(String.format(Locale.ROOT, "[intent=%s method=%s confidence=%.2f]",
                reply.intent, reply.method, reply.confidence));
        if (!reply.suggestions.isEmpty()) {
            System.out.println("related: " + String.join(" | ", reply.suggestions));
        }
    }

    /** Trains the model, cross-validates it and reports the measured accuracy. */
    private static void train(Path baseDir) throws Exception {
        ChatBot bot = new ChatBot(baseDir);
        bot.rebuild();
        TextProcessor nlp = bot.nlp();
        List<KnowledgeBase.Entry> corpus = bot.corpus();

        if (corpus.isEmpty()) {
            System.out.println("Nothing to train on: data/faq.csv is empty.");
            return;
        }

        Map<String, List<KnowledgeBase.Entry>> byIntent = new LinkedHashMap<>();
        for (KnowledgeBase.Entry entry : corpus) {
            byIntent.computeIfAbsent(entry.intent, k -> new ArrayList<>()).add(entry);
        }

        int minGroup = byIntent.values().stream().mapToInt(List::size).min().orElse(1);
        int foldCount = Math.max(2, Math.min(5, minGroup));
        List<List<NaiveBayes.Example>> folds = new ArrayList<>();
        for (int f = 0; f < foldCount; f++) {
            folds.add(new ArrayList<>());
        }
        List<List<String>> foldIntents = new ArrayList<>();
        for (int f = 0; f < foldCount; f++) {
            foldIntents.add(new ArrayList<>());
        }
        for (List<KnowledgeBase.Entry> group : byIntent.values()) {
            int slot = 0;
            for (KnowledgeBase.Entry entry : group) {
                int fold = slot++ % foldCount;
                folds.get(fold).add(new NaiveBayes.Example(nlp.features(entry.question), entry.intent));
                foldIntents.get(fold).add(entry.intent);
            }
        }

        int correct = 0;
        int total = 0;
        List<String> misses = new ArrayList<>();
        for (int fold = 0; fold < foldCount; fold++) {
            List<NaiveBayes.Example> training = new ArrayList<>();
            for (int other = 0; other < foldCount; other++) {
                if (other != fold) {
                    training.addAll(folds.get(other));
                }
            }
            NaiveBayes model = NaiveBayes.train(training);
            List<NaiveBayes.Example> test = folds.get(fold);
            for (int i = 0; i < test.size(); i++) {
                NaiveBayes.Prediction prediction = model.predict(test.get(i).tokens);
                total++;
                if (prediction.intent.equals(foldIntents.get(fold).get(i))) {
                    correct++;
                } else if (misses.size() < 5) {
                    misses.add("expected " + expectedIntent(foldIntents, fold, i)
                            + " but predicted " + prediction.intent);
                }
            }
        }

        double accuracy = total == 0 ? 0 : (100.0 * correct / total);

        // Retrieval quality: leave each question out of its own ranking and see
        // whether its nearest neighbours still belong to the same intent.
        List<List<String>> documents = new ArrayList<>();
        for (KnowledgeBase.Entry entry : corpus) {
            documents.add(nlp.stemAll(entry.question));
        }
        chatbot.nlp.TfIdfIndex index = new chatbot.nlp.TfIdfIndex(documents);
        int top1 = 0;
        int top3 = 0;
        int pooled = 0;
        for (int i = 0; i < corpus.size(); i++) {
            List<chatbot.nlp.TfIdfIndex.Hit> hits = index.rank(nlp.stemAll(corpus.get(i).question));
            int rank = 0;
            boolean first = false;
            boolean inThree = false;
            for (chatbot.nlp.TfIdfIndex.Hit hit : hits) {
                if (hit.docIndex == i) {
                    continue;
                }
                rank++;
                boolean sameIntent = corpus.get(hit.docIndex).intent.equals(corpus.get(i).intent);
                if (rank == 1) {
                    first = sameIntent;
                }
                if (rank <= 3 && sameIntent) {
                    inThree = true;
                    break;
                }
                if (rank >= 3) {
                    break;
                }
            }
            if (first) {
                top1++;
            }
            if (inThree) {
                top3++;
            }

            // intent-pooled score: best question per intent, then argmax
            Map<String, Double> best = new LinkedHashMap<>();
            List<String> queryStems = nlp.stemAll(corpus.get(i).question);
            for (int j = 0; j < corpus.size(); j++) {
                if (j == i) {
                    continue;
                }
                String intent = corpus.get(j).intent;
                double score = index.similarity(queryStems, j);
                best.merge(intent, score, Math::max);
            }
            String guess = null;
            double bestScore = -1;
            for (Map.Entry<String, Double> e : best.entrySet()) {
                if (e.getValue() > bestScore) {
                    bestScore = e.getValue();
                    guess = e.getKey();
                }
            }
            if (corpus.get(i).intent.equals(guess)) {
                pooled++;
            }
        }
        double top1Rate = corpus.isEmpty() ? 0 : (100.0 * top1 / corpus.size());
        double top3Rate = corpus.isEmpty() ? 0 : (100.0 * top3 / corpus.size());
        double pooledRate = corpus.isEmpty() ? 0 : (100.0 * pooled / corpus.size());

        System.out.println("======================================================");
        System.out.println("  Training report - Fenix");
        System.out.println("======================================================");
        System.out.println("  Corpus              : " + corpus.size() + " FAQ entries, "
                + byIntent.size() + " intents");
        System.out.println("  Stemmed vocabulary  : " + bot.stats().get("vocabulary") + " terms");
        System.out.println("  Model               : multinomial Naive Bayes, Laplace smoothing");
        System.out.println("  Cross-validation    : " + foldCount + "-fold (stratified)");
        System.out.println("  Intent accuracy     : " + String.format(Locale.ROOT, "%.1f%%", accuracy)
                + "  (" + correct + "/" + total + ")");
        System.out.println("  Retrieval top-1     : " + String.format(Locale.ROOT, "%.1f%%", top1Rate)
                + "  (nearest neighbour shares the intent)");
        System.out.println("  Retrieval top-3     : " + String.format(Locale.ROOT, "%.1f%%", top3Rate)
                + "  (same intent in the 3 closest)");
        System.out.println("  Pooled intent pick  : " + String.format(Locale.ROOT, "%.1f%%", pooledRate)
                + "  (best question per intent, then argmax)");
        if (!misses.isEmpty()) {
            System.out.println("  Sample misses       :");
            for (String miss : misses) {
                System.out.println("    - " + miss);
            }
        }
        System.out.println("  Model file          : " + bot.classifier().classes().size()
                + " classes -> model/naive-bayes.tsv");
        System.out.println("======================================================");
    }

    private static String expectedIntent(List<List<String>> foldIntents, int fold, int index) {
        return foldIntents.get(fold).get(index);
    }

    // -------------------------------------------------------------- selftest

    private static boolean selfTest() {
        int passed = 0;
        int failed = 0;

        PorterStemmer stemmer = new PorterStemmer();
        String[][] stemCases = {
                {"caresses", "caress"}, {"ponies", "poni"}, {"ties", "ti"},
                {"caress", "caress"}, {"cats", "cat"}, {"connect", "connect"},
                {"connection", "connect"}, {"connecting", "connect"},
                {"agree", "agre"}, {"agreed", "agre"}, {"feed", "feed"},
                {"disabled", "disabl"}, {"matting", "mat"}, {"mating", "mate"},
                {"meeting", "meet"}, {"milling", "mill"}, {"messing", "mess"},
                {"racing", "race"}, {"running", "run"}, {"falling", "fall"},
                {"hopping", "hop"}, {"hoped", "hope"}, {"hate", "hate"},
                {"happy", "happi"}, {"relational", "relat"}, {"conditional", "condit"},
                {"rational", "ration"}, {"digitizer", "digit"}, {"radicalli", "radic"},
                {"analogousli", "analog"}, {"vietnamization", "vietnam"},
                {"predication", "predic"}, {"operator", "oper"}, {"feudalism", "feudal"},
                {"decisiveness", "decis"}, {"hopefulness", "hope"},
                {"callousness", "callous"}, {"kindness", "kind"},
                {"formaliti", "formal"}, {"sensitiviti", "sensit"},
                {"triplicate", "triplic"}, {"formative", "form"},
                {"formalize", "formal"}, {"electriciti", "electr"},
                {"electrical", "electr"}, {"hopeful", "hope"}, {"goodness", "good"},
                {"revival", "reviv"}, {"allowance", "allow"}, {"inference", "infer"},
                {"airliner", "airlin"}, {"gyroscopic", "gyroscop"},
                {"adjustable", "adjust"}, {"defensible", "defens"},
                {"irritation", "irrit"}, {"allow", "allow"}, {"movement", "movement"},
                {"disable", "disabl"}, {"create", "creat"}, {"transport", "transport"},
        };
        for (String[] pair : stemCases) {
            String actual = stemmer.stem(pair[0]);
            if (actual.equals(pair[1])) {
                passed++;
            } else {
                failed++;
                System.out.println("FAIL stem: " + pair[0] + " -> " + actual + " (expected " + pair[1] + ")");
            }
        }

        TextProcessor nlp = new TextProcessor();
        if ("i want to know about shipping".equals(nlp.normalize("I want to KNOW about shipping!"))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL normalize: " + nlp.normalize("I want to KNOW about shipping!"));
        }
        List<String> stems = nlp.stems("What are the shipping options?");
        if (stems.contains("ship") && !stems.contains("the") && !stems.contains("are")) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL pipeline: " + stems);
        }

        List<NaiveBayes.Example> examples = new ArrayList<>();
        examples.add(new NaiveBayes.Example(nlp.features("How do I get a refund"), "refunds"));
        examples.add(new NaiveBayes.Example(nlp.features("I want my money back"), "refunds"));
        examples.add(new NaiveBayes.Example(nlp.features("Can I return this order"), "refunds"));
        examples.add(new NaiveBayes.Example(nlp.features("When will my parcel arrive"), "shipping"));
        examples.add(new NaiveBayes.Example(nlp.features("Do you ship internationally"), "shipping"));
        examples.add(new NaiveBayes.Example(nlp.features("What is the delivery time"), "shipping"));
        NaiveBayes model = NaiveBayes.train(examples);
        NaiveBayes.Prediction prediction = model.predict(nlp.features("I would like my money back please"));
        if ("refunds".equals(prediction.intent) && prediction.confidence > 0.5) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL classifier: " + prediction.intent + " " + prediction.confidence);
        }

        Map<String, Object> parsed = chatbot.util.Json.parseObject("{\"message\":\"hello\",\"n\":2}");
        if ("hello".equals(parsed.get("message")) && Long.valueOf(2L).equals(parsed.get("n"))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL json: " + parsed);
        }

        System.out.println("Self-test: " + passed + " passed, " + failed + " failed");
        return failed == 0;
    }
}
