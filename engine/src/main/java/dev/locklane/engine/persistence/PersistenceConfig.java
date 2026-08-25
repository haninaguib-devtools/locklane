package dev.locklane.engine.persistence;

import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class PersistenceConfig {

    /**
     * A user-defined DataSource bean takes over from Boot's own DataSource
     * auto-configuration entirely — no {@code spring.datasource.url} needed.
     * The directory is created up front: SQLite's driver does not create a missing
     * parent directory for you, only the database file inside it.
     */
    @Bean
    public DataSource dataSource(@Value("${locklane.data-dir}") String dataDir) throws IOException {
        Path dir = Path.of(dataDir);
        Files.createDirectories(dir);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("locklane.db"));
        return dataSource;
    }
}
