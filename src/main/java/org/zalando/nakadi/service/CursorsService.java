package org.zalando.nakadi.service;

import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zalando.nakadi.config.NakadiSettings;
import org.zalando.nakadi.domain.CursorError;
import org.zalando.nakadi.domain.EventTypePartition;
import org.zalando.nakadi.domain.NakadiCursor;
import org.zalando.nakadi.domain.Subscription;
import org.zalando.nakadi.exceptions.InternalNakadiException;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.InvalidStreamIdException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.NakadiRuntimeException;
import org.zalando.nakadi.exceptions.NoSuchEventTypeException;
import org.zalando.nakadi.exceptions.NoSuchSubscriptionException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.exceptions.UnableProcessException;
import org.zalando.nakadi.exceptions.runtime.CursorUnavailableException;
import org.zalando.nakadi.exceptions.runtime.OperationTimeoutException;
import org.zalando.nakadi.exceptions.runtime.ZookeeperException;
import org.zalando.nakadi.repository.EventTypeRepository;
import org.zalando.nakadi.repository.TopicRepository;
import org.zalando.nakadi.repository.db.SubscriptionDbRepository;
import org.zalando.nakadi.service.subscription.model.Partition;
import org.zalando.nakadi.service.subscription.zk.SubscriptionClientFactory;
import org.zalando.nakadi.service.subscription.zk.SubscriptionNotInitializedException;
import org.zalando.nakadi.service.subscription.zk.ZkSubscriptionClient;
import org.zalando.nakadi.service.timeline.TimelineService;
import org.zalando.nakadi.util.TimeLogger;
import org.zalando.nakadi.util.UUIDGenerator;
import org.zalando.nakadi.view.SubscriptionCursorWithoutToken;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class CursorsService {

    private final TimelineService timelineService;
    private final SubscriptionDbRepository subscriptionRepository;
    private final EventTypeRepository eventTypeRepository;
    private final NakadiSettings nakadiSettings;
    private final SubscriptionClientFactory zkSubscriptionFactory;
    private final CursorConverter cursorConverter;
    private final UUIDGenerator uuidGenerator;

    @Autowired
    public CursorsService(final TimelineService timelineService,
                          final SubscriptionDbRepository subscriptionRepository,
                          final EventTypeRepository eventTypeRepository,
                          final NakadiSettings nakadiSettings,
                          final SubscriptionClientFactory zkSubscriptionFactory,
                          final CursorConverter cursorConverter,
                          final UUIDGenerator uuidGenerator) {
        this.timelineService = timelineService;
        this.subscriptionRepository = subscriptionRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.nakadiSettings = nakadiSettings;
        this.zkSubscriptionFactory = zkSubscriptionFactory;
        this.cursorConverter = cursorConverter;
        this.uuidGenerator = uuidGenerator;
    }

    /**
     * It is guaranteed, that len(cursors) == len(result)
     **/
    public List<Boolean> commitCursors(final String streamId, final String subscriptionId,
                                       final List<NakadiCursor> cursors)
            throws ServiceUnavailableException, InvalidCursorException, InvalidStreamIdException,
            NoSuchEventTypeException, InternalNakadiException, NoSuchSubscriptionException, UnableProcessException {
        if (cursors.isEmpty()) {
            throw new UnableProcessException("Cursors are absent");
        }
        TimeLogger.addMeasure("getSubscription");
        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);

        TimeLogger.addMeasure("validateSubscriptionCursors");
        validateSubscriptionCommitCursors(subscription, cursors);

        TimeLogger.addMeasure("createSubscriptionClient");
        final ZkSubscriptionClient zkClient = zkSubscriptionFactory.createClient(
                subscription, "subscription." + subscriptionId + "." + streamId + ".offsets");

        TimeLogger.addMeasure("validateStreamId");
        validateStreamId(cursors, streamId, zkClient);

        TimeLogger.addMeasure("writeToZK");
        return zkClient.commitOffsets(
                cursors.stream().map(cursorConverter::convertToNoToken).collect(Collectors.toList()),
                new SubscriptionCursorComparator());
    }

    private void validateStreamId(final List<NakadiCursor> cursors, final String streamId,
                                  final ZkSubscriptionClient subscriptionClient)
            throws ServiceUnavailableException, InvalidCursorException, InvalidStreamIdException,
            NoSuchEventTypeException, InternalNakadiException {

        if (!uuidGenerator.isUUID(streamId)) {
            throw new InvalidStreamIdException(
                    String.format("Stream id has to be valid UUID, but `%s was provided", streamId), streamId);
        }

        if (!subscriptionClient.isActiveSession(streamId)) {
            throw new InvalidStreamIdException("Session with stream id " + streamId + " not found", streamId);
        }

        final Map<EventTypePartition, String> partitionSessions = Stream.of(subscriptionClient.listPartitions())
                .collect(Collectors.toMap(Partition::getKey, Partition::getSession));
        for (final NakadiCursor cursor : cursors) {
            final EventTypePartition etPartition = cursor.getEventTypePartition();
            final String partitionSession = partitionSessions.get(etPartition);
            if (partitionSession == null) {
                throw new InvalidCursorException(CursorError.PARTITION_NOT_FOUND, cursor);
            }

            if (!streamId.equals(partitionSession)) {
                throw new InvalidStreamIdException("Cursor " + cursor + " cannot be committed with stream id "
                        + streamId, streamId);
            }
        }
    }

    public List<SubscriptionCursorWithoutToken> getSubscriptionCursors(final String subscriptionId)
            throws NakadiException {
        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);
        final ZkSubscriptionClient zkSubscriptionClient = zkSubscriptionFactory.createClient(
                subscription, "subscription." + subscriptionId + ".get_cursors");
        final ImmutableList.Builder<SubscriptionCursorWithoutToken> cursorsListBuilder = ImmutableList.builder();

        Partition[] partitions;
        try {
            partitions = zkSubscriptionClient.listPartitions();
        } catch (final SubscriptionNotInitializedException ex) {
            partitions = new Partition[]{};
        }
        for (final Partition p : partitions) {
            cursorsListBuilder.add(zkSubscriptionClient.getOffset(p.getKey()));
        }
        return cursorsListBuilder.build();
    }

    public void resetCursors(final String subscriptionId, final List<NakadiCursor> cursors)
            throws ServiceUnavailableException, NoSuchSubscriptionException, CursorUnavailableException,
            UnableProcessException, OperationTimeoutException, ZookeeperException,
            InternalNakadiException, NoSuchEventTypeException {
        if (cursors.isEmpty()) {
            throw new UnableProcessException("Cursors are absent");
        }
        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);
        validateSubscriptionResetCursors(subscription, cursors);

        final ZkSubscriptionClient zkClient = zkSubscriptionFactory.createClient(
                subscription, "subscription." + subscriptionId + ".reset_cursors");
        // add 1 second to commit timeout in order to give time to finish reset if there is uncommitted events
        final long timeout = TimeUnit.SECONDS.toMillis(nakadiSettings.getDefaultCommitTimeoutSeconds()) +
                TimeUnit.SECONDS.toMillis(1);
        zkClient.resetCursors(
                cursors.stream().map(cursorConverter::convertToNoToken).collect(Collectors.toList()),
                timeout);
    }

    private void validateSubscriptionCommitCursors(final Subscription subscription, final List<NakadiCursor> cursors)
            throws ServiceUnavailableException, UnableProcessException {
        validateCursorsBelongToSubscription(subscription, cursors);

        cursors.forEach(cursor -> {
            try {
                timelineService.getTopicRepository(cursor.getTimeline()).validateCommitCursor(cursor);
            } catch (final InvalidCursorException e) {
                throw new UnableProcessException(e.getMessage(), e);
            }
        });
    }

    private void validateSubscriptionResetCursors(final Subscription subscription, final List<NakadiCursor> cursors)
            throws ServiceUnavailableException, CursorUnavailableException {
        validateCursorsBelongToSubscription(subscription, cursors);

        final Map<TopicRepository, List<NakadiCursor>> cursorsByRepo = cursors.stream()
                .collect(Collectors.groupingBy(c -> timelineService.getTopicRepository(c.getTimeline())));

        for (final Map.Entry<TopicRepository, List<NakadiCursor>> repoEntry : cursorsByRepo.entrySet()) {
            final TopicRepository topicRepository = repoEntry.getKey();
            final List<NakadiCursor> cursorsForRepo = repoEntry.getValue();
            try {
                topicRepository.validateReadCursors(cursorsForRepo);
            } catch (final InvalidCursorException e) {
                throw new CursorUnavailableException(e.getMessage(), e);
            }
        }
    }

    private void validateCursorsBelongToSubscription(final Subscription subscription, final List<NakadiCursor> cursors)
            throws UnableProcessException {
        final List<String> wrongEventTypes = cursors.stream()
                .map(NakadiCursor::getEventType)
                .filter(et -> !subscription.getEventTypes().contains(et))
                .collect(Collectors.toList());
        if (!wrongEventTypes.isEmpty()) {
            throw new UnableProcessException("Event type does not belong to subscription: " + wrongEventTypes);
        }
    }

    private class SubscriptionCursorComparator implements Comparator<SubscriptionCursorWithoutToken> {
        private final Map<SubscriptionCursorWithoutToken, NakadiCursor> cached = new HashMap<>();

        @Override
        public int compare(final SubscriptionCursorWithoutToken c1, final SubscriptionCursorWithoutToken c2) {
            return convert(c1).compareTo(convert(c2));
        }

        private NakadiCursor convert(final SubscriptionCursorWithoutToken value) {
            NakadiCursor result = cached.get(value);
            if (null != result) {
                return result;
            }
            try {
                result = cursorConverter.convert(value);
                cached.put(value, result);
                return result;
            } catch (final Exception ignore) {
                //On this stage exception should not be generated - cursors are validated.
                throw new NakadiRuntimeException(ignore);
            }
        }
    }
}
