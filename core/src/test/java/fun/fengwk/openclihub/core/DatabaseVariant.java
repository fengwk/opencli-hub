package fun.fengwk.openclihub.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;

/**
 * Resolves the active Maven database variant from the packaged, Maven-filtered
 * {@code auto-mapper.config}. The filter substitutes the profile's {@code dbType} value, so
 * the classpath artifact itself is the source of truth for which variant this build selected.
 */
final class DatabaseVariant {

    private static final String DB_TYPE_KEY = "fun.fengwk.automapper.annotation.AutoMapper.dbType";

    private DatabaseVariant() {
    }

    /** {@code POSTGRESQL}, {@code MYSQL} or {@code SQLITE}, as compiled into auto-mapper.config. */
    static String dbType() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("auto-mapper.config").getInputStream()) {
            properties.load(in);
        }
        String dbType = properties.getProperty(DB_TYPE_KEY);
        if (dbType == null) {
            throw new IllegalStateException("auto-mapper.config is missing " + DB_TYPE_KEY);
        }
        return dbType;
    }

    /** {@code postgresql}, {@code mysql} or {@code sqlite}: the variant used by the build. */
    static String variant() throws IOException {
        return dbType().toLowerCase(java.util.Locale.ROOT);
    }
}
