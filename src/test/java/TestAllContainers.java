import com.mycompany.cache.Cache;
import com.mycompany.cache.RedisBackedCache;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.utility.TestcontainersConfiguration;
import redis.clients.jedis.Jedis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.rnorth.visibleassertions.VisibleAssertions.*;

/**
 * Integration test for Redis-backed cache implementation.
 */
public class TestAllContainers {

    @Rule
    public GenericContainer redis = new GenericContainer("redis:3.0.2")
                                            .withExposedPorts(6379);
    private Cache cache;


    @Rule
    public PostgreSQLContainer postgres = new PostgreSQLContainer();

    @ClassRule
    public static GenericContainer alpine =
        new GenericContainer("alpine:3.2")
            .withExposedPorts(80)
            .withEnv("MAGIC_NUMBER", "42")
            .withCopyFileToContainer(MountableFile.forClasspathResource("bashttpd"), "/tmp")
            .withCopyFileToContainer(MountableFile.forClasspathResource("bashttpd.conf"), "/tmp")
            .withCommand("/bin/sh", "-c",
                "apk update; apk add bash socat netcat-openbsd; chmod a+x /tmp/bashttpd; nohup socat TCP4-LISTEN:80,fork EXEC:/tmp/bashttpd");

    /**
     * Make some checks before actually running to ensure Windows compatibility
     *
     * https://coderwall.com/p/siqnjg/disable-tls-on-boot2docker
     * https://www.testcontainers.org/usage/windows_support.html
     * http://www.java-allandsundry.com/2018/05/testcontainers-and-spring-boot.html
     */
    public static void checkWindows() {
        if (System.getProperty("os.name").toLowerCase().indexOf("win") == 0) {
            Assert.assertNotNull("DOCKER_HOST must be set", System.getenv("DOCKER_HOST"));
            Assert.assertNull("When running on Windows DOCKER_TLS_VERIFY must be not set and no TLS used when communicating with docker", System.getenv("DOCKER_TLS_VERIFY"));
            Assert.assertTrue("When running on Windows DOCKER_HOST must point to 2375 non-TLS port", System.getenv("DOCKER_HOST").indexOf(":2375") > 0);

            TestcontainersConfiguration conf = TestcontainersConfiguration.getInstance();
            assertNotNull("Testcontainer configuration must be available", conf);
            assertTrue("You must disable checks (checks.disable=true) in testcontainers.properties", conf.isDisableChecks());
        }
    }

    static {
        // Run before testcontainers
        checkWindows();
    }

    @Before
    public void setUp() throws Exception {
        Jedis jedis = new Jedis(redis.getContainerIpAddress(), redis.getMappedPort(6379));
        cache = new RedisBackedCache(jedis, "test");
    }

    @Test
    public void testPostgres() throws SQLException  {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(postgres.getJdbcUrl());
        hikariConfig.setUsername(postgres.getUsername());
        hikariConfig.setPassword(postgres.getPassword());

        HikariDataSource ds = new HikariDataSource(hikariConfig);
        Statement statement = ds.getConnection().createStatement();
        statement.execute("SELECT 1");
        ResultSet resultSet = statement.getResultSet();

        resultSet.next();
        int resultSetInt = resultSet.getInt(1);
        assertEquals("A basic SELECT query succeeds", 1, resultSetInt);
    }

    @Test
    public void testAlpineAccessible() throws IOException  {
        URL url = new URL("http://" + alpine.getContainerIpAddress() + ":" + alpine.getMappedPort(80));
        System.out.println("Connecting to " + url);
        HttpURLConnection u = (HttpURLConnection) url.openConnection();
        Assert.assertNotNull("Connection established", u);
        try (InputStream is = u.getInputStream()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String magicNumber = in.readLine();
            Assert.assertEquals("Our magic number is matches what the get from HTTP server in docker container", "42", magicNumber);
        }
    }

    @Test
    public void testFindingAnInsertedValue() {
        cache.put("foo", "FOO");
        Optional<String> foundObject = cache.get("foo", String.class);

        assertTrue("When an object in the cache is retrieved, it can be found",
                        foundObject.isPresent());
        assertEquals("When we put a String in to the cache and retrieve it, the value is the same",
                        "FOO",
                        foundObject.get());
    }

    @Test
    public void testNotFindingAValueThatWasNotInserted() {
        Optional<String> foundObject = cache.get("bar", String.class);

        assertFalse("When an object that's not in the cache is retrieved, nothing is found",
                foundObject.isPresent());
    }
}
