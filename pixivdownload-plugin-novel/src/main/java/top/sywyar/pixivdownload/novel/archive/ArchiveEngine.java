package top.sywyar.pixivdownload.novel.archive;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.novel.db.*;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.request.*;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Bounded, restartable archive steps. The journal, not this object's lifetime, owns progress. */
public final class ArchiveEngine {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ArchiveEngine.class);
    private final ArchiveStore store;
    private final ObjectMapper json;
    private final ArchiveSettings settings;
    private final ArchivePixiv pixiv;
    private final NovelDatabase novels;
    private final NovelDownloadService downloader;
    private final NovelMergeService merger;
    private final Runnable requestDelay;
    private final String prefix;
    private String cookie;

    public ArchiveEngine(ArchiveStore store,ObjectMapper json,ArchiveSettings settings,ArchivePixiv pixiv,
                         NovelDatabase novels,NovelDownloadService downloader,NovelMergeService merger,Runnable requestDelay) {
        this.store=store;this.json=json;this.settings=settings;this.pixiv=pixiv;this.novels=novels;
        this.downloader=downloader;this.merger=merger;this.requestDelay=requestDelay;
        prefix=settings.dryRun()?"dry-":"";
    }

    public void initialize() {
        store.verifyVersion();
        ObjectNode policy=json.createObjectNode();
        policy.set("tags",json.valueToTree(settings.excludedTags().stream().map(ArchiveSettings::normalize).sorted().toList()));
        ObjectNode previous=store.get(prefix+"control","policy");
        if(previous!=null && !previous.path("tags").equals(policy.path("tags"))) store.recheck(prefix);
        store.put(prefix+"control","policy",policy);
        for(String tag:settings.tags()) {
            ObjectNode saved=store.get(prefix+"job",tag);
            if(saved!=null) {
                if(saved.path("state").asText().equals("PAUSED")) {saved.put("state","PENDING");store.put(prefix+"job",tag,saved);}
                continue;
            }
            ObjectNode job=node("PENDING"); job.put("page",1); job.put("tag",tag);
            job.put("from",settings.since()); job.put("to",LocalDate.now().toString()); job.putArray("remaining");
            store.put(prefix+"job",tag,job);
        }
    }

    /** One step can be called again after a crash. No network work runs while the cooldown is active. */
    public boolean tick(String credential) {
        cookie=credential;
        ObjectNode gate=store.get(prefix+"control","network");
        if(gate!=null && (gate.path("state").asText().equals("AUTH_REQUIRED") || gate.path("due").asLong()>System.currentTimeMillis())) {cookie=null;return false;}
        ArchiveStore.Entry entry=store.next(prefix+"work",System.currentTimeMillis(),"PENDING","RETRY_WAIT");
        if(entry==null) entry=store.next(prefix+"series",System.currentTimeMillis(),"DIRTY");
        if(entry==null) {
            // Backpressure: finish/retry the admitted page before requesting another search page.
            if(store.next(prefix+"work",Long.MAX_VALUE,"RETRY_WAIT")!=null) {cookie=null;return false;}
            entry=store.next(prefix+"job",System.currentTimeMillis(),"PENDING","RETRY_WAIT");
        }
        if(entry==null) {cookie=null;return false;}
        try {
            if(entry.kind().endsWith("work")) work(entry);
            else if(entry.kind().endsWith("series")) merge(entry);
            else search(entry);
            store.put(prefix+"control","network",node("READY"));
            return true;
        } catch(CancellationException e) {throw e;}
        catch(PixivAjaxException e) { networkFailure(entry,e);return true; }
        catch(Exception e) {
            ObjectNode value=entry.data(); int attempts=value.path("attempts").asInt()+1;
            value.put("attempts",attempts).put("state",attempts>=8?"FAILED":"RETRY_WAIT")
                    .put("reason",e instanceof java.io.IOException ? "LOCAL_IO_ERROR":"INVALID_RESPONSE_OR_EXECUTION")
                    .put("due",System.currentTimeMillis()+ArchiveRetry.delay(0,attempts,0));
            // Series exports use DIRTY for retries; failure remains visible after eight attempts.
            if(entry.kind().endsWith("series") && attempts<8) value.put("state","DIRTY");
            store.put(entry.kind(),entry.key(),value);
            LOG.warn("archive kind={} id={} state={} attempts={} reason={}",entry.kind(),entry.key(),
                    value.path("state").asText(),attempts,value.path("reason").asText());
            return true;
        } finally {cookie=null;}
    }

    private JsonNode request(Supplier<JsonNode> action) {
        requestDelay.run();
        if(Thread.currentThread().isInterrupted()) throw new CancellationException();
        JsonNode result=action.get();
        if(Thread.currentThread().isInterrupted()) throw new CancellationException();
        return result;
    }

    private void search(ArchiveStore.Entry entry) {
        ObjectNode job=entry.data(); String tag=job.path("tag").asText();
        if(!settings.tags().contains(tag)) {job.put("state","PAUSED");store.put(entry.kind(),entry.key(),job);return;}
        LocalDate from=LocalDate.parse(job.path("from").asText()), to=LocalDate.parse(job.path("to").asText());
        int page=job.path("page").asInt(1);
        JsonNode body=request(()->pixiv.search(tag,from,to,page,cookie));
        JsonNode result=body.path("novel");
        if(!result.isObject() || !result.path("data").isArray() || !result.path("total").canConvertToLong())
            throw new IllegalStateException("Invalid novel search page");
        job.put("state","PENDING").put("due",0).put("attempts",0);
        JsonNode items=result.path("data"); long total=result.path("total").asLong();
        if(page==1) job.put("pageSize",Math.max(1,items.size()));
        int pageSize=job.path("pageSize").asInt(24);
        if(items.isEmpty() && total>(long)(page-1)*pageSize) job.put("coverageIncomplete",true);
        if(page==1 && items.size()>0 && total>(long)settings.maxPages()*items.size()) {
            if(from.isBefore(to)) {
                LocalDate middle=from.plusDays(java.time.temporal.ChronoUnit.DAYS.between(from,to)/2);
                ((ArrayNode)job.withArray("remaining")).add(json.createObjectNode().put("from",from.toString()).put("to",middle.toString()));
                job.put("from",middle.plusDays(1).toString());
                store.put(entry.kind(),entry.key(),job); return;
            }
            job.put("coverageIncomplete",true);
        }
        List<ArchiveStore.Entry> writes=new ArrayList<>();
        for(JsonNode item:items) {
            long id=item.path("id").asLong(0);
            if(id<=0) throw new IllegalStateException("Invalid novel search id");
            writes.add(new ArchiveStore.Entry(prefix+"work",Long.toString(id),node("PENDING").put("novelId",id)));
        }
        job.put("scanned",job.path("scanned").asLong()+items.size());
        if(items.isEmpty() || (long)page*pageSize>=total || page>=settings.maxPages()) {
            if(page>=settings.maxPages() && (long)page*pageSize<total) job.put("coverageIncomplete",true);
            ArrayNode ranges=job.withArray("remaining");
            if(ranges.isEmpty()) job.put("state",job.path("coverageIncomplete").asBoolean()?"COVERAGE_INCOMPLETE":"COMPLETED");
            else {
                JsonNode range=ranges.remove(ranges.size()-1);
                job.put("from",range.path("from").asText()).put("to",range.path("to").asText()).put("page",1);
            }
        } else job.put("page",page+1);
        writes.add(new ArchiveStore.Entry(entry.kind(),entry.key(),job));
        store.admit(writes);
    }

    private void work(ArchiveStore.Entry entry) throws Exception {
        long id=Long.parseLong(entry.key()); ObjectNode work=entry.data();
        NovelRecord existing=novels.getNovel(id);
        // Reuse already validated metadata after upgrades/global policy edits; content stays in the existing novel table.
        if(work.path("resolved").asBoolean() && work.path("tags").isArray() && existing!=null && !existing.deleted()) {
            if(excluded(work)) {finish(entry,"SKIPPED_EXCLUDED_TAG");return;}
            if(validFiles(existing)) {finish(entry,"COMPLETED");return;}
        }
        JsonNode body=request(()->pixiv.novel(id,cookie));
        if(!body.path("tags").path("tags").isArray() || !body.path("content").isTextual())
            throw new IllegalStateException("Novel tags/content unavailable");
        PixivNovelMetadata metadata=PixivNovelMetadata.parse(id,body);
        work.set("tags",json.valueToTree(metadata.tags().stream().map(WorkTag::name).toList()));
        if(metadata.seriesId()!=null) {
            long seriesId=metadata.seriesId(); work.put("seriesId",seriesId);
            ObjectNode series=store.get(prefix+"series",Long.toString(seriesId));
            if(series==null) {resolveSeries(seriesId);return;}
            if(!contains(series.path("chapters"),id)) {finish(entry,"SKIPPED_SERIES_LIMIT");return;}
        }
        work.put("resolved",true);
        if(excluded(work)) {finish(entry,"SKIPPED_EXCLUDED_TAG");return;}
        if(settings.dryRun()) {finish(entry,"DRY_RUN");return;}
        if(existing!=null && !existing.deleted() && validFiles(existing)) {finish(entry,"COMPLETED");return;}
        // Save metadata before the side effect. Crash after file/database completion is recovered without another download.
        work.put("state","PENDING"); store.put(entry.kind(),entry.key(),work);
        NovelDownloadRequest download=NovelDownloadRequestFactory.fromPixiv(metadata,null,cookie,null);
        download.getOther().setSkipEmbeddedImages(true);
        download.getOther().setEmbeddedImages(Map.of());
        download.getOther().setImageRequestDelayMs(randomDelayMillis());
        if(!settings.downloadCover()) download.getOther().setCoverUrl(null);
        download.getOther().setFormat("epub");
        if(!downloader.downloadBlocking(download,null)) throw new java.io.IOException("Novel download incomplete");
        finish(entry,"COMPLETED");
    }

    private boolean excluded(ObjectNode work) {
        List<String> tags=new ArrayList<>(); work.path("tags").forEach(t->tags.add(t.asText()));
        List<String> matched=settings.matches(tags); work.set("matchedTags",json.valueToTree(matched));
        return !matched.isEmpty();
    }

    private void resolveSeries(long id) {
        List<Long> selected=new ArrayList<>(); int last=0;
        while(selected.size()<settings.maxChapters()) {
            final int offset=last;
            JsonNode body=request(()->pixiv.series(id,offset,30,cookie));
            JsonNode items=body.path("page").path("seriesContents");
            if(!items.isArray()) throw new IllegalStateException("Invalid series directory");
            int previous=last;
            for(JsonNode item:items) {
                int order=item.path("series").path("contentOrder").asInt(0);
                long novelId=item.path("id").asLong(0);
                if(order<=previous || novelId<=0 || selected.contains(novelId)) throw new IllegalStateException("Invalid series order");
                previous=order; selected.add(novelId);
                if(selected.size()==settings.maxChapters()) break;
            }
            if(items.size()<30 || selected.size()==settings.maxChapters()) break;
            last=previous;
        }
        if(selected.isEmpty()) throw new IllegalStateException("Series directory unavailable");
        ObjectNode snapshot=node("DIRTY"); snapshot.put("seriesId",id).put("maxChapters",settings.maxChapters());
        snapshot.set("chapters",json.valueToTree(selected));
        List<ArchiveStore.Entry> writes=new ArrayList<>();
        for(long novelId:selected) writes.add(new ArchiveStore.Entry(prefix+"work",Long.toString(novelId),
                node("PENDING").put("novelId",novelId).put("seriesId",id)));
        writes.add(new ArchiveStore.Entry(prefix+"series",Long.toString(id),snapshot)); store.admit(writes);
    }

    private void finish(ArchiveStore.Entry entry,String state) {
        ObjectNode work=entry.data(); work.put("state",state).put("due",0).put("attempts",0);
        List<ArchiveStore.Entry> writes=new ArrayList<>(); writes.add(entry);
        String seriesId=work.path("seriesId").asText("");
        ObjectNode series=store.get(prefix+"series",seriesId);
        if(series!=null) {series.put("state","DIRTY").put("due",0);writes.add(new ArchiveStore.Entry(prefix+"series",seriesId,series));}
        store.batch(writes,false);
        LOG.info("archive novel={} state={}",entry.key(),state);
    }

    private void merge(ArchiveStore.Entry entry) throws Exception {
        ObjectNode series=entry.data(); List<Long> allowed=new ArrayList<>(); ArrayNode skipped=json.createArrayNode();
        for(JsonNode id:series.path("chapters")) {
            ObjectNode work=store.get(prefix+"work",id.asText()); String state=work==null?"PENDING":work.path("state").asText();
            if(state.equals("PENDING") || state.equals("RETRY_WAIT") || state.equals("FAILED")) {
                series.put("due",System.currentTimeMillis()+30_000);store.put(entry.kind(),entry.key(),series);return;
            }
            if(state.equals("COMPLETED") || state.equals("DRY_RUN")) allowed.add(id.asLong());
            else skipped.add(json.createObjectNode().put("novelId",id.asLong()).put("reason",state)
                    .set("matchedTags",work.path("matchedTags")));
        }
        series.set("excludedChapters",skipped); series.set("includedChapters",json.valueToTree(allowed));
        if(!settings.dryRun() && !allowed.isEmpty()) {
            for(var format:List.of(NovelDownloadService.NovelFormat.TXT,NovelDownloadService.NovelFormat.EPUB)) {
                var result=merger.mergeArchive(Long.parseLong(entry.key()),allowed,format);
                if(!result.success()) throw new java.io.IOException("Archive merge incomplete");

            }
        }
        series.put("state",settings.dryRun()?"DRY_RUN":allowed.isEmpty()?"EMPTY":"COMPLETED").put("due",0);
        if(!settings.dryRun()) merger.writeArchiveManifest(Long.parseLong(entry.key()),series.toPrettyString(),allowed.isEmpty());
        store.put(entry.kind(),entry.key(),series);
    }

    private boolean validFiles(NovelRecord record) {
        if(record.rawContent()==null || record.folder()==null) return false;
        try(var files=Files.list(Path.of(record.folder()))) {
            return files.anyMatch(p->{
                if(!Files.isRegularFile(p))return false;
                String name=p.getFileName().toString();
                if(name.endsWith(".epub"))return validEpub(p);
                try {return (name.endsWith(".txt") || name.endsWith(".html")) && Files.size(p)>0;}
                catch(java.io.IOException e){return false;}
            });
        } catch(Exception e) {return false;}
    }
    private static boolean validEpub(Path file) {
        try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(file.toFile())) {
            if(zip.getEntry("mimetype")==null || zip.getEntry("META-INF/container.xml")==null)return false;
            var entries=zip.entries();
            while(entries.hasMoreElements())try(var data=zip.getInputStream(entries.nextElement())) {data.transferTo(java.io.OutputStream.nullOutputStream());}
            return true;
        } catch(java.io.IOException e) {return false;}
    }
    private static boolean contains(JsonNode values,long id) {for(JsonNode value:values) if(value.asLong()==id)return true;return false;}
    private ObjectNode node(String state) {return json.createObjectNode().put("state",state).put("due",0);}
    public int randomDelayMillis() {return java.util.concurrent.ThreadLocalRandom.current()
            .nextInt(settings.minDelaySeconds()*1000,settings.maxDelaySeconds()*1000+1);}

    private void networkFailure(ArchiveStore.Entry entry,PixivAjaxException error) {
        int status=error.statusCode();
        if(entry.kind().endsWith("work") && (status==404 || status==410)) {finish(entry,"SKIPPED_UNAVAILABLE");return;}
        ObjectNode gate=store.get(prefix+"control","network"); if(gate==null) gate=node("WAITING_NETWORK");
        int failures=gate.path("failures").asInt()+1;
        long delay=ArchiveRetry.delay(status,failures,error.retryAfterMillis());
        gate.put("failures",failures).put("status",status).put("due",System.currentTimeMillis()+delay)
                .put("state",ArchiveRetry.requiresLogin(status,failures)?"AUTH_REQUIRED":"WAITING_NETWORK");
        store.put(prefix+"control","network",gate);
        LOG.warn("archive network status={} state={} retryAt={}",status,gate.path("state").asText(),gate.path("due").asLong());
    }
    public void resumeAuthentication() {store.put(prefix+"control","network",node("READY"));}
}
