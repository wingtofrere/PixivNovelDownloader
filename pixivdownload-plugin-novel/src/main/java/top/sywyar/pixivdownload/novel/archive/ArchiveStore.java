package top.sywyar.pixivdownload.novel.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Versioned journal in the host database. One transaction admits a page and advances its checkpoint. */
public final class ArchiveStore {
    public static final int VERSION = 1;
    private final DataSource source;
    private final ObjectMapper json;
    public record Entry(String kind, String key, ObjectNode data) {}

    public ArchiveStore(DataSource source, ObjectMapper json) {
        this.source = source;
        this.json = json;
    }

    public ObjectNode get(String kind, String key) {
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT version,payload,state,due FROM novel_archive_state WHERE kind=? AND entry_key=?")) {
            q.setString(1, kind); q.setString(2, key);
            try (ResultSet r = q.executeQuery()) { return r.next() ? decode(r) : null; }
        } catch (SQLException e) { throw new IllegalStateException("archive journal read failed", e); }
    }

    /** A newer release's data must never be silently interpreted or overwritten by an older release. */
    public void verifyVersion() {
        try (Connection c = source.getConnection(); Statement q = c.createStatement();
             ResultSet r = q.executeQuery("SELECT MIN(version),MAX(version) FROM novel_archive_state")) {
            if (r.next() && r.getObject(1) != null && (r.getInt(1) != VERSION || r.getInt(2) != VERSION))
                throw new IllegalStateException("Unsupported archive data version; keep database and upgrade application");
        } catch (SQLException e) { throw new IllegalStateException("archive journal unavailable", e); }
    }

    private ObjectNode decode(ResultSet r) throws SQLException {
        if (r.getInt("version") != VERSION) throw new IllegalStateException("Unsupported archive data version");
        try {
            if (json.readTree(r.getString("payload")) instanceof ObjectNode node)
                return node.put("state", r.getString("state")).put("due", r.getLong("due"));
            throw new IllegalStateException("Invalid archive journal payload");
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid archive journal payload", e); }
    }

    public void put(String kind, String key, ObjectNode data) { batch(List.of(new Entry(kind, key, data)), false); }

    /** Discovery uses insert-if-absent for works so another tag cannot reset completed/retry state. */
    public void admit(List<Entry> entries) { batch(entries, true); }

    public void batch(List<Entry> entries, boolean keepWorks) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (Entry e : entries) {
                    String conflict = keepWorks && e.kind().endsWith("work") ? "DO NOTHING" :
                            "DO UPDATE SET version=excluded.version,state=excluded.state,due=excluded.due,payload=excluded.payload";
                    try (PreparedStatement q = c.prepareStatement(
                            "INSERT INTO novel_archive_state(kind,entry_key,version,state,due,payload) VALUES(?,?,?,?,?,?) " +
                            "ON CONFLICT(kind,entry_key) " + conflict)) {
                        q.setString(1,e.kind()); q.setString(2,e.key()); q.setInt(3,VERSION);
                        q.setString(4,e.data().path("state").asText("PENDING"));
                        q.setLong(5,e.data().path("due").asLong(0)); q.setString(6,e.data().toString()); q.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException("archive journal write failed", e); }
    }

    public Entry next(String kind, long now, String... states) {
        String placeholders = String.join(",", Collections.nCopies(states.length, "?"));
        try (Connection c = source.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT entry_key,version,payload,state,due FROM novel_archive_state WHERE kind=? AND due<=? AND state IN (" +
                        placeholders + ") ORDER BY due,entry_key LIMIT 1")) {
            q.setString(1,kind); q.setLong(2,now);
            for (int i=0;i<states.length;i++) q.setString(i+3,states[i]);
            try (ResultSet r=q.executeQuery()) { return r.next() ? new Entry(kind,r.getString(1),decode(r)) : null; }
        } catch (SQLException e) { throw new IllegalStateException("archive queue read failed",e); }
    }

    public Map<String,Long> counts() {
        Map<String,Long> result=new TreeMap<>();
        try (Connection c=source.getConnection(); Statement q=c.createStatement(); ResultSet r=q.executeQuery(
                "SELECT kind,state,COUNT(*) FROM novel_archive_state GROUP BY kind,state")) {
            while(r.next()) result.put(r.getString(1)+":"+r.getString(2),r.getLong(3));
            return result;
        } catch(SQLException e) { throw new IllegalStateException("archive statistics failed",e); }
    }

    /** Explicit admin recovery keeps checkpoints and completed identities intact. */
    public void retryFailed(String prefix) {
        try(Connection c=source.getConnection();PreparedStatement q=c.prepareStatement(
                "UPDATE novel_archive_state SET state=CASE WHEN kind=? THEN 'DIRTY' ELSE 'PENDING' END,due=0,payload=json_set(payload,'$.attempts',0) WHERE kind IN (?,?,?) AND state='FAILED'")) {
            q.setString(1,prefix+"series");q.setString(2,prefix+"series");q.setString(3,prefix+"job");q.setString(4,prefix+"work");q.executeUpdate();
        } catch(SQLException e) {throw new IllegalStateException("archive retry failed",e);}
    }

    /** Re-evaluate stored metadata after a global blacklist change; downloaded content stays in novels. */
    public void recheck(String prefix) {
        try(Connection c=source.getConnection(); PreparedStatement q=c.prepareStatement(
                "UPDATE novel_archive_state SET state='PENDING',due=0 WHERE kind=? AND state IN ('COMPLETED','SKIPPED_EXCLUDED_TAG','DRY_RUN')")) {
            q.setString(1,prefix+"work"); q.executeUpdate();
        } catch(SQLException e) {throw new IllegalStateException("archive policy recheck failed",e);}
        try(Connection c=source.getConnection(); PreparedStatement q=c.prepareStatement(
                "UPDATE novel_archive_state SET state='DIRTY',due=0 WHERE kind=?")) {
            q.setString(1,prefix+"series"); q.executeUpdate();
        } catch(SQLException e) {throw new IllegalStateException("archive merge recheck failed",e);}
    }
}
