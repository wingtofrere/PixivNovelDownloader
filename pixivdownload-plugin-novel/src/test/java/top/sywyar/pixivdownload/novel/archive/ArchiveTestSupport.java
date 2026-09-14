package top.sywyar.pixivdownload.novel.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.List;

final class ArchiveTestSupport {
    static final ObjectMapper JSON=new ObjectMapper();
    static SQLiteDataSource source(Path path) throws Exception {
        SQLiteDataSource ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+path);
        try(var c=ds.getConnection();var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS novel_archive_state(kind TEXT NOT NULL,entry_key TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 1,state TEXT NOT NULL DEFAULT 'PENDING',due INTEGER NOT NULL DEFAULT 0,payload TEXT NOT NULL,PRIMARY KEY(kind,entry_key))");
        }
        return ds;
    }
    static ObjectNode node(String state) {return JSON.createObjectNode().put("state",state).put("due",0);}
    static ArchiveSettings settings(boolean dry,List<String> excluded,String...tags) {
        return new ArchiveSettings(true,dry,List.of(tags),excluded,10,1,1,1000,"2026-01-01",true);
    }
    static ObjectNode body(long id,Long series,String...tags) {
        ObjectNode body=JSON.createObjectNode().put("id",id).put("title","Novel "+id).put("content","body "+id+"[uploadedimage:99]").put("userId","100");
        var array=body.putObject("tags").putArray("tags");for(String tag:tags)array.addObject().put("tag",tag);
        if(series!=null)body.putObject("seriesNavData").put("seriesId",series).put("order",id).put("title","Series");
        return body;
    }
    static ObjectNode search(long total,long...ids) {
        ObjectNode root=JSON.createObjectNode();var result=root.putObject("novel").put("total",total);var items=result.putArray("data");
        for(long id:ids)items.addObject().put("id",id);return root;
    }
    static ObjectNode series(int count) {
        var root=JSON.createObjectNode();var items=root.putObject("page").putArray("seriesContents");
        for(int i=1;i<=count;i++)items.addObject().put("id",i).putObject("series").put("contentOrder",i);
        return root;
    }
}
