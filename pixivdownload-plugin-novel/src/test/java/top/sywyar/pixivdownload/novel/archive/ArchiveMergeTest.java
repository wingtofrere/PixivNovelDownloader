package top.sywyar.pixivdownload.novel.archive;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.novel.db.*;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveMergeTest {
    @TempDir Path dir;
    NovelDatabase db;NovelMergeService merger;
    @BeforeEach void setup() {
        db=mock(NovelDatabase.class);var config=mock(DownloadSettings.class);when(config.getRootFolder()).thenReturn(dir.toString());
        var query=mock(WorkQueryService.class);when(query.authorNames(anyList())).thenReturn(Map.of());
        when(db.getSeries(88)).thenReturn(new NovelSeries(88,"Series",null,0,"",null,null));
        merger=new NovelMergeService(config,db,query,NovelTestMessages.messageResolver());
    }
    NovelRecord chapter(long id) {
        return new NovelRecord(id,"Chapter "+id,dir.toString(),1,"epub",1,null,false,null,"",null,null,88L,id,
                null,null,null,null,false,"ja","body "+id+"[uploadedimage:99]",null);
    }
    @Test void explicitSelectionCannotLeakOldChapterOrImagesAndKeepsOrder() throws Exception {
        when(db.getNovel(1)).thenReturn(chapter(1));when(db.getNovel(2)).thenReturn(chapter(2));
        var txt=merger.mergeArchive(88,List.of(2L,1L),NovelFormat.TXT);
        String content=Files.readString(Path.of(txt.mergedPath()));assertThat(content.indexOf("Chapter 2")).isLessThan(content.indexOf("Chapter 1"));
        var epub=merger.mergeArchive(88,List.of(2L,1L),NovelFormat.EPUB);
        try(var zip=new ZipFile(epub.mergedPath())) {assertThat(zip.stream().map(e->e.getName())).noneMatch(n->n.contains("embed_"));}
        verify(db,never()).getNovelsBySeriesId(anyLong());verify(db,never()).getNovel(11);
    }
    @Test void failedRebuildPreservesExistingEdition() throws Exception {
        when(db.getNovel(1)).thenReturn(chapter(1));var result=merger.mergeArchive(88,List.of(1L),NovelFormat.TXT);
        byte[] previous=Files.readAllBytes(Path.of(result.mergedPath()));
        assertThatThrownBy(()->merger.mergeArchive(88,List.of(1L,2L),NovelFormat.TXT)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(Path.of(result.mergedPath()))).isEqualTo(previous);
    }
    @Test void allExcludedRetainsPreviousEditionButRemovesItFromCurrentOutput() throws Exception {
        when(db.getNovel(1)).thenReturn(chapter(1));var result=merger.mergeArchive(88,List.of(1L),NovelFormat.TXT);
        merger.writeArchiveManifest(88,"{\"state\":\"EMPTY\"}",true);
        assertThat(Path.of(result.mergedPath())).doesNotExist();
        try(var files=Files.list(Path.of(result.mergedPath()).getParent())) {assertThat(files.map(p->p.getFileName().toString())).anyMatch(n->n.startsWith("previous-policy-"));}
    }
}
