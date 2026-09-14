package top.sywyar.pixivdownload.novel.archive;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.SmartLifecycle;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** One background worker with a stable runtime lock. Closing the browser never stops this worker. */
public final class ArchiveRuntime implements SmartLifecycle {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ArchiveRuntime.class);
    private final ArchiveStore store;
    private final ObjectMapper json;
    private final RuntimePathProvider paths;
    private final Function<ArchiveSettings,ArchiveEngine> factory;
    private volatile Thread worker;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile String credential;
    private volatile String status="DISABLED";
    private ArchiveSettings settings;
    private ArchiveEngine engine;
    private FileChannel lockChannel;
    private FileLock lock;

    public ArchiveRuntime(ArchiveStore store,ObjectMapper json,RuntimePathProvider paths,Function<ArchiveSettings,ArchiveEngine> factory) {
        this.store=store;this.json=json;this.paths=paths;this.factory=factory;
    }
    public Path configPath() {return paths.configFile("json").resolveSibling("novel-archive.json");}
    private ArchiveSettings readSettings() {
        try {
            ObjectNode defaults=json.valueToTree(ArchiveSettings.defaults());
            if(Files.exists(configPath())) {
                JsonNode input=json.readTree(configPath().toFile());
                if(!input.isObject()) throw new IllegalArgumentException("Archive configuration must be an object");
                input.fieldNames().forEachRemaining(key->{if(!defaults.has(key)) throw new IllegalArgumentException("Unknown archive setting: "+key);});
                defaults.setAll((ObjectNode)input);
            }
            return json.treeToValue(defaults,ArchiveSettings.class);
        } catch(java.io.IOException e) {throw new IllegalStateException("Invalid novel-archive.json",e);}
    }
    @Override public synchronized void start() {
        if(running)return;
        if(worker!=null && worker.isAlive()) throw new IllegalStateException("Previous archive worker is still stopping");
        settings=readSettings();
        if(!settings.enabled()) {status="DISABLED";return;}
        try {
            Files.createDirectories(paths.stateDirectory());
            lockChannel=FileChannel.open(paths.stateDirectory().resolve("archive.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock=lockChannel.tryLock();
            if(lock==null) throw new IllegalStateException("Another archive worker owns this runtime directory");
            engine=factory.apply(settings);engine.initialize();
            credential=System.getenv("PIXIV_ARCHIVE_COOKIE");
            ObjectNode control=store.get("control","runtime"); paused=control!=null && control.path("paused").asBoolean();
            running=true;
            worker=new Thread(this::run,"pixiv-novel-archive");worker.setDaemon(true);worker.start();
        } catch(Exception e) {releaseLock();status="START_FAILED";throw new IllegalStateException("Archive startup failed; progress retained",e);}
    }
    private void run() {
        try {
            long previous=System.currentTimeMillis();
            while(running) {
                long now=System.currentTimeMillis();
                if(now-previous>60_000) sleep(20_000); // Resume settling; all persisted due times are wall-clock based.
                if(paused) status="PAUSED";
                else if(credential==null || credential.isBlank()) status="AUTH_REQUIRED";
                else {status=engine.tick(credential)?"RUNNING":"IDLE_OR_WAITING";}
                previous=System.currentTimeMillis();sleep(1000);
            }
        } catch(CancellationException ignored) {status="STOPPED";}
        catch(Exception e) {status="FAILED_PROGRESS_RETAINED";LOG.error("archive worker stopped; progress retained; errorType={}",e.getClass().getSimpleName());}
        finally {running=false;credential=null;releaseLock();}
    }
    public synchronized void pause() {
        paused=true;store.put("control","runtime",json.createObjectNode().put("paused",true));
    }
    /** A new credential is held in memory only; restarting can obtain it from the inherited environment. */
    public synchronized void resume(String cookie) {
        if(!running) start();
        if(!running) throw new IllegalStateException("Enable archive in novel-archive.json first");
        if(cookie!=null && !cookie.isBlank()) {
            if(cookie.length()>16384 || cookie.contains("\r") || cookie.contains("\n")) throw new IllegalArgumentException("Invalid credential");
            credential=cookie; engine.resumeAuthentication();
        }
        paused=false;store.put("control","runtime",json.createObjectNode().put("paused",false));
    }
    public void retryFailed() {
        if(settings==null) throw new IllegalStateException("Archive is not initialized");
        store.retryFailed(settings.dryRun()?"dry-":"");
    }
    public Map<String,Object> status() {
        Map<String,Object> values=new LinkedHashMap<>();values.put("status",status);values.put("running",running);
        values.put("paused",paused);values.put("dryRun",settings==null || settings.dryRun());
        values.put("counts",store.counts());
        String prefix=settings!=null && settings.dryRun()?"dry-":"";
        values.put("network",store.get(prefix+"control","network"));
        if(settings!=null) {
            Map<String,Object> jobs=new LinkedHashMap<>();for(String tag:settings.tags()) jobs.put(tag,store.get(prefix+"job",tag));
            values.put("jobs",jobs);
        }
        return values;
    }
    @Override public void stop() {
        running=false;Thread current=worker;
        if(current!=null) {
            current.interrupt();
            try {current.join(30_000);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
        }
    }
    @Override public boolean isRunning() {return running;}
    @Override public int getPhase() {return Integer.MAX_VALUE-100;}
    private synchronized void releaseLock() {
        try {if(lock!=null)lock.release();} catch(java.io.IOException ignored) {}
        try {if(lockChannel!=null)lockChannel.close();} catch(java.io.IOException ignored) {}
        lock=null;lockChannel=null;
    }
    public static void sleep(long millis) {
        try {Thread.sleep(millis);} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new CancellationException();}
    }
}
