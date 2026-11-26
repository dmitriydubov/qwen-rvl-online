package ru.projectx.contracts.acceptance;

import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

final class StandStorage {

    private static final String IMAGE_VARIABLE = "TEST_MINIO_IMAGE";
    private static final String DEFAULT_IMAGE =
            "chainguard/minio@sha256:ad134b2dd572ae0d989d6807c3e45516cc75a34883a8021db2ff04be5d1d496a";
    private static final String MINIO = "minio/minio";
    private static final int S3_PORT = 9000;
    private static final Duration RECOVERY = Duration.ofSeconds(60);
    private static final long UNKNOWN_PART_SIZE = -1;

    private final MinIOContainer container;
    private State state = State.RUNNING;
    private MinioClient admin;

    private enum State { RUNNING, STOPPED, PAUSED }

    private StandStorage(MinIOContainer container) {
        this.container = container;
    }

    static StandStorage container() throws IOException {
        String image = Optional.ofNullable(System.getenv(IMAGE_VARIABLE)).filter(name -> !name.isBlank())
                .orElse(DEFAULT_IMAGE);
        MinIOContainer container = new MinIOContainer(DockerImageName.parse(image).asCompatibleSubstituteFor(MINIO));
        container.setPortBindings(List.of(freePort() + ":" + S3_PORT));
        return new StandStorage(container);
    }

    MinIOContainer startable() {
        return container;
    }

    String endpoint() {
        return container.getS3URL();
    }

    String accessKey() {
        return container.getUserName();
    }

    String secretKey() {
        return container.getPassword();
    }

    void recreateBucket(String bucket) throws Exception {
        recover();
        removeBucket(bucket);
        client().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }

    void removeBucket(String bucket) throws Exception {
        if (!bucketExists(bucket)) {
            return;
        }
        for (String key : keys(bucket, "")) {
            client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        }
        client().removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
    }

    boolean bucketExists(String bucket) throws Exception {
        return client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
    }

    List<String> keys(String bucket, String prefix) throws Exception {
        List<String> keys = new ArrayList<>();
        for (Result<Item> item : client().listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
            keys.add(item.get().objectName());
        }
        return keys;
    }

    byte[] object(String bucket, String key) throws Exception {
        try (GetObjectResponse object = client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return object.readAllBytes();
        }
    }

    void putObject(String bucket, String key, byte[] content) throws Exception {
        client().putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(content), content.length, UNKNOWN_PART_SIZE).build());
    }

    void removeObject(String bucket, String key) throws Exception {
        client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    void stop() {
        docker().stopContainerCmd(container.getContainerId()).exec();
        state = State.STOPPED;
    }

    void pause() {
        docker().pauseContainerCmd(container.getContainerId()).exec();
        state = State.PAUSED;
    }

    boolean disrupted() {
        return state != State.RUNNING;
    }

    void recover() {
        switch (state) {
            case STOPPED -> docker().startContainerCmd(container.getContainerId()).exec();
            case PAUSED -> docker().unpauseContainerCmd(container.getContainerId()).exec();
            case RUNNING -> {
                return;
            }
            default -> throw new IllegalStateException("неизвестное состояние хранилища стенда " + state);
        }
        state = State.RUNNING;
        await("хранилище стенда снова отвечает").atMost(RECOVERY).ignoreExceptions().until(this::answers);
    }

    private boolean answers() throws Exception {
        client().listBuckets();
        return true;
    }

    private DockerClient docker() {
        return container.getDockerClient();
    }

    private MinioClient client() {
        if (admin == null) {
            admin = MinioClient.builder().endpoint(endpoint()).credentials(accessKey(), secretKey()).build();
        }
        return admin;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
