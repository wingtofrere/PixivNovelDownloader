package top.sywyar.pixivdownload.novel.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.*;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;

/** Archive orchestration stays in the novel plugin and reuses the host's database and download path. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnPluginEnabled("novel")
public class ArchiveConfiguration {
    @Bean public ArchiveStore archiveStore(SqlSessionFactory sessions,ObjectMapper json) {
        return new ArchiveStore(sessions.getConfiguration().getEnvironment().getDataSource(),json);
    }
    @Bean public ArchiveRuntime archiveRuntime(ArchiveStore store,ObjectMapper json,RuntimePathProvider paths,
                                               PixivAjaxClient client,NovelDatabase novels,NovelDownloadService downloader,
                                               NovelMergeService merger) {
        return new ArchiveRuntime(store,json,paths,settings->new ArchiveEngine(store,json,settings,
                new ArchivePixiv(client,json),novels,downloader,merger,()->ArchiveRuntime.sleep(
                java.util.concurrent.ThreadLocalRandom.current().nextLong(settings.minDelaySeconds()*1000L,
                        settings.maxDelaySeconds()*1000L+1))));
    }
    @Bean public ArchiveController archiveController(ArchiveRuntime runtime) {return new ArchiveController(runtime);}
}
