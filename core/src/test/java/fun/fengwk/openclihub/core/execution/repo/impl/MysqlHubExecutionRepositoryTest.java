package fun.fengwk.openclihub.core.execution.repo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.openclihub.core.execution.repo.impl.mapper.HubExecutionMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies repository-specific pagination behavior that cannot rely on int offset arithmetic. */
class MysqlHubExecutionRepositoryTest {

    /** The largest valid page must reach MyBatis as its exact positive long offset, not page zero. */
    @Test
    void shouldUseLongOffsetForLargestPageNumber() {
        HubExecutionMapper mapper = mock(HubExecutionMapper.class);
        when(mapper.pageAllOrderByQueuedAtDescIdDesc(anyLong(), anyInt())).thenReturn(List.of());
        MysqlHubExecutionRepository repository = new MysqlHubExecutionRepository(
            mapper, new ObjectMapper());

        repository.page(new PageQuery(Integer.MAX_VALUE, 1000), null);

        ArgumentCaptor<Long> offsetCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mapper).pageAllOrderByQueuedAtDescIdDesc(offsetCaptor.capture(), eq(1000));
        assertThat(offsetCaptor.getValue())
            .isEqualTo(2_147_483_646_000L)
            .isPositive();
    }

}
