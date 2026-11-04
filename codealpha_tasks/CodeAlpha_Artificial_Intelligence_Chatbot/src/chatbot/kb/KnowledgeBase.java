package chatbot.kb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The FAQ knowledge base.
 *
 * <p>Two tab-separated files are merged at load time:</p>
 * <ul>
 *   <li>{@code data/faq.csv}    -- the seeded, hand-written training corpus</li>
 *   <li>{@code data/trained.csv} -- everything the bot learned at runtime</li>
 * </ul>
 *
 * <p>Format: {@code intent <TAB> question <TAB> answer}. Lines starting with
 * {@code #} are comments; the first three fields are enough, extra tabs belong
 * to the answer.</p>
 */
public final class KnowledgeBase {

    public static final class Entry {
        public final String intent;
        public final String question;
        public final String answer;

        public Entry(String intent, String question, String answer) {
            this.intent = intent;
            this.question = question;
            this.answer = answer;
        }
    }

    private static final String[] COLUMNS = {"intent", "question", "answer"};

    private final Path baseDir;
    private final Path seededFile;
    private final Path trainedFile;
    private final List<Entry> entries = new ArrayList<>();

    public KnowledgeBase(Path baseDir) {
        this.baseDir = baseDir;
        this.seededFile = baseDir.resolve("data").resolve("faq.csv");
        this.trainedFile = baseDir.resolve("data").resolve("trained.csv");
    }

    public synchronized void reload() throws IOException {
        entries.clear();
        entries.addAll(read(seededFile));
        entries.addAll(read(trainedFile));
    }

    private List<Entry> read(Path file) throws IOException {
        List<Entry> loaded = new ArrayList<>();
        if (!Files.exists(file)) {
            return loaded;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            String intent = parts[0].trim();
            String question = parts[1].trim();
            String answer = parts[2].trim();
            if (!intent.isEmpty() && !question.isEmpty() && !answer.isEmpty()) {
                loaded.add(new Entry(intent, question, answer));
            }
        }
        return loaded;
    }

    /** Persists a newly taught Q&A pair and appends it to the in-memory index. */
    public synchronized Entry teach(String intent, String question, String answer) throws IOException {
        Entry entry = new Entry(intent.trim(), question.trim(), answer.trim());
        Files.createDirectories(trainedFile.getParent());
        if (!Files.exists(trainedFile)) {
            Files.writeString(trainedFile, "# intent\tquestion\tanswer\n", StandardCharsets.UTF_8);
        }
        String line = entry.intent + "\t" + entry.question + "\t" + entry.answer + "\n";
        Files.writeString(trainedFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        entries.add(entry);
        return entry;
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized List<String> intents() {
        Set<String> intents = new LinkedHashSet<>();
        for (Entry entry : entries) {
            intents.add(entry.intent);
        }
        return new ArrayList<>(intents);
    }

    /** Intent name -> canonical answer (the first answer written for it). */
    public synchronized MapView answersByIntent() {
        MapView view = new MapView();
        for (Entry entry : entries) {
            view.putIfAbsent(entry.intent, entry.answer);
        }
        return view;
    }

    public synchronized int size() {
        return entries.size();
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path modelFile() {
        return baseDir.resolve("model").resolve("naive-bayes.tsv");
    }

    /** Tiny insertion-ordered map to avoid exposing java.util.Map plumbing. */
    public static final class MapView {
        private final java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();

        void putIfAbsent(String key, String value) {
            map.putIfAbsent(key, value);
        }

        public String get(String key) {
            return map.get(key);
        }

        public Set<String> keySet() {
            return map.keySet();
        }

        public int size() {
            return map.size();
        }

        public List<String> questionsFor(String intent, List<Entry> all) {
            List<String> questions = new ArrayList<>();
            for (Entry entry : all) {
                if (entry.intent.equals(intent)) {
                    questions.add(entry.question);
                }
            }
            return questions;
        }
    }
}
