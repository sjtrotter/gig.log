package cloud.trotter.feed.contract;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Kotlin `internal` is public in bytecode, so the boundary is checked from Java: no constructor or factory of a
 * verified type, and no pin loader that takes caller bytes, is reachable from outside the class. Reachable means
 * not private and not synthetic (javac refuses to call a synthetic member); package-private counts, since any jar
 * can declare a class in this package.
 */
public class FactoryVisibilityTest {

    private static Set<String> reachable(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (!c.isSynthetic() && !Modifier.isPrivate(c.getModifiers())) out.add("<init>");
        }
        for (Method m : type.getDeclaredMethods()) {
            if (!m.isSynthetic() && !Modifier.isPrivate(m.getModifiers())) out.add(m.getName());
        }
        return out;
    }

    private static Set<String> setOf(String... names) {
        return new TreeSet<>(Arrays.asList(names));
    }

    @Test
    public void verifiedManifestIsMadeOnlyByItsCheckedVerify() {
        assertEquals(
            setOf("getCandidate", "getContract", "getDigest", "getEpoch", "getFeed", "getFiles", "getIssuedAt",
                "getKeyId", "getSequence", "getSource"),
            reachable(VerifiedManifest.class));
        assertEquals(setOf("verify"), reachable(VerifiedManifest.Companion.class));
    }

    @Test
    public void verifiedFeedFileIsMadeOnlyByItsCheckedVerify() {
        assertEquals(
            setOf("bytes", "getEpoch", "getFeed", "getKeyId", "getName", "getSequence", "getSha256", "getSourceId"),
            reachable(VerifiedFeedFile.class));
        assertEquals(setOf("verify"), reachable(VerifiedFeedFile.Companion.class));
    }

    @Test
    public void feedTrustLoadsOnlyTheShippedPinFile() {
        // signer$feed_contract checks a signature against the epoch already pinned for the source and feed; it takes
        // no key.
        assertEquals(setOf("getRejectedUserPins", "signer$feed_contract", "trusts"), reachable(FeedTrust.class));
        assertEquals(setOf("shipped"), reachable(FeedTrust.Companion.class));
    }
}
