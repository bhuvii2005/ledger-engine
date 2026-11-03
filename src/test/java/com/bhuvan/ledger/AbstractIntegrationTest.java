package com.bhuvan.ledger;

import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.redis.testcontainers.RedisContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Singleton container pattern: these are started once (manually, below) and
// reused by every subclass for the life of the JVM, instead of each
// subclass starting and stopping its own copy via @Container lifecycle hooks.
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    // Fixed host port: Redis-outage tests stop and start this same container, and Docker would
    // hand out a new random port on restart, leaving the app pointed at the old one.
    private static final int REDIS_HOST_PORT = freePort();

    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(REDIS_HOST_PORT), ExposedPort.tcp(6379))));

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_HOST_PORT);
        // lets tests count the SQL statements a request ran (zero for a Redis replay)
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener",
                () -> "WARN");
    }

    protected static void stopRedis() {
        DockerClientFactory.instance().client().stopContainerCmd(REDIS.getContainerId()).withTimeout(2).exec();
    }

    /** Starts Redis again and waits until the app's own client is talking to it. */
    protected static void startRedis(StringRedisTemplate redis, Duration cacheSkipWindow) {
        DockerClientFactory.instance().client().startContainerCmd(REDIS.getContainerId()).exec();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).ignoreExceptions()
                .until(() -> "PONG".equals(redis.execute(c -> c.ping(), true)));
        try {
            // let IdempotencyCache's "skip Redis after a failure" window run out
            Thread.sleep(cacheSkipWindow.toMillis() + 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
