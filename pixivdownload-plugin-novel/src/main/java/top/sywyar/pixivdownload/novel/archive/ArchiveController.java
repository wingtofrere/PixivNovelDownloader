package top.sywyar.pixivdownload.novel.archive;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Endpoints are declared admin-only by NovelPlugin. Responses never include credentials. */
@RestController
@RequestMapping("/api/novel/archive")
public final class ArchiveController {
    private final ArchiveRuntime runtime;
    public ArchiveController(ArchiveRuntime runtime) {this.runtime=runtime;}
    @GetMapping("/status") public Map<String,Object> status() {return runtime.status();}
    @PostMapping("/retry-failed") public Map<String,Object> retryFailed() {runtime.retryFailed();return status();}
    @PostMapping("/pause") public Map<String,Object> pause() {runtime.pause();return status();}
    @PostMapping("/resume") public Map<String,Object> resume(
            @RequestHeader(value="X-Pixiv-Cookie",required=false) String cookie) {runtime.resume(cookie);return status();}
}
