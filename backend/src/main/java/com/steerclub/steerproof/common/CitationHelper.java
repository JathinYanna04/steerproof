package com.steerclub.steerproof.common;

public class CitationHelper {

    public static String formatShortCitation(String authors, Integer year, String journal) {
        String firstAuthor = "Authors";
        if (authors != null && !authors.isBlank()) {
            String firstEntry = authors.split("[,;]")[0].trim();
            firstEntry = firstEntry.replaceAll("(?i)\\s+et\\s+al\\.?", "").trim();
            String[] tokens = firstEntry.split("\\s+");
            if (tokens.length > 0) {
                firstAuthor = tokens[tokens.length - 1];
            }
        }
        int y = year != null ? year : 2022;
        String j = (journal != null && !journal.isBlank()) ? journal : "Clinical Study";
        return firstAuthor + " et al., " + y + " · " + j;
    }
}
