package chatbot.nlp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The front end of the NLP pipeline: normalisation, tokenisation,
 * stop-word removal and stemming.
 *
 * <p>Every string that enters the reasoning layer goes through {@link #stems(String)}
 * first, so the classifier, the retrieval index and the rule engine all compare
 * like with like.</p>
 */
public final class TextProcessor {

    /** Function words that carry little discriminative meaning for retrieval. */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "and", "or", "but", "if", "then", "else", "when",
            "at", "by", "for", "with", "about", "against", "between", "into",
            "through", "during", "before", "after", "above", "below", "to", "from",
            "up", "down", "in", "out", "on", "off", "over", "under", "again",
            "further", "once", "here", "there", "all", "any", "both", "each", "few",
            "more", "most", "other", "some", "such", "nor", "only", "own", "same",
            "so", "than", "too", "very", "can", "will", "just", "should", "now",
            "do", "does", "did", "doing", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "having", "of", "that", "this", "these",
            "those", "i", "you", "he", "she", "it", "we", "they", "me", "him",
            "her", "us", "them", "my", "your", "his", "its", "our", "their", "what",
            "which", "who", "whom", "as", "how", "why", "am", "please", "really"));

    private final PorterStemmer stemmer = new PorterStemmer();

    /** Lower-cases, strips punctuation and collapses whitespace. */
    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    /** Normalise, split on whitespace and drop stop words (no stemming). */
    public List<String> tokenize(String text) {
        String normalized = normalize(text);
        List<String> tokens = new ArrayList<>();
        if (normalized.isEmpty()) {
            return tokens;
        }
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty() && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Adds adjacent word pairs ("credit card" -> credit_card) to the bag. */
    public List<String> ngrams(List<String> stems) {
        List<String> out = new ArrayList<>(stems);
        for (int i = 0; i + 1 < stems.size(); i++) {
            out.add(stems.get(i) + "_" + stems.get(i + 1));
        }
        return out;
    }

    /** Full pipeline: normalise, tokenize, de-stop-word, stem. */
    public List<String> stems(String text) {
        List<String> tokens = tokenize(text);
        List<String> result = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            String stemmed = stemmer.stem(token);
            if (stemmed != null && !stemmed.isEmpty() && !result.contains(stemmed)) {
                result.add(stemmed);
            }
        }
        return result;
    }

    /**
     * Classification features: every token is kept, stop words included.
     * Small function words ("how", "can", "my") are poor for retrieval but
     * are excellent intent signals, so the classifier gets the full bag.
     */
    public List<String> features(String text) {
        String normalized = normalize(text);
        List<String> out = new ArrayList<>();
        if (normalized.isEmpty()) {
            return out;
        }
        for (String token : normalized.split(" ")) {
            String stemmed = stemmer.stem(token);
            if (stemmed != null && !stemmed.isEmpty()) {
                out.add(stemmed);
            }
        }
        return out;
    }

    /** Bag-of-words frequencies (stop words removed). */
    public List<String> stemAll(String text) {
        List<String> tokens = tokenize(text);
        List<String> result = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            String stemmed = stemmer.stem(token);
            if (stemmed != null && !stemmed.isEmpty()) {
                result.add(stemmed);
            }
        }
        return result;
    }

    public PorterStemmer stemmer() {
        return stemmer;
    }
}
