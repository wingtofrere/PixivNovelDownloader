package top.sywyar.pixivdownload.novel.archive;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/** Global configuration; job-local blacklist overrides deliberately do not exist. */
public record ArchiveSettings(boolean enabled, boolean dryRun, List<String> tags, List<String> excludedTags,
                              int maxChapters, int minDelaySeconds, int maxDelaySeconds, int maxPages,
                              String since, boolean downloadCover) {
    public ArchiveSettings {
        tags = clean(tags); excludedTags = clean(excludedTags);
        if (maxChapters < 1 || maxChapters > 100 || minDelaySeconds < 1 || maxDelaySeconds < minDelaySeconds
                || maxDelaySeconds > 3600 || maxPages < 1 || maxPages > 1000)
            throw new IllegalArgumentException("Invalid archive limits/delay");
        LocalDate.parse(since);
        if (LocalDate.parse(since).isAfter(LocalDate.now())) throw new IllegalArgumentException("since is in the future");
        if (enabled && tags.isEmpty()) throw new IllegalArgumentException("Archive tags are required");
    }
    public static ArchiveSettings defaults() {
        return new ArchiveSettings(false,true,List.of(),List.of(),10,6,15,1000,"2007-09-10",true);
    }
    private static List<String> clean(List<String> values) {
        if(values==null) return List.of();
        if(values.stream().anyMatch(v -> v==null || v.isBlank())) throw new IllegalArgumentException("Empty tag");
        return values.stream().map(String::trim).distinct().toList();
    }
    public static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    public List<String> matches(List<String> actual) {
        Set<String> tags=new HashSet<>(); actual.forEach(t -> tags.add(normalize(t)));
        return excludedTags.stream().filter(t -> tags.contains(normalize(t))).toList();
    }
}
