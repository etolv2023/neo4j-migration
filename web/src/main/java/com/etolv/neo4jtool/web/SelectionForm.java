package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The selection as typed into the form: plain text fields that become a {@link SelectionConfig}.
 * Lists are separated by commas, spaces or new lines; an id range is written {@code 5000-9000}.
 */
public record SelectionForm(String orgProperty, String orgIds, String labels, String ids, String idRanges, String excludeIds,
                            Boolean includeNeighbours) {

    /** A ticked box arrives as "true"; an unticked one does not arrive at all. */
    public SelectionForm {
        includeNeighbours = Boolean.TRUE.equals(includeNeighbours);
    }

    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");
    private static final Pattern RANGE = Pattern.compile("(\\d+)\\s*(?:-|\\.\\.|:)\\s*(\\d+)");

    public static SelectionForm empty(boolean includeNeighbours) {
        return new SelectionForm("", "", "", "", "", "", includeNeighbours);
    }

    /** @throws IllegalArgumentException with a message that names the field that is wrong */
    public SelectionConfig toConfig(String defaultOrgProperty) {
        List<Object> orgs = new ArrayList<>();
        for (String token : tokens(orgIds)) {
            orgs.add(token.matches("-?\\d+") ? (Object) Long.parseLong(token) : token);
        }
        List<List<Long>> ranges = new ArrayList<>();
        String rest = idRanges == null ? "" : idRanges;
        Matcher m = RANGE.matcher(rest);
        while (m.find()) {
            ranges.add(List.of(Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
        }
        String leftover = m.replaceAll(" ").replaceAll("[,;\\s]+", "");
        if (!leftover.isEmpty()) {
            throw new IllegalArgumentException("Id ranges: write them as 5000-9000, separated by commas or lines (got '" + idRanges.strip() + "')");
        }
        return new SelectionConfig(blank(orgProperty) ? defaultOrgProperty : orgProperty.strip(), orgs, tokens(labels),
                numbers(ids, "Ids"), ranges, numbers(excludeIds, "Excluded ids"), includeNeighbours);
    }

    private static List<Long> numbers(String text, String field) {
        List<Long> result = new ArrayList<>();
        for (String token : tokens(text)) {
            if (!token.matches("\\d+")) {
                throw new IllegalArgumentException(field + " must be whole numbers (got '" + token + "')");
            }
            result.add(Long.parseLong(token));
        }
        return result;
    }

    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        if (!blank(text)) {
            for (String token : SEPARATORS.split(text.strip())) {
                if (!token.isEmpty()) {
                    result.add(token);
                }
            }
        }
        return result;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
