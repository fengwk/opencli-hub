package fun.fengwk.openclihub.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Verifies that the MySQL profile selects the MySQL Spring SQL initialization resources. */
class MysqlSqlInitializationProfileTest {

    @Test
    void shouldInitializeCurrentMysqlSchemaAndDataWithSpring() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-mysql.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
            .containsEntry("spring.sql.init.mode", "always")
            .containsEntry("spring.sql.init.schema-locations", "classpath:schema-mysql.sql")
            .containsEntry("spring.sql.init.data-locations", "classpath:data-mysql.sql");
    }
}
