
package com.ms.petopia.api.auth.service;

import com.ms.petopia.api.auth.domain.User;
import com.ms.petopia.api.auth.mapper.AuthMapper;
import com.ms.petopia.global.exception.CommonException;
import com.ms.petopia.global.exception.ErrorCode;
import com.ms.petopia.global.security.TokenHashUtil;
import com.ms.petopia.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real-Redis integration test, not a load benchmark.
 * Uses TokenService with mock DB/JWT dependencies so that Redis GETDEL and
 * refresh-token consumption are tested without starting the full application.
 * Requires Docker and the org.testcontainers:junit-jupiter test dependency.
 */
@Testcontainers
class RefreshTokenConcurrencyIntegrationTest {

    private static final int REQUESTS = 100;
    private static final Long USER_ID = 1L;
    private static final String GENERATED_REFRESH_TOKEN = "new-refresh-token-in-test";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private static LettuceConnectionFactory redisConnectionFactory;
    private static RefreshTokenStore refreshTokenStore;

    @BeforeAll
    static void setUpRedis() {
        redisConnectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(redisConnectionFactory);
        refreshTokenStore = new RefreshTokenStore(template);
    }

    @AfterAll
    static void tearDownRedis() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @RepeatedTest(5)
    void identicalRefreshToken_canBeConsumedByOnlyOneRequest() throws Exception {
        String rawRefreshToken = "portfolio-test-" + UUID.randomUUID();
        refreshTokenStore.save(TokenHashUtil.sha256(rawRefreshToken), USER_ID,
                Duration.ofMinutes(5));

        AuthMapper authMapper = mock(AuthMapper.class);
        JwtTokenProvider jwtProvider = mock(JwtTokenProvider.class);
        User activeUser = User.builder()
                .userId(USER_ID)
                .role("USER")
                .status("ACTIVE")
                .build();

        when(authMapper.selectUserById(USER_ID)).thenReturn(activeUser);
        when(jwtProvider.generateAccessToken(USER_ID, "USER"))
                .thenReturn("new-access-token-in-test");
        when(jwtProvider.generateRefreshToken(USER_ID))
                .thenReturn(GENERATED_REFRESH_TOKEN);

        TokenService service = new TokenService(authMapper, jwtProvider, refreshTokenStore);

        ExecutorService workers = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();

        try {
            for (int i = 0; i < REQUESTS; i++) {
                outcomes.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        return "START_TIMEOUT";
                    }
                    try {
                        service.refresh(rawRefreshToken);
                        return "SUCCESS";
                    } catch (CommonException e) {
                        if (e.getErrorCode() == ErrorCode.INVALID_REFRESH_TOKEN) {
                            return "REJECTED";
                        }
                        return "UNEXPECTED_" + e.getErrorCode();
                    }
                }));
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS),
                    "Workers did not become ready within 30 seconds");
            start.countDown();

            int success = 0;
            int rejected = 0;
            for (Future<String> future : outcomes) {
                String result = future.get(40, TimeUnit.SECONDS);
                if ("SUCCESS".equals(result)) {
                    success++;
                } else if ("REJECTED".equals(result)) {
                    rejected++;
                } else {
                    throw new AssertionError("Unexpected outcome: " + result);
                }
            }

            int duplicateSuccess = Math.max(0, success - 1);
            System.out.printf(
                    "[RefreshToken concurrency] attempts=%d success=%d rejected=%d duplicateSuccess=%d%n",
                    REQUESTS, success, rejected, duplicateSuccess);

            assertEquals(1, success, "Exactly one request should consume the token");
            assertEquals(REQUESTS - 1, rejected, "All other requests must be rejected");
            assertEquals(0, duplicateSuccess, "There must be no duplicate success");
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            refreshTokenStore.revoke(TokenHashUtil.sha256(rawRefreshToken));
            refreshTokenStore.revoke(TokenHashUtil.sha256(GENERATED_REFRESH_TOKEN));
        }
    }
}
