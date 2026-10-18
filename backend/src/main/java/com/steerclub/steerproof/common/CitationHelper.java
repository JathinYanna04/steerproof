package com.steerclub.steerproof.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds citation strings in code from the paper's own metadata row. The writer LLM never
 * produces citation text. Missing metadata is omitted, never invented.
 */
public class CitationHelper {

    private static final Map<String, String> JOURNAL_ABBREVIATIONS = Map.ofEntries(
            Map.entry("medical clinics of north america", "Med Clin North Am"),
            Map.entry("the medical clinics of north america", "Med Clin North Am"),
            Map.entry("diabetes, obesity and metabolism", "Diabetes Obes Metab"),
            Map.entry("diabetes, obesity & metabolism", "Diabetes Obes Metab"),
            Map.entry("diabetes obesity and metabolism", "Diabetes Obes Metab"),
            Map.entry("the new england journal of medicine", "N Engl J Med"),
            Map.entry("new england journal of medicine", "N Engl J Med"),
            Map.entry("the lancet", "Lancet"),
            Map.entry("the lancet diabetes & endocrinology", "Lancet Diabetes Endocrinol"),
            Map.entry("jama", "JAMA"),
            Map.entry("nature medicine", "Nat Med"),
            Map.entry("obesity reviews", "Obes Rev"),
            Map.entry("international journal of obesity", "Int J Obes"),
            Map.entry("diabetes care", "Diabetes Care")
    );

    /** Surname of the first listed author, handling "Kevin D. Hall", "Hall, Kevin D.; ..." and "Wilding JPH". */
    public static String firstAuthorSurname(String authors) {
        if (authors == null || authors.isBlank()) return null;
        String trimmed = authors.trim();
        String firstEntry;
        if (trimmed.contains(";")) {
            // "Hall, Kevin D.; Kahan, Scott" style: surname precedes the comma
            firstEntry = trimmed.split(";")[0].trim();
            if (firstEntry.contains(",")) {
                return firstEntry.split(",")[0].trim();
            }
        } else {
            firstEntry = trimmed.split(",")[0].trim();
        }
        firstEntry = firstEntry.replaceAll("(?i)\\s+et\\s+al\\.?", "").trim();
        String[] tokens = firstEntry.split("\\s+");
        if (tokens.length == 0 || tokens[0].isBlank()) return null;
        String last = tokens[tokens.length - 1].replace(".", "");
        // Vancouver style "Wilding JPH": trailing token is initials, the surname comes first
        if (tokens.length > 1 && last.matches("[A-Z]{1,4}")) {
            return tokens[0];
        }
        return tokens[tokens.length - 1];
    }

    public static int authorCount(String authors) {
        if (authors == null || authors.isBlank()) return 0;
        if (authors.contains(";")) return authors.split(";").length;
        if (authors.toLowerCase().contains("et al")) return 3;
        return authors.split(",").length;
    }

    public static String abbreviateJournal(String journal) {
        if (journal == null || journal.isBlank()) return null;
        String abbrev = JOURNAL_ABBREVIATIONS.get(journal.trim().toLowerCase());
        return abbrev != null ? abbrev : journal.trim();
    }

    /** Short form for assets: "<First author> et al., <year> · <journal abbrev>". Null when no author and no year. */
    public static String formatShortCitation(String authors, Integer year, String journal) {
        String surname = firstAuthorSurname(authors);
        if (surname == null && year == null) return null;

        StringBuilder sb = new StringBuilder();
        if (surname != null) {
            sb.append(surname);
            if (authorCount(authors) > 1) sb.append(" et al.");
        }
        if (year != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(year);
        }
        String j = abbreviateJournal(journal);
        if (j != null) sb.append(" · ").append(j);
        return sb.toString();
    }

    /** Full citation with title, for sources.md only. */
    public static String formatFullCitation(String authors, Integer year, String title, String journal, String doi) {
        List<String> parts = new ArrayList<>();
        if (authors != null && !authors.isBlank()) parts.add(authors.trim() + (year != null ? " (" + year + ")" : ""));
        else if (year != null) parts.add("(" + year + ")");
        if (title != null && !title.isBlank()) parts.add(title.trim());
        if (journal != null && !journal.isBlank()) parts.add(journal.trim());
        String s = String.join(". ", parts);
        if (doi != null && !doi.isBlank()) s += (s.isEmpty() ? "" : ". ") + "doi:" + doi.trim();
        return s.isEmpty() ? null : s + (s.endsWith(".") ? "" : ".");
    }
}
