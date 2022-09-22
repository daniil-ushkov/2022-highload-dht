package ok.dht.test.lsm;

import java.nio.file.Path;

public record Config(
        Path basePath,
        long flushThresholdBytes) {
}
