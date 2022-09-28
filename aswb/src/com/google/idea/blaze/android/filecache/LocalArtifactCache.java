/*
 * Copyright 2021 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.filecache;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.base.async.FutureUtil;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import com.google.idea.blaze.base.command.buildresult.OutputArtifact;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.prefetch.FetchExecutor;
import com.google.idea.blaze.base.prefetch.RemoteArtifactPrefetcher;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.scope.scopes.TimingScope.EventType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * LocalArtifactCache is an on disk cache for {@link OutputArtifact}s.
 *
 * <p>The {@link #get} returns the path to a local copy of the cached artifact (if present).
 *
 * <p>The {@link #putAll} method can be used to populate the cache with a set of artifacts. Putting
 * a collection is more efficient since it allows us to batch download the missing artifacts.
 *
 * <pre>
 * All cache maintenance operations happen as part of {@link #putAll}:
 *   - It recognizes when an Artifact has been updated (via timestamp or objfs blobId)
 *   - It deletes stale entries if they are not referenced in the collection passed to
 *     {@link #putAll} call and {@code removeMissingArtifacts} parameter is set to true. This is
 *     unlike a traditional cache, and hence care should be taken to always call {@link #putAll}
 *     with all the artifacts that need to be referenced if {@code removeMissingArtifact} parameter
 *     is set to true.
 * </pre>
 *
 * Internally, every {@link OutputArtifact} is mapped to a {@link CacheEntry} object, which captures
 * metadata about the artifact including data like timestamp or objfs blobId that allows it to
 * determine when an artifact has been updated.
 *
 * <p>An in memory map of these CacheEntries allows for quick retrieval of the cached objects. But
 * this map is also persisted to the disk after calls to {@link #clearCache} and {@link #putAll} and
 * is expected to be re-initialized from the disk via a call to {@link #initialize}.
 */
public class LocalArtifactCache implements ArtifactCache {
  private static final Logger logger = Logger.getInstance(LocalArtifactCache.class);

  private final Project project;

  /** Name of the cache. This is used for logging purposes only. */
  private final String cacheName;

  /** Absolute path to the directory where artifacts will be stored. */
  private final Path cacheDir;

  /**
   * Maps cache key to CacheEntry. A cache key is a String to uniquely identify a CacheEntry for a
   * specific set of Artifacts. The cache key is the same as what is stored in CacheEntry.
   */
  private final Map<String, CacheEntry> cacheState = new HashMap<>();

  public LocalArtifactCache(Project project, String cacheName, Path cacheDir) {
    this.project = project;
    this.cacheName = cacheName;
    this.cacheDir = cacheDir;
  }

  /**
   * {@inheritDoc}
   *
   * <p>NOTE: This method does blocking disk I/O, so should not be called in the cache's
   * constructor.
   */
  @Override
  public synchronized void initialize() {
    loadCacheData();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Any untracked artifacts in {@link #cacheDir} will be removed as well. Blocks until done.
   */
  @Override
  public synchronized void clearCache() {
    try {
      ImmutableList<ListenableFuture<?>> deletionFutures = LocalCacheUtils.clearCache(cacheDir);
      Futures.allAsList(deletionFutures).get();
    } catch (ExecutionException | LocalCacheOperationException e) {
      logger.warn("Could not delete contents of " + cacheDir, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      // write a empty state to disk
      cacheState.clear();
      writeCacheData();
    }
  }

  @Override
  public void refresh() {
    throw new UnsupportedOperationException("Operation is not supported.");
  }

  /**
   * {@inheritDoc}
   *
   * <p>New artifacts are copied and tracked unconditionally. Known artifacts are only updated if
   * they have changes in objFS since last copy. Blocks until done.
   */
  @Override
  public synchronized void putAll(
      Collection<OutputArtifact> artifacts, BlazeContext context, boolean removeMissingArtifacts) {
    // Utility maps for the passed artifacts
    Map<String, OutputArtifact> keyToArtifact = new HashMap<>();
    Map<String, CacheEntry> keyToCacheEntry = new HashMap<>();
    artifacts.forEach(
        a -> {
          CacheEntry cacheEntry;
          try {
            cacheEntry = CacheEntry.forArtifact(a);
          } catch (ArtifactNotFoundException e) {
            // If the artifact to cache doesn't exist, we skip caching it.
            return;
          }
          keyToArtifact.put(cacheEntry.getCacheKey(), a);
          keyToCacheEntry.put(cacheEntry.getCacheKey(), cacheEntry);
        });

    // track which artifacts were updated
    // All artifacts not already in cache, and artifacts whose CacheEntry doesn't equal existing
    // CacheKey are considered "updated"
    ImmutableList<String> updatedKeys =
        keyToCacheEntry.entrySet().stream()
            .filter(
                kv ->
                    !cacheState.containsKey(kv.getKey())
                        || !cacheState.get(kv.getKey()).equals(kv.getValue()))
            .map(Entry::getKey)
            .collect(ImmutableList.toImmutableList());
    ImmutableMap<String, OutputArtifact> updatedKeyToArtifact =
        updatedKeys.stream().collect(ImmutableMap.toImmutableMap(k -> k, keyToArtifact::get));
    ImmutableMap<String, CacheEntry> updatedKeyToCacheEntry =
        updatedKeys.stream().collect(ImmutableMap.toImmutableMap(k -> k, keyToCacheEntry::get));

    // track artifacts that are missing, for when we want to remove them
    ImmutableList<String> removedKeys = ImmutableList.of();
    if (removeMissingArtifacts) {
      removedKeys =
          cacheState.keySet().stream()
              .filter(k -> !keyToCacheEntry.containsKey(k))
              .collect(ImmutableList.toImmutableList());
    }

    try {
      // Prefetch artifacts from ObjFS (if required)
      ListenableFuture<?> downloadArtifactsFuture =
          RemoteArtifactPrefetcher.getInstance()
              .downloadArtifacts(
                  project.getName(),
                  BlazeArtifact.getRemoteArtifacts(updatedKeyToArtifact.values()));
      FutureUtil.waitForFuture(context, downloadArtifactsFuture)
          .timed("FetchCacheArtifacts", EventType.Prefetching)
          .withProgressMessage(String.format("Fetching Artifacts for %s...", cacheName))
          .run();

      // Copy files to disk and notify
      List<ListenableFuture<String>> copyFutures =
          copyLocally(updatedKeyToArtifact, updatedKeyToCacheEntry);
      List<String> copiedKeys = Futures.allAsList(copyFutures).get();
      copiedKeys.stream()
          .filter(k -> !k.isEmpty())
          .forEach(k -> cacheState.put(k, updatedKeyToCacheEntry.get(k)));

      if (!copiedKeys.isEmpty()) {
        context.output(
            PrintOutput.log(String.format("Copied %d files to %s", copiedKeys.size(), cacheName)));
      }

      // Delete files from disk and notify
      // removedKeys will be empty if removeMissingArtifacts is false
      List<ListenableFuture<String>> removeFutures = deleteCachedFiles(removedKeys);
      List<String> deletedKeys = Futures.allAsList(removeFutures).get();
      deletedKeys.forEach(cacheState::remove);

      if (!deletedKeys.isEmpty()) {
        context.output(
            PrintOutput.log(
                String.format("Removed %d files from %s", deletedKeys.size(), cacheName)));
      }
    } catch (ExecutionException e) {
      logger.warn(String.format("%s synchronization didn't complete", cacheName), e);
      IssueOutput.warn(
              String.format(
                  "%s synchronization didn't complete. Resyncing might fix the issue", cacheName))
          .submit(context);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      context.setCancelled();
    } finally {
      // Write the cache state upon completion
      writeCacheData();
    }
  }

  @Override
  @Nullable
  public synchronized Path get(String cacheKey) {
    CacheEntry cacheEntry = cacheState.get(cacheKey);
    if (cacheEntry == null) {
      return null;
    }
    return LocalCacheUtils.getPathToCachedFile(cacheDir, cacheEntry.getFileName());
  }

  @Override
  @Nullable
  public synchronized Path get(OutputArtifact artifact) {
    CacheEntry queriedEntry;
    try {
      queriedEntry = CacheEntry.forArtifact(artifact);
    } catch (ArtifactNotFoundException e) {
      return null;
    }
    return get(queriedEntry.getCacheKey());
  }

  /**
   * Loads cache information from {@link #cacheDir} and ensures the that the serialized cache
   * information is consistent between serialized json and files on disk. If inconsistent cache
   * state is found, makes a best effort to fix cache state and make it consistent.
   */
  private void loadCacheData() {
    // All files in cache directory except the serialized cache state json
    Set<File> cachedFiles = LocalCacheUtils.getCacheFiles(cacheDir);

    File cacheDataFile = LocalCacheUtils.getCacheDataFile(cacheDir);
    // No cache data file, but there are other files present in cache directory
    if (!FileOperationProvider.getInstance().exists(cacheDataFile) && !cachedFiles.isEmpty()) {
      logger.warn(
          String.format(
              "%s does not exist, but %s contains cached files. Clearing directory for a clean"
                  + " start.",
              cacheDataFile, cacheDir));
      clearCache();
      return;
    }

    try {
      // Read cache state from disk
      LocalCacheUtils.loadCacheDataFileToCacheState(cacheDataFile, cacheState);

      // Remove any references to files that no longer exists in file system
      ImmutableCollection<String> removedReferences =
          LocalCacheUtils.removeStaleReferences(cachedFiles, cacheState);

      if (!removedReferences.isEmpty()) {
        logger.warn(
            String.format(
                "%d invalid references in %s. Removed invalid references.",
                removedReferences.size(), cacheName));
      }

      // Remove any file in FS that is not referenced by the cache
      removeUntrackedFiles(cachedFiles);
    } finally {
      writeCacheData();
    }
  }

  /**
   * Returns a list of futures copying the given {@link OutputArtifact}s to disk. The returned
   * futures return the cache key on successful copy, or an empty string on copy failure.
   */
  private ImmutableList<ListenableFuture<String>> copyLocally(
      Map<String, OutputArtifact> updatedKeyToArtifact,
      Map<String, CacheEntry> updatedKeyToCacheEntry) {
    return updatedKeyToArtifact.entrySet().stream()
        .map(
            kv ->
                FetchExecutor.EXECUTOR.submit(
                    () -> {
                      try {
                        copyLocally(
                            kv.getValue(),
                            LocalCacheUtils.getPathToCachedFile(
                                cacheDir, updatedKeyToCacheEntry.get(kv.getKey()).getFileName()));
                        // return cache key of successfully copied file
                        return kv.getKey();
                      } catch (IOException e) {
                        logger.warn(
                            String.format(
                                "Failed to copy artifact %s to %s", kv.getValue(), cacheDir),
                            e);
                      }
                      // return empty string on failure to copy
                      return "";
                    }))
        .collect(ImmutableList.toImmutableList());
  }

  private static void copyLocally(OutputArtifact blazeArtifact, Path destinationPath)
      throws IOException {
    try (InputStream stream = blazeArtifact.getInputStream()) {
      Files.copy(stream, destinationPath, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Returns a list of futures deleting files corresponding to the keys from disk. The returned
   * futures return the cache key on successful deletion, or an empty string on failure.
   */
  private ImmutableList<ListenableFuture<String>> deleteCachedFiles(
      ImmutableList<String> removedKeys) {
    return removedKeys.stream()
        // Create a (key, file) pair. This is created so the futures do not hold a reference to the
        // current object
        .map(k -> Pair.create(k, cacheDir.resolve(cacheState.get(k).getFileName())))
        .map(
            pair ->
                FetchExecutor.EXECUTOR.submit(
                    () -> {
                      try {
                        Files.deleteIfExists(pair.second);
                        // return cache key of the deleted file
                        return pair.first;
                      } catch (IOException e) {
                        logger.warn(e);
                      }
                      // return empty string on failure to delete
                      return "";
                    }))
        .collect(ImmutableList.toImmutableList());
  }

  /** Deletes files in {@code cachedFiles} that are not tracked in {@link #cacheState}. */
  private void removeUntrackedFiles(Set<File> cachedFiles) {
    ImmutableList<ListenableFuture<?>> futures =
        LocalCacheUtils.removeUntrackedFiles(cachedFiles, cacheState);

    if (futures.isEmpty()) {
      return;
    }

    try {
      Futures.allAsList(futures).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      logger.warn("Could not remove untracked files", e);
    }
  }

  /** Serializes {@link #cacheState} to a file on disk. */
  private void writeCacheData() {
    File cacheDataFile = LocalCacheUtils.getCacheDataFile(cacheDir);
    CacheData cacheData = new CacheData(cacheState.values());

    try {
      LocalCacheUtils.writeCacheData(cacheDir, cacheDataFile, cacheData).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      logger.warn(String.format("Failed to write cache state file %s", cacheDataFile));
    }
  }
}
