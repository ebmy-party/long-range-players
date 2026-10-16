package dev.codex.longrange.client;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

final class PrivateFiles {
    static void write(Path target, String contents) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "lrp-private-", ".tmp");
        try {
            var posix = Files.getFileAttributeView(temp, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            else {
                var acl = Files.getFileAttributeView(temp, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (acl == null) throw new IOException("Cannot restrict config permissions");
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
            Files.writeString(temp, contents);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
