package top.sywyar.pixivdownload.novel.archive;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.novel.archive.ArchiveTestSupport.*;
class ArchivePolicyTest {
    @Test void globalBlacklistIsExactCaseInsensitiveAndUnicodeNormalized() {
        var policy=settings(false,List.of("BL","AI生成"),"A","B");
        assertThat(policy.matches(List.of("BLAH","AI生成物ではない"))).isEmpty();
        assertThat(policy.matches(List.of("ｂｌ","allowed"))).containsExactly("BL");
        assertThat(policy.matches(List.of("AI生成"))).containsExactly("AI生成");
    }
    @Test void invalidLimitsCannotEnableBusyLoop() {
        assertThatThrownBy(()->new ArchiveSettings(true,false,List.of("a"),List.of(),10,0,0,1000,"2026-01-01",true))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retryNeverUndercutsServerCooldownAndRepeated403Pauses() {
        assertThat(ArchiveRetry.delay(429,1,7_200_000)).isEqualTo(7_200_000);
        assertThat(ArchiveRetry.delay(0,100,0)).isEqualTo(3_600_000);
        assertThat(ArchiveRetry.requiresLogin(403,3)).isTrue();
        assertThat(ArchiveRetry.requiresLogin(0,999)).isFalse();
        assertThat(ArchiveRetry.requiresLogin(401,1)).isTrue();
    }
}
