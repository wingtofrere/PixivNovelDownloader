package top.sywyar.pixivdownload.novel.archive;

/** Retry policy separates indefinite network recovery from bounded invalid-content attempts. */
public final class ArchiveRetry {
    private ArchiveRetry() {}
    public static long delay(int status,int failures,long retryAfter) {
        long base=status==429 ? 300_000L : status==403 ? 900_000L : 30_000L;
        long backoff=Math.min(3_600_000L,base*(1L << Math.min(7,Math.max(0,failures-1))));
        return Math.max(backoff,retryAfter);
    }
    public static boolean requiresLogin(int status,int failures) {
        return status==401 || (status==403 && failures>=3);
    }
}
