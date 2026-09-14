package top.sywyar.pixivdownload.novel.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.sywyar.pixivdownload.novel.archive.ArchiveTestSupport.*;

class ArchiveRuntimeTest {
    @TempDir Path dir;
    RuntimePathProvider paths() {
        return new RuntimePathProvider() {
            public Path configFile(String extension) {return dir.resolve("config/plugins/novel."+extension);}
            public Path stateDirectory() {return dir.resolve("state/novel");}
            public Path dataDirectory() {return dir.resolve("data/novel");}
        };
    }
    void config(String json) throws Exception {
        Path path=paths().configFile("json").resolveSibling("novel-archive.json");Files.createDirectories(path.getParent());Files.writeString(path,json);
    }
    @Test void absentConfigIsDisabledAndNeverInitializesWorker() throws Exception {
        var engine=mock(ArchiveEngine.class);var runtime=new ArchiveRuntime(new ArchiveStore(source(dir.resolve("db")),JSON),JSON,paths(),s->engine);
        runtime.start();assertThat(runtime.isRunning()).isFalse();verifyNoInteractions(engine);
    }
    @Test void unknownConfigFieldFailsInsteadOfSilentlyIgnoringBlacklistTypo() throws Exception {
        config("{\"enabled\":true,\"tags\":[\"A\"],\"excluded_tag\":[\"BL\"]}");
        var runtime=new ArchiveRuntime(new ArchiveStore(source(dir.resolve("db")),JSON),JSON,paths(),s->mock(ArchiveEngine.class));
        assertThatThrownBy(runtime::start).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pauseAndExclusiveRuntimeLockSurviveWorkerReplacement() throws Exception {
        config("{\"enabled\":true,\"tags\":[\"A\"]}");
        var store=new ArchiveStore(source(dir.resolve("db")),JSON);store.put("control","runtime",node("PENDING").put("paused",true));
        var first=new ArchiveRuntime(store,JSON,paths(),s->mock(ArchiveEngine.class));
        var second=new ArchiveRuntime(store,JSON,paths(),s->mock(ArchiveEngine.class));
        try {
            first.start();assertThat(first.isRunning()).isTrue();
            assertThatThrownBy(second::start).isInstanceOf(IllegalStateException.class);
            assertThat(first.isRunning()).isTrue();first.stop();
            second.start();assertThat(second.status().get("paused")).isEqualTo(true);
        } finally {first.stop();second.stop();}
    }
    @Test void firstBlockedRequestReportsRunningInsteadOfDisabled() throws Exception {
        config("{\"enabled\":true,\"tags\":[\"A\"]}");
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var engine=mock(ArchiveEngine.class);
        when(engine.tick(anyString())).thenAnswer(call->{entered.countDown();release.await();return true;});
        var runtime=new ArchiveRuntime(new ArchiveStore(source(dir.resolve("db")),JSON),JSON,paths(),s->engine);
        try {
            runtime.start();runtime.resume("test-cookie");
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.status().get("status")).isEqualTo("RUNNING");
            assertThat(runtime.isRunning()).isTrue();
        } finally {release.countDown();runtime.stop();}
    }
}
