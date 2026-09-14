package top.sywyar.pixivdownload.novel.archive;

import com.fasterxml.jackson.databind.*;
import org.springframework.web.util.UriComponentsBuilder;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;

/** Thin adapter over the existing credential-aware Pixiv HTTP transport. */
public class ArchivePixiv {
    private final PixivAjaxClient client;
    private final ObjectMapper json;
    public ArchivePixiv(PixivAjaxClient client,ObjectMapper json) {this.client=client;this.json=json;}
    public JsonNode novel(long id,String cookie) { return body(URI.create("https://www.pixiv.net/ajax/novel/"+id),cookie); }
    public JsonNode series(long id,int last,int limit,String cookie) {
        return body(URI.create("https://www.pixiv.net/ajax/novel/series_content/"+id+
                "?limit="+limit+"&last_order="+last+"&order_by=asc"),cookie);
    }
    public JsonNode search(String tag,LocalDate from,LocalDate to,int page,String cookie) {
        URI uri=UriComponentsBuilder.fromUriString("https://www.pixiv.net/ajax/search/novels/{tag}")
                .queryParam("word","{tag}").queryParam("order","date_d").queryParam("mode","all")
                .queryParam("s_mode","s_tag_full").queryParam("p",page).queryParam("gs",0)
                .queryParam("scd",from.toString()).queryParam("ecd",to.toString())
                .buildAndExpand(Map.of("tag",tag)).encode().toUri();
        return body(uri,cookie);
    }
    private JsonNode body(URI uri,String cookie) {
        try {
            JsonNode root=json.readTree(client.get(uri,cookie));
            if(root!=null && root.path("error").asBoolean(false))
                throw new top.sywyar.pixivdownload.core.pixiv.PixivAjaxException(
                        top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure.HTTP_STATUS,403);
            if(root==null || !root.has("error") || !root.path("body").isObject())
                throw new IllegalStateException("Pixiv AJAX response unavailable or invalid");
            return root.path("body");
        } catch(java.io.IOException e) {throw new IllegalStateException("Invalid Pixiv JSON",e);}
    }
}
