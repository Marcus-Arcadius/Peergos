package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.FragmentWithHash;
import peergos.shared.util.ProgressConsumer;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RetryStorage implements ContentAddressedStorage {

    private final Random random = new Random(1);
    private final ContentAddressedStorage target;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final int maxAttempts;

    public RetryStorage(ContentAddressedStorage target, int maxAttempts) {
        this.target = target;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new RetryStorage(target.directToOrigin(), maxAttempts);
    }

    private <V> void retryAfter(Supplier<CompletableFuture<V>> method, int milliseconds) {
        executor.schedule(method::get, milliseconds, TimeUnit.MILLISECONDS);
    }

    private int jitter(int minMilliseconds, int rangeMilliseconds) {
        return minMilliseconds + random.nextInt(rangeMilliseconds);
    }

    private <V> CompletableFuture<V> runWithRetry(Supplier<CompletableFuture<V>> f) {
        return recurse(maxAttempts, f);
    }

    private <V> CompletableFuture<V> recurse(int retriesLeft, Supplier<CompletableFuture<V>> f) {
        CompletableFuture<V> res = new CompletableFuture<>();
        try {
            f.get()
                    .thenAccept(res::complete)
                    .exceptionally(e -> {
                        if (retriesLeft == 1) {
                            res.completeExceptionally(e);
                        } else if (e instanceof StorageQuotaExceededException) {
                            res.completeExceptionally(e);
                        } else if (e instanceof HttpFileNotFoundException) {
                            res.completeExceptionally(e);
                        } else {
                            retryAfter(() -> recurse(retriesLeft - 1, f)
                                            .thenAccept(res::complete)
                                            .exceptionally(t -> {
                                                res.completeExceptionally(t);
                                                return null;
                                            }),
                                    jitter((maxAttempts + 1 - retriesLeft) * 1000, 500));
                        }
                        return null;
                    });
        } catch (Throwable t) {
            res.completeExceptionally(t);
        }
        return res;
    }
    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return runWithRetry(() -> target.blockStoreProperties());
    }
    @Override
    public CompletableFuture<Cid> id() {
        return runWithRetry(() -> target.id());
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return runWithRetry(() -> target.startTransaction(owner));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return runWithRetry(() -> target.closeTransaction(owner, tid));
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid) {
        return runWithRetry(() -> target.put(owner, writer, signatures, blocks, tid));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash, String auth) {
        return runWithRetry(() -> target.get(hash, auth));
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressCounter) {
        return runWithRetry(() -> target.putRaw(owner, writer, signatures, blocks, tid, progressCounter));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash, String auth) {
        return runWithRetry(() -> target.getRaw(hash, auth));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        return runWithRetry(() -> target.getChampLookup(owner, root, champKey));
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        return runWithRetry(() -> target.gc());
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root, String auth) {
        return runWithRetry(() -> target.getLinks(root, auth));
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return runWithRetry(() -> target.getSize(block));
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Cid> hashes,
                                                                       List<BatWithId> bats,
                                                                       Hasher h,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        return runWithRetry(() -> target.downloadFragments(hashes, bats, h, monitor, spaceIncreaseFactor));
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(List<Multihash> blocks) {
        return runWithRetry(() -> target.authReads(blocks));
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        return runWithRetry(() -> target.authWrites(owner, writer, signedHashes, blockSizes, isRaw, tid));
    }
}
