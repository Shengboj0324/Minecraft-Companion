package io.github.shengboj0324.architect.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tells a request to change a build that already stands in the world ("fix this base", "now do the bottom half",
 * "add an elevator to my tower") from a request for something new ("build a castle"). The first needs a
 * renovation, which captures the build; a new design starts from an empty model and cannot see it.
 */
public final class RequestIntent {

    private RequestIntent() { }

    private static final Pattern EXISTING = Pattern.compile(
            "\\b(renovat\\w*|refurbish\\w*|remodel\\w*|restor\\w*|repair\\w*|fix\\w*|redo|redecorat\\w*|revamp\\w*|reinvent\\w*|rework\\w*|upgrad\\w*|improv\\w*|patch\\w*|drain\\w*)\\b"
                    + "|\\b(this|my|our|the|that|existing|current|same)\\s+(\\w+\\s+){0,2}(base|build|building|house|home|castle|tower|structure|place|bunker|mansion|ship|fort|fortress|palace|temple|city|village|hall|lab|outpost)\\b"
                    + "|\\b(bottom|top|lower|upper|left|right|east|west|north|south|other|rest of the|underwater|below the|first|second|third|ground)\\s+(half|part|side|floors?|levels?|section|wing|deck|parts|storeys?|stories)\\b"
                    + "|\\b(add|put|install|give)\\b.*\\b(to|in|into|on|onto|inside)\\s+(it|this|that|here|there)\\b"
                    + "|\\b(continue|keep going|carry on|finish (the|it)|now (do|the|work))\\b");

    /** Does the request refer to a build that already exists (rather than ask for a new one)? */
    public static boolean aboutExistingBuild(String request) {
        if (request == null) return false;
        String t = request.toLowerCase(Locale.ROOT);
        if (t.matches(".*\\b(build|make|design|create|construct)\\s+(me\\s+|us\\s+)?(a|an|the|some|two|three|\\d+)\\b.*")
                && !t.matches(".*\\b(this|my|our|existing|current)\\b.*") && !t.matches(".*\\b(renovat|repair|fix|remodel|restor)\\w*.*"))
            return false; // "build me a castle", "build the Eiffel Tower"
        return EXISTING.matcher(t).find();
    }
}
