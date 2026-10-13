package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class GallerySourceTest {
    @TempDir Path root;
    @Test void rejectsDeclaredCommitThatDoesNotMatchConfiguredCheckout() throws Exception {
        Files.createDirectories(root.resolve(".git/refs/heads"));Files.writeString(root.resolve(".git/HEAD"),"ref: refs/heads/main\n");
        Files.writeString(root.resolve(".git/refs/heads/main"),GallerySource.COMMIT+"\n");
        assertThatCode(()->GallerySource.verifyCommit(root,GallerySource.COMMIT)).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(()->GallerySource.verifyCommit(root,"a".repeat(40))).withMessageContaining("SOURCE_COMMIT");
    }
    @Test void acceptsDetachedPinnedCheckoutWithoutExecutingGit() throws Exception {
        Files.createDirectory(root.resolve(".git"));Files.writeString(root.resolve(".git/HEAD"),GallerySource.COMMIT+"\n");
        GallerySource.verifyCommit(root,GallerySource.COMMIT);
    }
}
