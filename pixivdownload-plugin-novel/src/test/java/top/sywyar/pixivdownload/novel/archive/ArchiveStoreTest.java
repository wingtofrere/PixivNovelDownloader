package top.sywyar.pixivdownload.novel.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.novel.archive.ArchiveTestSupport.*;

class ArchiveStoreTest {
    @TempDir Path dir;
    @Test void releaseRestartPreservesPageCompletedIdsSeriesAndRetryTime() throws Exception {
        var ds=source(dir.resolve("progress.sqlite"));var old=new ArchiveStore(ds,JSON);
        old.put("job","A",node("PENDING").put("page",438));
        old.put("work","123",node("COMPLETED"));
        old.put("work","124",node("RETRY_WAIT").put("due",9999999999999L));
        old.put("series","88",node("DIRTY").put("maxChapters",10));
        var upgraded=new ArchiveStore(source(dir.resolve("progress.sqlite")),JSON);upgraded.verifyVersion();
        assertThat(upgraded.get("job","A").path("page").asInt()).isEqualTo(438);
        assertThat(upgraded.get("work","123").path("state").asText()).isEqualTo("COMPLETED");
        assertThat(upgraded.next("work",System.currentTimeMillis(),"RETRY_WAIT")).isNull();
        assertThat(upgraded.get("series","88").path("maxChapters").asInt()).isEqualTo(10);
    }
    @Test void duplicateDiscoveryCannotResetDownloadOrRetry() throws Exception {
        var store=new ArchiveStore(source(dir.resolve("db")),JSON);store.put("work","1",node("COMPLETED"));
        store.admit(List.of(new ArchiveStore.Entry("work","1",node("PENDING")),new ArchiveStore.Entry("job","B",node("PENDING").put("page",2))));
        assertThat(store.get("work","1").path("state").asText()).isEqualTo("COMPLETED");
        assertThat(store.get("job","B").path("page").asInt()).isEqualTo(2);
    }
    @Test void failedPageCommitRollsBackCheckpointAndDiscoveredIds() throws Exception {
        var ds=source(dir.resolve("db"));var store=new ArchiveStore(ds,JSON);store.put("job","A",node("PENDING").put("page",3));
        try(var c=ds.getConnection();var s=c.createStatement()) {
            s.execute("CREATE TRIGGER abort_page BEFORE UPDATE ON novel_archive_state WHEN NEW.kind='job' BEGIN SELECT RAISE(ABORT,'simulated crash'); END");
        }
        assertThatThrownBy(()->store.admit(List.of(new ArchiveStore.Entry("work","99",node("PENDING")),
                new ArchiveStore.Entry("job","A",node("PENDING").put("page",4))))).isInstanceOf(IllegalStateException.class);
        assertThat(store.get("work","99")).isNull();assertThat(store.get("job","A").path("page").asInt()).isEqualTo(3);
    }
    @Test void futureVersionRefusesStartupWithoutChangingData() throws Exception {
        var ds=source(dir.resolve("db"));var store=new ArchiveStore(ds,JSON);store.put("work","1",node("COMPLETED"));
        try(var c=ds.getConnection();var s=c.createStatement()) {s.executeUpdate("UPDATE novel_archive_state SET version=999");}
        assertThatThrownBy(store::verifyVersion).isInstanceOf(IllegalStateException.class);
        try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT version,state FROM novel_archive_state")) {
            r.next();assertThat(r.getInt(1)).isEqualTo(999);assertThat(r.getString(2)).isEqualTo("COMPLETED");
        }
    }
    @Test void policyRecheckUpdatesBothQueueAndLoadedState() throws Exception {
        var store=new ArchiveStore(source(dir.resolve("db")),JSON);store.put("work","1",node("COMPLETED"));store.recheck("");
        assertThat(store.next("work",1,"PENDING").data().path("state").asText()).isEqualTo("PENDING");
    }
}
