package top.sywyar.pixivdownload.novel.archive;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.sywyar.pixivdownload.novel.archive.ArchiveTestSupport.*;

class ArchiveEngineTest {
    @TempDir Path dir;
    ArchiveStore store; ArchivePixiv pixiv; NovelDatabase novels; NovelDownloadService downloads; NovelMergeService merges;
    @BeforeEach void setup() throws Exception {
        store=new ArchiveStore(source(dir.resolve("db")),JSON);pixiv=mock(ArchivePixiv.class);novels=mock(NovelDatabase.class);
        downloads=mock(NovelDownloadService.class);merges=mock(NovelMergeService.class);
        when(downloads.downloadBlocking(any(),isNull())).thenReturn(true);
        when(merges.mergeArchive(anyLong(),anyList(),any())).thenReturn(new NovelMergeService.MergeResult(true,"ok","unused",1));
    }
    ArchiveEngine engine(ArchiveSettings config) {var engine=new ArchiveEngine(store,JSON,config,pixiv,novels,downloads,merges,()->{});engine.initialize();return engine;}
    @Test void hitChapter37DownloadsOnlyFirstTenMinusBlacklistAcrossTwoTags() throws Exception {
        when(pixiv.search(anyString(),any(),any(),anyInt(),anyString())).thenReturn(search(1,37));
        when(pixiv.series(eq(88L),eq(0),eq(30),anyString())).thenReturn(series(30));
        when(pixiv.novel(anyLong(),anyString())).thenAnswer(a->body(a.getArgument(0),88L,((Long)a.getArgument(0))==3?"BL":"allowed"));
        var engine=engine(settings(false,List.of("BL"),"A","B"));for(int i=0;i<40;i++)engine.tick("fixture");
        ArgumentCaptor<NovelDownloadRequest> captured=ArgumentCaptor.forClass(NovelDownloadRequest.class);
        verify(downloads,times(9)).downloadBlocking(captured.capture(),isNull());
        assertThat(captured.getAllValues().stream().map(NovelDownloadRequest::getNovelId)).containsExactlyInAnyOrder(1L,2L,4L,5L,6L,7L,8L,9L,10L);
        assertThat(captured.getAllValues()).allSatisfy(r->{assertThat(r.getOther().isSkipEmbeddedImages()).isTrue();assertThat(r.getOther().getEmbeddedImages()).isEmpty();});
        verify(pixiv,times(1)).series(anyLong(),anyInt(),anyInt(),anyString());
        assertThat(store.get("work","37").path("state").asText()).isEqualTo("SKIPPED_SERIES_LIMIT");
        verify(merges).mergeArchive(88L,List.of(1L,2L,4L,5L,6L,7L,8L,9L,10L),NovelDownloadService.NovelFormat.EPUB);
        // New release instance with identical settings must not refresh the series or redownload completed works.
        var next=engine(settings(false,List.of("BL"),"A","B"));for(int i=0;i<5;i++)assertThat(next.tick("fixture")).isFalse();
        verify(downloads,times(9)).downloadBlocking(any(),isNull());
    }
    @Test void dryRunNeverDownloadsOrConsumesRealProgress() throws Exception {
        when(pixiv.search(anyString(),any(),any(),anyInt(),anyString())).thenReturn(search(1,101));
        when(pixiv.novel(eq(101L),anyString())).thenReturn(body(101,null,"allowed"));
        var dry=engine(settings(true,List.of(),"A"));for(int i=0;i<5;i++)dry.tick("fixture");
        verifyNoInteractions(downloads,merges);assertThat(store.get("work","101")).isNull();
        var real=engine(settings(false,List.of(),"A"));for(int i=0;i<5;i++)real.tick("fixture");
        verify(downloads).downloadBlocking(any(),isNull());
    }
    @Test void missingTagsFailsClosed() throws Exception {
        when(pixiv.search(anyString(),any(),any(),anyInt(),anyString())).thenReturn(search(1,101));
        var invalid=body(101,null);invalid.remove("tags");when(pixiv.novel(eq(101L),anyString())).thenReturn(invalid);
        var engine=engine(settings(false,List.of(),"A"));engine.tick("fixture");engine.tick("fixture");
        verifyNoInteractions(downloads);assertThat(store.get("work","101").path("state").asText()).isEqualTo("RETRY_WAIT");
    }
    @Test void offlineAnd429CooldownSurviveRestartWithoutConsumingWorkAttempts() throws Exception {
        when(pixiv.search(anyString(),any(),any(),anyInt(),anyString())).thenThrow(new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS,429,900000));
        var engine=engine(settings(false,List.of(),"A"));engine.tick("fixture");
        var due=store.get("control","network").path("due").asLong();assertThat(due).isGreaterThan(System.currentTimeMillis()+890000);
        var restarted=engine(settings(false,List.of(),"A"));assertThat(restarted.tick("fixture")).isFalse();
        verify(pixiv,times(1)).search(anyString(),any(),any(),anyInt(),anyString());
        assertThat(store.get("job","A").path("attempts").asInt()).isZero();
    }
    @Test void restartContinuesSavedPage() {
        var job=node("PENDING").put("tag","A").put("page",438).put("pageSize",24).put("from","2026-01-01").put("to","2026-01-02");job.putArray("remaining");
        store.put("job","A",job);
        when(pixiv.search(anyString(),any(),any(),eq(438),anyString())).thenReturn(search(0));
        var restarted=engine(settings(false,List.of(),"A"));restarted.tick("fixture");
        verify(pixiv).search(eq("A"),any(),any(),eq(438),anyString());
    }
    @Test void crashAfterExistingFileCommitDoesNotFetchOrDownloadAgain() throws Exception {
        var folder=dir.resolve("novel-101");java.nio.file.Files.createDirectories(folder);java.nio.file.Files.writeString(folder.resolve("book.txt"),"saved body");
        var existing=new top.sywyar.pixivdownload.novel.db.NovelRecord(101,"saved",folder.toString(),1,"txt",1,null,false,null,"",null,null,null,null,
                null,null,null,null,false,"ja","saved body",null);
        when(novels.getNovel(101)).thenReturn(existing);
        var pending=node("PENDING").put("resolved",true);pending.putArray("tags").add("allowed");store.put("work","101",pending);
        var restarted=engine(settings(false,List.of(),"A"));restarted.tick("fixture");
        verifyNoInteractions(pixiv,downloads);assertThat(store.get("work","101").path("state").asText()).isEqualTo("COMPLETED");
    }
    @Test void shortSeriesKeepsSevenAndNeverRequestsAnotherDirectoryPage() {
        when(pixiv.search(anyString(),any(),any(),anyInt(),anyString())).thenReturn(search(1,7));
        when(pixiv.novel(anyLong(),anyString())).thenAnswer(a->body(a.getArgument(0),88L,"allowed"));
        when(pixiv.series(anyLong(),anyInt(),anyInt(),anyString())).thenReturn(series(7));
        var engine=engine(settings(true,List.of(),"A"));for(int i=0;i<20;i++)engine.tick("fixture");
        assertThat(store.get("dry-series","88").path("chapters").size()).isEqualTo(7);
        verify(pixiv,times(1)).series(anyLong(),anyInt(),anyInt(),anyString());
    }
    @Test void repeated403PausesWithoutBusyRetry() {
        when(pixiv.search(anyString(),any(),any(),anyInt(),anyString())).thenThrow(new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS,403));
        var engine=engine(settings(false,List.of(),"A"));
        for(int i=0;i<3;i++) {engine.tick("fixture");var gate=store.get("control","network");gate.put("due",0);store.put("control","network",gate);}
        assertThat(engine.tick("fixture")).isFalse();assertThat(store.get("control","network").path("state").asText()).isEqualTo("AUTH_REQUIRED");
        verify(pixiv,times(3)).search(anyString(),any(),any(),anyInt(),anyString());
    }

    @Test void oversizedRangeSplitsAndCappedSingleDayReportsIncomplete() {
        var config=new ArchiveSettings(true,true,List.of("A"),List.of(),10,1,1,1,"2026-01-01",false);
        when(pixiv.search(anyString(),any(),any(),anyInt(),anyString())).thenReturn(search(100,101));
        var engine=engine(config);engine.tick("fixture");
        assertThat(store.get("dry-job","A").path("remaining").size()).isEqualTo(1);
    }
}
