package org.meridor.perspective.worker.fetcher.impl;

import org.meridor.perspective.framework.storage.EntityListener;
import org.meridor.perspective.framework.storage.StorageEvent;
import org.meridor.perspective.worker.fetcher.LastModificationAware;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.meridor.perspective.framework.storage.StorageEvent.ADDED;
import static org.meridor.perspective.framework.storage.StorageEvent.MODIFIED;
import static org.meridor.perspective.worker.fetcher.impl.LastModified.*;
import static org.meridor.perspective.worker.fetcher.impl.SchedulerUtils.getMomentsAgoLimit;
import static org.meridor.perspective.worker.fetcher.impl.SchedulerUtils.getSomeTimeAgoLimit;

public abstract class LastModificationListener<T> implements EntityListener<T>, LastModificationAware {
    
    private final Map<String, Set<String>> lastModifiedData = new ConcurrentHashMap<>();
    
    protected abstract int getLongTimeAgoLimit();
    
    protected abstract String getId(T entity);
    
    protected abstract String getCloudId(T entity);
    
    protected abstract Instant getLastModifiedInstant(T entity);

    @Override
    public void onEvent(T entity, T previousEntity, StorageEvent event) {
        LastModified lastModified = getLastModified(entity);
        String id = getId(entity);
        String cloudId = getCloudId(entity);
        String key = lastModified.getKey(cloudId);
        removeId(id);
        if (event == ADDED || event == MODIFIED) {
            lastModifiedData.putIfAbsent(key, new HashSet<>());
            lastModifiedData.get(key).add(id);
        }
    }
    
    private void removeId(String id) {
        lastModifiedData.keySet().forEach(k -> lastModifiedData.get(k).remove(id));
    }
    
    private LastModified getLastModified(T entity) {
        int longTimeAgoLimit = getLongTimeAgoLimit();
        int momentsAgoLimit = getMomentsAgoLimit(longTimeAgoLimit);
        int someTimeAgoLimit = getSomeTimeAgoLimit(longTimeAgoLimit);
        Instant currentInstant = Instant.now();
        Duration difference = Duration.between(getLastModifiedInstant(entity), currentInstant);
        long differenceMilliseconds = difference.toMillis();
        if (differenceMilliseconds <= momentsAgoLimit) {
            return NOW;
        } else if (differenceMilliseconds <= someTimeAgoLimit) {
            return MOMENTS_AGO;
        } else if (differenceMilliseconds <= longTimeAgoLimit) {
            return SOME_TIME_AGO;
        } else {
            return LONG_AGO;
        }
    }

    @Override
    public Set<String> getIds(String cloudId, LastModified lastModified) {
        return lastModifiedData.getOrDefault(lastModified.getKey(cloudId), Collections.emptySet());
    }
}
