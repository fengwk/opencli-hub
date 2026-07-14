package fun.fengwk.openclihub.core;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.share.util.HubIds;
import org.junit.jupiter.api.Test;

class HubIdsTest {

    /** Generated UUIDs and legacy signed-BIGINT values are the two supported identity forms. */
    @Test
    void shouldAcceptCanonicalUuidAndMigratedPositiveLong() {
        assertThat(HubIds.isSupported("6f59e726-bdb3-4b32-b5e4-95e070a1e87b")).isTrue();
        assertThat(HubIds.isSupported("343020517415976960")).isTrue();
        assertThat(HubIds.isSupported(Long.toString(Long.MAX_VALUE))).isTrue();
    }

    /** Rejected forms must never become automatically managed filesystem names. */
    @Test
    void shouldRejectIdentifiersOutsideManagedCompatibilityBoundary() {
        assertThat(HubIds.isSupported(null)).isFalse();
        assertThat(HubIds.isSupported("")).isFalse();
        assertThat(HubIds.isSupported("0")).isFalse();
        assertThat(HubIds.isSupported("-1")).isFalse();
        assertThat(HubIds.isSupported("01")).isFalse();
        assertThat(HubIds.isSupported("9223372036854775808")).isFalse();
        assertThat(HubIds.isSupported("6F59E726-BDB3-4B32-B5E4-95E070A1E87B")).isFalse();
        assertThat(HubIds.isSupported("not-an-id")).isFalse();
    }
}
