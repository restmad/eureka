package com.netflix.rx.eureka.server.service;

import com.netflix.rx.eureka.interests.ChangeNotification;
import com.netflix.rx.eureka.interests.Interest;
import com.netflix.rx.eureka.interests.Interests;
import com.netflix.rx.eureka.protocol.EurekaProtocolError;
import com.netflix.rx.eureka.protocol.Heartbeat;
import com.netflix.rx.eureka.protocol.discovery.InterestRegistration;
import com.netflix.rx.eureka.protocol.discovery.UnregisterInterestSet;
import com.netflix.rx.eureka.registry.EurekaRegistry;
import com.netflix.rx.eureka.registry.InstanceInfo;
import com.netflix.rx.eureka.server.service.InterestChannelMetrics.ChannelSubscriptionMonitor;
import com.netflix.rx.eureka.server.transport.ClientConnection;
import com.netflix.rx.eureka.service.InterestChannel;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;

/**
 * An implementation of {@link InterestChannel} for eureka server.
 *
 * <b>This channel is self contained and does not require any external invocations on the {@link InterestChannel}
 * interface.</b>
 *
 * @author Nitesh Kant
 */
public class InterestChannelImpl extends AbstractChannel<InterestChannelImpl.STATES> implements InterestChannel {

    protected enum STATES {Idle, Open, Closed}

    private final InterestChannelMetrics metrics;

    private final InterestNotificationMultiplexer notificationMultiplexer;
    private final ChannelSubscriptionMonitor channelSubscriptionMonitor;

    public InterestChannelImpl(final EurekaRegistry<InstanceInfo> registry, final ClientConnection transport, final InterestChannelMetrics metrics) {
        super(STATES.Idle, transport, registry, 3, 30000);
        this.metrics = metrics;
        this.notificationMultiplexer = new InterestNotificationMultiplexer(registry);
        this.channelSubscriptionMonitor = new ChannelSubscriptionMonitor(metrics);

        subscribeToTransportInput(new Action1<Object>() {
            @Override
            public void call(Object message) {
                /**
                 * Since, it is guaranteed that the messages on a connection are strictly sequential (single threaded
                 * invocation), we do not need to worry about thread safety here and hence we can safely do a
                 * isRegistered -> register kind of actions without worrying about race conditions.
                 */
                if (message instanceof InterestRegistration) {
                    Interest<InstanceInfo> interest = ((InterestRegistration) message).toComposite();
                    switch (state.get()) {
                        case Open:
                            change(interest);
                            break;
                        case Closed:
                            sendErrorOnTransport(CHANNEL_CLOSED_EXCEPTION);
                            break;
                    }
                } else if (message instanceof UnregisterInterestSet) {
                    switch (state.get()) {
                        case Open:
                            change(Interests.forNone());
                            break;
                        case Closed:
                            sendErrorOnTransport(CHANNEL_CLOSED_EXCEPTION);
                            break;
                    }
                } else if (message instanceof Heartbeat) {
                    heartbeat();
                } else {
                    sendErrorOnTransport(new EurekaProtocolError("Unexpected message " + message));
                }

            }
        });

        state.set(STATES.Open);
        this.metrics.incrementStateCounter(STATES.Open);
        sendAckOnTransport();

        notificationMultiplexer.changeNotifications().subscribe(
                new Subscriber<ChangeNotification<InstanceInfo>>() {
                    @Override
                    public void onCompleted() {
                        sendOnCompleteOnTransport(); // On complete of stream.
                        change(Interests.forNone());  // TODO: needed?
                    }

                    @Override
                    public void onError(Throwable e) {
                        sendErrorOnTransport(e);
                    }

                    @Override
                    public void onNext(ChangeNotification<InstanceInfo> notification) {
                        metrics.incrementApplicationNotificationCounter(notification.getData().getApp());
                        sendNotificationOnTransport(notification);
                    }
                });
    }


    @Override
    public Observable<Void> change(Interest<InstanceInfo> newInterest) {
        logger.debug("Received interest change request {}", newInterest);

        if (STATES.Closed == state.get()) {
            /**
             * Since channel is already closed and hence the transport, we don't need to send an error on transport.
             */
            return Observable.error(CHANNEL_CLOSED_EXCEPTION);
        }

        channelSubscriptionMonitor.update(newInterest);
        notificationMultiplexer.update(newInterest);

        Observable<Void> toReturn = transport.sendAcknowledgment();
        subscribeToTransportSend(toReturn, "acknowledgment"); // Subscribe eagerly and not require the caller to subscribe.
        return toReturn;
    }

    @Override
    public void _close() {
        if (state.compareAndSet(STATES.Open, STATES.Closed)) {
            state.set(STATES.Closed);
            channelSubscriptionMonitor.update(Interests.forNone());
            metrics.stateTransition(STATES.Open, STATES.Closed);
            notificationMultiplexer.unregister();
            super._close(); // Shutdown the transport
        }
    }
}
