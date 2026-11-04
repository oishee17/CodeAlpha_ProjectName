package chatbot.brain;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The deterministic half of the bot: pattern-matched, rule-based answers for
 * conversational intents (greetings, thanks, small talk, "what can you do").
 *
 * <p>Rules run before the machine-learning layer because an exact conversational
 * match should never be left to a probability. Anything they do not claim is
 * handed to {@link NaiveBayes}-driven intent classification and TF-IDF retrieval.</p>
 */
public final class RuleEngine {

    public static final class Result {
        public final String intent;
        public final String reply;

        Result(String intent, String reply) {
            this.intent = intent;
            this.reply = reply;
        }
    }

    private static final class Rule {
        final String intent;
        final Pattern pattern;
        final Supplier<String> reply;

        Rule(String intent, String pattern, Supplier<String> reply) {
            this.intent = intent;
            this.pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            this.reply = reply;
        }
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    private final List<Rule> rules = new ArrayList<>();

    public RuleEngine() {
        rules.add(new Rule("datetime",
                "\\b(what('?s| is)? (the )?(time|date)|current (time|date)|what day is it|today('?s| is) date)\\b.*",
                () -> {
                    LocalTime now = LocalTime.now(ZoneId.systemDefault());
                    return "It is " + now.format(CLOCK) + " right now. Anything else I can help with?";
                }));

        rules.add(new Rule("greeting",
                "^(hi|hey|hello|yo|howdy|hiya|good (morning|afternoon|evening)|greetings|salam|assalamualaikum)\\b.*",
                () -> "Hello! I'm Fenix, an NLP chatbot. Ask me about the FAQs I know, "
                        + "or type \"help\" to see what I can do."));

        rules.add(new Rule("how_are_you",
                "\\bhow('?s| is| are)? (it going|you( today)?|things going)\\b.*|^how do you do\\b.*",
                () -> "I'm running smoothly - thanks for asking! My NLP pipeline is warmed up and "
                        + "ready for your questions."));

        rules.add(new Rule("identity",
                "\\b(who|what) (are|r) (you|u|yourself)\\b.*|^your name\\b.*|\\bwhat should i call you\\b.*",
                () -> "I'm Fenix, a Java chatbot. I combine rule-based patterns, a Naive Bayes "
                        + "classifier and TF-IDF retrieval over a trained FAQ corpus."));

        rules.add(new Rule("capabilities",
                "\\b(what can you do|help|commands|features|capabilities|how do you work)\\b.*",
                () -> "Here's what I can do:\n"
                        + "1. Answer frequently asked questions (trained FAQ corpus).\n"
                        + "2. Match your wording to the closest known question, even with different phrasing.\n"
                        + "3. Classify your intent with a Naive Bayes machine-learning model.\n"
                        + "4. Learn new answers live - if I don't know something, teach me and I'll remember it.\n"
                        + "Just type your question to get started."));

        rules.add(new Rule("creator",
                "\\b(who|what) (made|built|created|developed) (you|this|the bot)\\b.*|\\b(your|the) (author|creator|developer)\\b.*",
                () -> "I was built in Java using natural language processing: tokenisation, stop-word "
                        + "removal, Porter stemming, TF-IDF cosine similarity and a Naive Bayes classifier."));

        rules.add(new Rule("thanks",
                "\\b(thanks|thank you|thankyou|thx|cheers|ta|appreciate it)\\b.*",
                () -> "You're welcome! Anything else you'd like to know?"));

        rules.add(new Rule("goodbye",
                "^(bye|bye bye|goodbye|see (you|ya)|later|good night|goodnight|cya)\\b.*",
                () -> "Goodbye! Come back any time - I'll keep learning the questions you teach me."));

        rules.add(new Rule("bot_feeling",
                "\\bhow do you (feel|think)\\b.*|\\bare you (alive|real|human|a robot)\\b.*",
                () -> "I'm a program, so I don't feel things - but I am very much alive in the JVM! "
                        + "I process your text with real NLP techniques instead."));

        rules.add(new Rule("joke",
                "\\b(tell me )?(a )?joke\\b.*|\\bmake me laugh\\b.*|\\bsomething funny\\b.*",
                () -> "Why did the programmer quit his job? Because he didn't get arrays (a raise)! "
                        + "Ba-dum-tss."));
    }

    /** Returns the first matching rule, or {@code null} when the text is conversational noise. */
    public Result match(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return null;
        }
        for (Rule rule : rules) {
            if (rule.pattern.matcher(cleaned).matches()) {
                return new Result(rule.intent, rule.reply.get());
            }
        }
        return null;
    }

    public int ruleCount() {
        return rules.size();
    }
}
