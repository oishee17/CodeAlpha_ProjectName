package chatbot.nlp;

/**
 * The Porter stemming algorithm (Martin Porter, 1980).
 *
 * <p>Reduces inflected English words to a common stem so that "connect",
 * "connecting", "connected" and "connection" all collapse onto one token.
 * Both the knowledge-base documents and the live user query are passed
 * through this same stemmer, which is what makes the fuzzy matching work.</p>
 */
public final class PorterStemmer {

    private static final int K0 = 0;

    private char[] b;
    private int k;      // exclusive end of the word region being worked on
    private int j;      // stem boundary left behind by the last successful suffix match

    public String stem(String word) {
        if (word == null) {
            return null;
        }
        if (word.length() < 3) {
            return word;
        }
        b = word.toCharArray();
        k = b.length;
        j = k;
        step1a();
        step1b();
        step1c();
        step2();
        step3();
        step4();
        step5a();
        step5b();
        return new String(b, 0, k);
    }

    // ---------------------------------------------------------------- steps

    private void step1a() {
        if (b[k - 1] != 's') {
            return;
        }
        if (match("sses")) {
            k -= 2;
        } else if (match("ies")) {
            k -= 2;
        } else if (k < 2 || b[k - 2] != 's') {
            k--;
        }
    }

    private void step1b() {
        if (match("eed")) {
            if (m() > 0) {
                k--;
            }
            return;
        }
        if (!(match("ed") || match("ing")) || !vowelInStem()) {
            return;
        }
        k = j;
        if (match("at")) {
            apply("ate");
            return;
        }
        if (match("bl")) {
            apply("ble");
            return;
        }
        if (match("iz")) {
            apply("ize");
            return;
        }
        if (doubleCons()) {
            k--;
            char tail = b[k - 1];
            if (tail == 'l' || tail == 's' || tail == 'z') {
                k++;
            }
            return;
        }
        if (m() == 1 && cvc(k - 1)) {
            append("e");
        }
    }

    private void step1c() {
        // (y -> i) when the remaining stem still contains a vowel
        if (match("y") && vowelInStem()) {
            b[j] = 'i';
        }
    }

    private void step2() {
        if (subst("ational", "ate")) return;
        if (subst("ization", "ize")) return;
        if (subst("fulness", "ful")) return;
        if (subst("ousness", "ous")) return;
        if (subst("alness", "al")) return;
        if (subst("tional", "tion")) return;
        if (subst("biliti", "ble")) return;
        if (subst("iviti", "ive")) return;
        if (subst("aliti", "al")) return;
        if (subst("alism", "al")) return;
        if (subst("ation", "ate")) return;
        if (subst("ousli", "ous")) return;
        if (subst("ator", "ate")) return;
        if (subst("izer", "ize")) return;
        if (subst("alli", "al")) return;
        if (subst("entli", "ent")) return;
        if (subst("abli", "able")) return;
        if (subst("fulli", "ful")) return;
        if (subst("enci", "ence")) return;
        if (subst("anci", "ance")) return;
        if (subst("eli", "e")) return;
        if (match("ogi") && j > K0 && b[j - 1] == 'l' && m() > 0) {
            k = j + 1;
        }
    }

    private void step3() {
        if (subst("icate", "ic")) return;
        if (subst("ative", "")) return;
        if (subst("alize", "al")) return;
        if (subst("iciti", "ic")) return;
        if (subst("ical", "ic")) return;
        if (subst("ful", "")) return;
        if (subst("ness", "")) return;
    }

    private void step4() {
        if (delete("ement")) return;
        if (delete("ment")) return;
        if (delete("able")) return;
        if (delete("ible")) return;
        if (delete("ance")) return;
        if (delete("ence")) return;
        if (delete("ant")) return;
        if (delete("ent")) return;
        if (delete("ism")) return;
        if (delete("ate")) return;
        if (delete("iti")) return;
        if (delete("ous")) return;
        if (delete("ive")) return;
        if (delete("ize")) return;
        if (delete("al")) return;
        if (delete("er")) return;
        if (delete("ic")) return;
        if (delete("ou")) return;
        if (match("ion")) {
            // only after s or t: "operation", "connections"
            if (j - 1 >= K0 && (b[j - 1] == 's' || b[j - 1] == 't') && m() > 1) {
                k = j;
            }
        }
    }

    private void step5a() {
        if (!match("e")) {
            return;
        }
        int measure = m();
        if (measure > 1 || (measure == 1 && !cvc(j - 1))) {
            k = j;
        }
    }

    private void step5b() {
        if (!match("l")) {
            return;
        }
        if (doubleCons() && m() > 1) {
            k--;
        }
    }

    // ------------------------------------------------------------- helpers

    /** True when the current region ends with {@code suffix}; on success stores the stem end in {@link #j}. */
    private boolean match(String suffix) {
        int len = suffix.length();
        if (len > k - K0) {
            return false;
        }
        int start = k - len;
        for (int i = 0; i < len; i++) {
            if (b[start + i] != suffix.charAt(i)) {
                return false;
            }
        }
        j = start;
        return true;
    }

    /**
     * Replaces a suffix when the stem meets the required measure (m > 0).
     * Returns {@code true} whenever the suffix matched textually, even if the
     * measure was too small: once a suffix is recognised the step is spent and
     * shorter alternatives must not be tried instead.
     */
    private boolean subst(String suffix, String replacement) {
        if (!match(suffix)) {
            return false;
        }
        if (m() > 0) {
            apply(replacement);
        }
        return true;
    }

    /** Deletes a suffix when the stem measure is m > 1; see {@link #subst}. */
    private boolean delete(String suffix) {
        if (!match(suffix)) {
            return false;
        }
        if (m() > 1) {
            apply("");
        }
        return true;
    }

    private void apply(String replacement) {
        char[] next = new char[j + replacement.length()];
        System.arraycopy(b, 0, next, 0, j);
        for (int i = 0; i < replacement.length(); i++) {
            next[j + i] = replacement.charAt(i);
        }
        b = next;
        k = next.length;
        j = k;
    }

    /** Appends letters to the end of the current word (the "add an e" rule). */
    private void append(String suffix) {
        char[] next = new char[k + suffix.length()];
        System.arraycopy(b, 0, next, 0, k);
        for (int i = 0; i < suffix.length(); i++) {
            next[k + i] = suffix.charAt(i);
        }
        b = next;
        k = next.length;
    }

    /** Number of VC sequences in the stem b[K0, j) -- the Porter "measure". */
    private int m() {
        int n = 0;
        int i = K0;
        while (i < j && cons(i)) {
            i++;
        }
        while (true) {
            int vowels = i;
            while (i < j && !cons(i)) {
                i++;
            }
            if (i == vowels) {
                return n;
            }
            int consonants = i;
            while (i < j && cons(i)) {
                i++;
            }
            if (i == consonants) {
                return n;
            }
            n++;
        }
    }

    private boolean cons(int i) {
        if (i < K0 || i >= k) {
            return false;
        }
        char c = b[i];
        if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') {
            return false;
        }
        if (c == 'y') {
            return i == K0 || !cons(i - 1);
        }
        return true;
    }

    private boolean vowelInStem() {
        for (int i = K0; i < j; i++) {
            if (!cons(i)) {
                return true;
            }
        }
        return false;
    }

    private boolean doubleCons() {
        if (k - K0 < 2) {
            return false;
        }
        return b[k - 1] == b[k - 2] && cons(k - 1);
    }

    /** True when the word ends consonant-vowel-consonant (final c not w, x or y). */
    private boolean cvc(int i) {
        if (i < K0 + 2 || i >= k) {
            return false;
        }
        if (!cons(i) || cons(i - 1) || !cons(i - 2)) {
            return false;
        }
        char c = b[i];
        return c != 'w' && c != 'x' && c != 'y';
    }
}
