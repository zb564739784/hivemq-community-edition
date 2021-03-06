/*
 * Copyright 2019 dc-square GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hivemq.mqtt.handler.connect;

import com.google.common.util.concurrent.*;
import com.hivemq.annotations.NotNull;
import com.hivemq.annotations.Nullable;
import com.hivemq.bootstrap.netty.ChannelDependencies;
import com.hivemq.configuration.service.FullConfigurationService;
import com.hivemq.configuration.service.InternalConfigurations;
import com.hivemq.extension.sdk.api.auth.parameter.AuthenticatorProviderInput;
import com.hivemq.extension.sdk.api.packets.auth.ModifiableDefaultPermissions;
import com.hivemq.extension.sdk.api.packets.disconnect.DisconnectReasonCode;
import com.hivemq.extension.sdk.api.packets.general.DisconnectedReasonCode;
import com.hivemq.extension.sdk.api.packets.publish.AckReasonCode;
import com.hivemq.extensions.client.parameter.AuthenticatorProviderInputFactory;
import com.hivemq.extensions.events.OnAuthFailedEvent;
import com.hivemq.extensions.events.OnAuthSuccessEvent;
import com.hivemq.extensions.events.OnServerDisconnectEvent;
import com.hivemq.extensions.executor.PluginOutPutAsyncer;
import com.hivemq.extensions.executor.PluginTaskExecutorService;
import com.hivemq.extensions.handler.PluginAuthorizerService;
import com.hivemq.extensions.handler.PluginAuthorizerServiceImpl.AuthorizeWillResultEvent;
import com.hivemq.extensions.handler.tasks.PublishAuthorizerResult;
import com.hivemq.extensions.packets.general.ModifiableDefaultPermissionsImpl;
import com.hivemq.extensions.services.auth.*;
import com.hivemq.limitation.TopicAliasLimiter;
import com.hivemq.logging.EventLog;
import com.hivemq.mqtt.handler.MessageHandler;
import com.hivemq.mqtt.handler.connack.MqttConnacker;
import com.hivemq.mqtt.handler.ordering.OrderedTopicHandler;
import com.hivemq.mqtt.handler.publish.DefaultPermissionsEvaluator;
import com.hivemq.mqtt.handler.publish.FlowControlHandler;
import com.hivemq.mqtt.message.ProtocolVersion;
import com.hivemq.mqtt.message.connack.CONNACK;
import com.hivemq.mqtt.message.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.mqtt.message.connect.CONNECT;
import com.hivemq.mqtt.message.connect.MqttWillPublish;
import com.hivemq.mqtt.message.mqtt5.Mqtt5UserProperties;
import com.hivemq.mqtt.message.reason.Mqtt5ConnAckReasonCode;
import com.hivemq.mqtt.services.PublishPollService;
import com.hivemq.persistence.ChannelPersistence;
import com.hivemq.persistence.clientsession.ClientSessionPersistence;
import com.hivemq.persistence.clientsession.SharedSubscriptionService;
import com.hivemq.security.auth.ClientToken;
import com.hivemq.util.*;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static com.hivemq.bootstrap.netty.ChannelHandlerNames.*;
import static com.hivemq.configuration.service.InternalConfigurations.AUTH_DENY_UNAUTHENTICATED_CONNECTIONS;
import static com.hivemq.mqtt.message.connack.Mqtt5CONNACK.DEFAULT_MAXIMUM_PACKET_SIZE_NO_LIMIT;
import static com.hivemq.mqtt.message.connect.Mqtt5CONNECT.*;

/**
 * The handler which is responsible for CONNECT messages
 *
 * @author Dominik Obermaier
 * @author Christoph Schäbel
 */
@Singleton
@ChannelHandler.Sharable
public class ConnectHandler extends SimpleChannelInboundHandler<CONNECT> implements MessageHandler<CONNECT> {

    private static final @NotNull Logger log = LoggerFactory.getLogger(ConnectHandler.class);
    private static final int MAX_TAKEOVER_RETRIES = 100;
    private static final String CONNACK_NO_AUTHENTICATION_LOG_STATEMENT = "MQTT CONNECT packet for client with IP {} " +
            "provided authentication information, but no authentication was registered with HiveMQ. Disconnecting client.";

    private final @NotNull DisconnectClientOnConnectMessageHandler onSecondConnectHandler;
    private final @NotNull ClientSessionPersistence clientSessionPersistence;
    private final @NotNull ChannelPersistence channelPersistence;
    private final @NotNull FullConfigurationService configurationService;
    private final @NotNull EventLog eventLog;
    private final @NotNull Provider<OrderedTopicHandler> orderedTopicHandlerProvider;
    private final @NotNull Provider<FlowControlHandler> flowControlHandlerProvider;
    private final @NotNull MqttConnacker mqttConnacker;
    private final @NotNull TopicAliasLimiter topicAliasLimiter;
    private final @NotNull PublishPollService publishPollService;
    private final @NotNull SharedSubscriptionService sharedSubscriptionService;
    private final @NotNull PluginTaskExecutorService pluginTaskExecutorService;
    private final @NotNull ChannelDependencies channelDependencies;
    private final @NotNull PluginOutPutAsyncer asyncer;
    private final @NotNull ConnackSentListener connackSentListener = new ConnackSentListener();
    private final @NotNull Authenticators authenticators;
    private final @NotNull Authorizers authorizers;
    private final @NotNull AuthenticatorProviderInputFactory authenticatorProviderInputFactory;
    private final @NotNull PluginAuthorizerService pluginAuthorizerService;

    private int maxClientIdLength;
    private long configuredSessionExpiryInterval;
    private int topicAliasMaximum;
    private int serverKeepAliveMaximum;
    private boolean allowZeroKeepAlive;

    private long maxMessageExpiryInterval;

    private final Striped<Lock> stripedLock = Striped.lock(Runtime.getRuntime().availableProcessors() * 16);

    @Inject
    public ConnectHandler(
            final @NotNull DisconnectClientOnConnectMessageHandler onSecondConnectHandler,
            final @NotNull ClientSessionPersistence clientSessionPersistence,
            final @NotNull ChannelPersistence channelPersistence,
            final @NotNull FullConfigurationService configurationService,
            final @NotNull EventLog eventLog,
            final @NotNull Provider<OrderedTopicHandler> orderedTopicHandlerProvider,
            final @NotNull Provider<FlowControlHandler> flowControlHandlerProvider,
            final @NotNull MqttConnacker mqttConnacker,
            final @NotNull TopicAliasLimiter topicAliasLimiter,
            final @NotNull Authenticators authenticators,
            final @NotNull PluginTaskExecutorService pluginTaskExecutorService,
            final @NotNull ChannelDependencies channelDependencies,
            final @NotNull PluginOutPutAsyncer asyncer,
            final @NotNull AuthenticatorProviderInputFactory authenticatorProviderInputFactory,
            final @NotNull PublishPollService publishPollService,
            final @NotNull SharedSubscriptionService sharedSubscriptionService,
            final @NotNull Authorizers authorizers,
            final @NotNull PluginAuthorizerService pluginAuthorizerService) {

        this.onSecondConnectHandler = onSecondConnectHandler;
        this.clientSessionPersistence = clientSessionPersistence;
        this.channelPersistence = channelPersistence;
        this.configurationService = configurationService;
        this.eventLog = eventLog;
        this.orderedTopicHandlerProvider = orderedTopicHandlerProvider;
        this.flowControlHandlerProvider = flowControlHandlerProvider;
        this.mqttConnacker = mqttConnacker;
        this.topicAliasLimiter = topicAliasLimiter;
        this.publishPollService = publishPollService;
        this.sharedSubscriptionService = sharedSubscriptionService;
        this.authenticators = authenticators;
        this.pluginTaskExecutorService = pluginTaskExecutorService;
        this.channelDependencies = channelDependencies;
        this.asyncer = asyncer;
        this.authenticatorProviderInputFactory = authenticatorProviderInputFactory;
        this.authorizers = authorizers;
        this.pluginAuthorizerService = pluginAuthorizerService;
    }

    @PostConstruct
    public void postConstruct() {
        maxClientIdLength = configurationService.restrictionsConfiguration().maxClientIdLength();
        configuredSessionExpiryInterval = configurationService.mqttConfiguration().maxSessionExpiryInterval();
        if (configurationService.mqttConfiguration().topicAliasEnabled()) {
            topicAliasMaximum = configurationService.mqttConfiguration().topicAliasMaxPerClient();
        } else {
            topicAliasMaximum = 0;
        }
        serverKeepAliveMaximum = configurationService.mqttConfiguration().keepAliveMax();
        allowZeroKeepAlive = configurationService.mqttConfiguration().keepAliveAllowZero();
        maxMessageExpiryInterval = configurationService.mqttConfiguration().maxMessageExpiryInterval();
    }

    @Override
    protected void channelRead0(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT connect)
            throws Exception {

        try {

            ctx.pipeline().addAfter(MQTT_MESSAGE_DECODER, MQTT_DISALLOW_SECOND_CONNECT, onSecondConnectHandler);

        } catch (final IllegalArgumentException e) {
            /*  When this happens, the client sent two CONNECT messages in a *very* short time because we
                have a race condition that the second CONNECT arrived before the second disallow handler
                was added to the pipeline. We're just resending the message again to the begin of the pipeline
                so the MQTT second connect disallow handler can kick in
            */
            ctx.pipeline().firstContext().fireChannelRead(connect);
            return;
        }

        overwriteNotSetValues(connect);

        if (!checkClientId(ctx, connect)) {
            return;
        }

        if (!checkWillPublish(ctx, connect)) {
            return;
        }

        if (!checkWillRetained(ctx, connect)) {
            return;
        }

        ctx.channel().attr(ChannelAttributes.TAKEN_OVER).set(false);
        ctx.channel().attr(ChannelAttributes.DISCONNECT_FUTURE).set(SettableFuture.create());
        ctx.channel().attr(ChannelAttributes.CLIENT_RECEIVE_MAXIMUM).set(connect.getReceiveMaximum());

        ctx.channel().attr(ChannelAttributes.REQUEST_RESPONSE_INFORMATION).set(connect.isResponseInformationRequested());
        ctx.channel().attr(ChannelAttributes.REQUEST_PROBLEM_INFORMATION).set(connect.isProblemInformationRequested());

        removeNoConnectIdleHandler(ctx);
        addOrderedTopicHandler(ctx, connect);

        if (authenticators.areAuthenticatorsAvailable() || AUTH_DENY_UNAUTHENTICATED_CONNECTIONS) {
            authenticate(ctx, connect);
            return;
        }

        connectSuccessfulUnauthenticated(ctx, connect);
    }

    private void authenticate(@NotNull final ChannelHandlerContext ctx, @NotNull final CONNECT connect) {
        final Map<String, WrappedAuthenticatorProvider> authenticatorProviderMap = authenticators.getAuthenticatorProviderMap();
        if (authenticatorProviderMap.isEmpty() && AUTH_DENY_UNAUTHENTICATED_CONNECTIONS) {
            final OnAuthFailedEvent event = new OnAuthFailedEvent(DisconnectedReasonCode.NOT_AUTHORIZED, "no authenticator registered", connect.getUserProperties().getPluginUserProperties());
            mqttConnacker.connackError(
                    ctx.channel(), CONNACK_NO_AUTHENTICATION_LOG_STATEMENT, "Disconnected not authorized",
                    Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, Mqtt3ConnAckReturnCode.REFUSED_NOT_AUTHORIZED,
                    ReasonStrings.CONNACK_NOT_AUTHORIZED_NO_AUTHENTICATOR, event);
            return;
        }

        if (authenticatorProviderMap.isEmpty()) {
            connectSuccessfulUnauthenticated(ctx, connect);
            return;
        }

        final String authMethod = connect.getAuthMethod();
        if (authMethod != null) {
            ctx.channel().attr(ChannelAttributes.AUTH_METHOD).set(authMethod);
            ctx.pipeline()
                    .addAfter(MQTT_MESSAGE_DECODER, AUTH_IN_PROGRESS_MESSAGE_HANDLER,
                            channelDependencies.getAuthInProgressMessageHandler());
        }


        final ConnectAuthTaskInput input = new ConnectAuthTaskInput(connect, ctx);
        final ConnectAuthTaskContext context =
                new ConnectAuthTaskContext(connect.getClientIdentifier(), this, mqttConnacker, ctx, connect, asyncer,
                        authenticatorProviderMap.size(), configurationService.securityConfiguration().validateUTF8());

        final AuthenticatorProviderInput authenticatorProviderInput = authenticatorProviderInputFactory.createInput(ctx, connect.getClientIdentifier());

        for (final WrappedAuthenticatorProvider wrapped : authenticatorProviderMap.values()) {

            if (!pluginTaskExecutorService.handlePluginInOutTaskExecution(
                    context, input, context, new SimpleAuthTask(wrapped, authenticatorProviderInput))) {
                log.warn("Extension task queue full. Ignoring {}", wrapped.getAuthenticatorProvider());
                context.increment();
            }

        }
    }

    public void connectSuccessfulUnauthenticated(
            final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT connect) {

        if (AUTH_DENY_UNAUTHENTICATED_CONNECTIONS) {
            final OnAuthFailedEvent event = new OnAuthFailedEvent(DisconnectedReasonCode.NOT_AUTHORIZED, "authentication not successful", connect.getUserProperties().getPluginUserProperties());
            mqttConnacker.connackError(
                    ctx.channel(), "Client with ip {} could not be authenticated", "Authentication not successful",
                    Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, Mqtt3ConnAckReturnCode.REFUSED_NOT_AUTHORIZED,
                    ReasonStrings.CONNACK_NOT_AUTHORIZED_FAILED, event);
            return;
        }

        ctx.channel().attr(ChannelAttributes.AUTH_PERMISSIONS).setIfAbsent(new ModifiableDefaultPermissionsImpl());
        ctx.pipeline().channel().attr(ChannelAttributes.AUTH_AUTHENTICATED).set(false);
        connectAuthenticated(ctx, connect);
    }

    public void connectSuccessfulAuthenticated(
            final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT connect) {
        final ClientToken clientCredentialsData = ChannelUtils.tokenFromChannel(ctx.channel());
        ctx.pipeline().channel().attr(ChannelAttributes.AUTH_AUTHENTICATED).set(true);
        clientCredentialsData.setAuthenticated(true);

        if (ctx.channel().hasAttr(ChannelAttributes.AUTH_METHOD)) {
            try {
                ctx.pipeline().remove(AUTH_IN_PROGRESS_MESSAGE_HANDLER);
            } catch (final NoSuchElementException ignored) {

            }
        }

        connectAuthenticated(ctx, connect);
    }

    @Override
    public void overwriteNotSetValues(final @NotNull CONNECT connect) {

        if (connect.getSessionExpiryInterval() == SESSION_EXPIRY_NOT_SET) {
            connect.setSessionExpiryInterval(SESSION_EXPIRE_ON_DISCONNECT);
        }
        if (connect.getReceiveMaximum() == RECEIVE_MAXIMUM_NOT_SET) {
            connect.setReceiveMaximum(DEFAULT_RECEIVE_MAXIMUM);
        }
        if (connect.getTopicAliasMaximum() == TOPIC_ALIAS_MAXIMUM_NOT_SET) {
            connect.setTopicAliasMaximum(DEFAULT_TOPIC_ALIAS_MAXIMUM);
        }
        if (connect.getMaximumPacketSize() == MAXIMUM_PACKET_SIZE_NOT_SET) {
            connect.setMaximumPacketSize(DEFAULT_MAXIMUM_PACKET_SIZE_NO_LIMIT);
        }
        if (connect.isResponseInformationRequested() == null) {
            connect.setResponseInformationRequested(DEFAULT_RESPONSE_INFORMATION_REQUESTED);
        }
        if (connect.isProblemInformationRequested() == null) {
            connect.setProblemInformationRequested(DEFAULT_PROBLEM_INFORMATION_REQUESTED);
        }
        if (connect.isWill()) {
            final MqttWillPublish willPublish = connect.getWillPublish();
            if (willPublish.getMessageExpiryInterval() > maxMessageExpiryInterval) {
                willPublish.setMessageExpiryInterval(maxMessageExpiryInterval);
            }
            if (willPublish.getDelayInterval() == MqttWillPublish.WILL_DELAY_INTERVAL_NOT_SET) {
                willPublish.setDelayInterval(MqttWillPublish.WILL_DELAY_INTERVAL_DEFAULT);
            }
        }
    }

    private void addOrderedTopicHandler(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT connect) {

        ctx.channel()
                .pipeline()
                .addAfter(MQTT_MESSAGE_ID_RETURN_HANDLER, MQTT_ORDERED_TOPIC_HANDLER,
                        orderedTopicHandlerProvider.get());
        if (ProtocolVersion.MQTTv5 == connect.getProtocolVersion()) {
            ctx.channel()
                    .pipeline()
                    .addBefore(STOP_READING_AFTER_CONNECT_HANDLER, MQTT_5_FLOW_CONTROL_HANDLER,
                            flowControlHandlerProvider.get());
        }
    }

    @Override
    public void userEventTriggered(final @NotNull ChannelHandlerContext ctx, final @NotNull Object evt)
            throws Exception {
        if (evt instanceof ConnectPersistenceUpdateHandler.FinishedConnectPersistence) {
            handleFinishedConnectPersistence(ctx, (ConnectPersistenceUpdateHandler.FinishedConnectPersistence) evt);
        } else if (evt instanceof AuthorizeWillResultEvent) {
            final AuthorizeWillResultEvent resultEvent = (AuthorizeWillResultEvent) evt;
            afterPublishAuthorizer(ctx, resultEvent.getConnect(), resultEvent.getResult());
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    private void handleFinishedConnectPersistence(
            final @NotNull ChannelHandlerContext ctx,
            final @NotNull ConnectPersistenceUpdateHandler.FinishedConnectPersistence evt) {

        afterPersistSession(ctx, evt.getMessage(), evt.isSessionPresent());
    }

    private boolean checkClientId(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT msg) {

        final Boolean assigned = ctx.channel().attr(ChannelAttributes.CLIENT_ID_ASSIGNED).get();

        if (assigned != null && assigned) {
            return true;
        }

        if (msg.getClientIdentifier().length() > maxClientIdLength) {

            final String logMessage =
                    "A client (IP: {}) connected with a client identifier longer than " + maxClientIdLength +
                            " characters. This is not allowed.";
            final String eventlogMessage = "Sent CONNECT with Client identifier too long";
            final OnServerDisconnectEvent event = new OnServerDisconnectEvent(DisconnectedReasonCode.CLIENT_IDENTIFIER_NOT_VALID,
                    ReasonStrings.CONNACK_CLIENT_IDENTIFIER_TOO_LONG, msg.getUserProperties());
            mqttConnacker.connackError(ctx.channel(), logMessage, eventlogMessage,
                    Mqtt5ConnAckReasonCode.CLIENT_IDENTIFIER_NOT_VALID,
                    Mqtt3ConnAckReturnCode.REFUSED_IDENTIFIER_REJECTED,
                    ReasonStrings.CONNACK_CLIENT_IDENTIFIER_TOO_LONG, event);
            return false;
        }
        return true;
    }

    private boolean checkWillPublish(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT msg) {
        if (msg.getWillPublish() != null) {
            if (Topics.containsWildcard(msg.getWillPublish().getTopic())) {
                final OnServerDisconnectEvent event = new OnServerDisconnectEvent(DisconnectedReasonCode.TOPIC_NAME_INVALID, ReasonStrings.CONNACK_NOT_AUTHORIZED_WILL_WILDCARD, msg.getUserProperties());
                mqttConnacker.connackError(ctx.channel(),
                        "A client (IP: {}) sent a CONNECT with a wildcard character in the Will Topic (# or +). This is not allowed.",
                        "Sent CONNECT with wildcard character in the Will Topic (#/+)",
                        Mqtt5ConnAckReasonCode.TOPIC_NAME_INVALID, Mqtt3ConnAckReturnCode.REFUSED_NOT_AUTHORIZED,
                        ReasonStrings.CONNACK_NOT_AUTHORIZED_WILL_WILDCARD, event);
                return false;

            }

            final int willQos = msg.getWillPublish().getQos().getQosNumber();
            final int maxQos = configurationService.mqttConfiguration().maximumQos().getQosNumber();
            if (willQos > maxQos) {
                final String reasonString = String.format(ReasonStrings.CONNACK_QOS_NOT_SUPPORTED_WILL, willQos, maxQos);
                final OnServerDisconnectEvent event = new OnServerDisconnectEvent(DisconnectedReasonCode.QOS_NOT_SUPPORTED, reasonString, msg.getUserProperties());
                mqttConnacker.connackError(ctx.channel(),
                        "A client (IP: {}) sent a CONNECT with a Will QoS higher than the maximum configured QoS. This is not allowed.",
                        "Sent CONNECT with Will QoS (" + willQos + ") higher than the allowed maximum (" + maxQos + ")",
                        Mqtt5ConnAckReasonCode.QOS_NOT_SUPPORTED, Mqtt3ConnAckReturnCode.REFUSED_NOT_AUTHORIZED,
                        reasonString, event);
                return false;
            }
        }
        return true;
    }

    private boolean checkWillRetained(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT msg) {
        if (msg.getWillPublish() != null && msg.getWillPublish().isRetain() &&
                !configurationService.mqttConfiguration().retainedMessagesEnabled()) {
            final OnServerDisconnectEvent event = new OnServerDisconnectEvent(DisconnectedReasonCode.RETAIN_NOT_SUPPORTED, ReasonStrings.CONNACK_RETAIN_NOT_SUPPORTED, msg.getUserProperties());
            mqttConnacker.connackError(ctx.channel(),
                    "A client (IP: {}) sent a CONNECT with Will Retain set to 1 although retain is not available.",
                    "Sent a CONNECT with Will Retain set to 1 although retain is not available",
                    Mqtt5ConnAckReasonCode.RETAIN_NOT_SUPPORTED, Mqtt3ConnAckReturnCode.REFUSED_NOT_AUTHORIZED,
                    ReasonStrings.CONNACK_RETAIN_NOT_SUPPORTED, event);
            return false;
        }
        return true;
    }

    private void connectAuthenticated(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT msg) {
        ctx.pipeline().channel().attr(ChannelAttributes.AUTHENTICATED_OR_AUTHENTICATION_BYPASSED).set(true);
        ctx.pipeline().channel().attr(ChannelAttributes.PREVENT_LWT).set(true); //do not send will until it is authorized

        if (msg.getWillPublish() != null) {
            if (authorizers.areAuthorizersAvailable()) {
                ctx.executor().execute(() -> pluginAuthorizerService.authorizeWillPublish(ctx, msg));
            } else {
                if (isWillNotAuthorized(ctx, msg)) {
                    return;
                }
                continueAfterWillAuthorization(ctx, msg);
            }
        } else {
            continueAfterWillAuthorization(ctx, msg);
        }
    }

    private void continueAfterWillAuthorization(@NotNull final ChannelHandlerContext ctx, @NotNull final CONNECT msg) {

        ctx.pipeline().fireUserEventTriggered(new OnAuthSuccessEvent());

        final ListenableFuture<Void> disconnectFuture = disconnectClientWithSameClientId(msg, ctx, 0);

        Futures.addCallback(disconnectFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable final Void result) {
                afterTakeover(ctx, msg);
            }

            @Override
            public void onFailure(@NotNull final Throwable t) {
                Exceptions.rethrowError("Exception on disconnecting client with same client identifier", t);
            }
        }, ctx.executor());
    }

    private void afterPublishAuthorizer(@NotNull final ChannelHandlerContext ctx, @NotNull final CONNECT msg, @NotNull final PublishAuthorizerResult authorizerResult) {

        if (authorizerResult.isAuthorizerPresent() && authorizerResult.getAckReasonCode() != null) {
            //decision has been made in PublishAuthorizer
            if (authorizerResult.getAckReasonCode() == AckReasonCode.SUCCESS) {
                continueAfterWillAuthorization(ctx, msg);
            } else {
                connackWillNotAuthorized(ctx, msg, authorizerResult.getDisconnectReasonCode(), authorizerResult.getAckReasonCode(), authorizerResult.getReasonString());
            }
            return;
        }

        final ModifiableDefaultPermissions permissions = ctx.channel().attr(ChannelAttributes.AUTH_PERMISSIONS).get();
        final ModifiableDefaultPermissionsImpl defaultPermissions = (ModifiableDefaultPermissionsImpl) permissions;

        //if authorizers are present and no permissions are available and the default behaviour has not been changed
        //then we deny the publish
        if (authorizerResult.isAuthorizerPresent()
                && (defaultPermissions == null || (defaultPermissions.asList().size() < 1
                && !defaultPermissions.isDefaultAuthorizationBehaviourOverridden()))) {

            connackWillNotAuthorized(ctx, msg, authorizerResult.getDisconnectReasonCode(), null, null);
            return;
        }

        if (!DefaultPermissionsEvaluator.checkWillPublish(permissions, msg.getWillPublish())) {
            //will is not authorized, disconnect client
            connackWillNotAuthorized(ctx, msg, authorizerResult.getDisconnectReasonCode(), authorizerResult.getAckReasonCode(), authorizerResult.getReasonString());
            return;
        }

        continueAfterWillAuthorization(ctx, msg);
    }

    private boolean isWillNotAuthorized(@NotNull final ChannelHandlerContext ctx, @NotNull final CONNECT msg) {
        if (msg.getWillPublish() != null) {
            final ModifiableDefaultPermissions permissions = ctx.channel().attr(ChannelAttributes.AUTH_PERMISSIONS).get();
            if (!DefaultPermissionsEvaluator.checkWillPublish(permissions, msg.getWillPublish())) {

                //will is not authorized, disconnect client
                connackWillNotAuthorized(ctx, msg, null, null, null);

                return true;
            }
        }
        return false;
    }

    private void connackWillNotAuthorized(@NotNull final ChannelHandlerContext ctx, @NotNull final CONNECT msg,
                                          @Nullable final DisconnectReasonCode disconnectReasonCode,
                                          @Nullable final AckReasonCode ackReasonCode, @Nullable final String reasonString) {

        Mqtt5ConnAckReasonCode connAckReasonCode = disconnectReasonCode != null ?
                Mqtt5ConnAckReasonCode.fromDisconnectReasonCode(disconnectReasonCode) : null;

        if (connAckReasonCode == null) {
            connAckReasonCode = ackReasonCode != null ?
                    Mqtt5ConnAckReasonCode.fromAckReasonCode(ackReasonCode) : Mqtt5ConnAckReasonCode.NOT_AUTHORIZED;
        }

        final String usedReasonString = reasonString != null ? reasonString : "Will Publish is not authorized for topic '"
                + msg.getWillPublish().getTopic() + "' with QoS '" + msg.getWillPublish().getQos()
                + "' and retain '" + msg.getWillPublish().isRetain() + "'";

        mqttConnacker.connackError(ctx.channel(),
                "A client (IP: {}) sent a CONNECT message with an not authorized Will Publish to topic '"
                        + msg.getWillPublish().getTopic() + "' with QoS '" + msg.getWillPublish().getQos().getQosNumber()
                        + "' and retain '" + msg.getWillPublish().isRetain() + "'.",
                "Sent a CONNECT message with an not authorized Will Publish to topic '" +
                        msg.getWillPublish().getTopic() + "' with QoS '" + msg.getWillPublish().getQos().getQosNumber()
                        + "' and retain '" + msg.getWillPublish().isRetain() + "'",
                connAckReasonCode, Mqtt3ConnAckReturnCode.REFUSED_NOT_AUTHORIZED, usedReasonString);
    }

    private void afterTakeover(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT msg) {
        channelPersistence.persist(msg.getClientIdentifier(), ctx.channel());

        if (msg.isCleanStart()) {
            ctx.fireUserEventTriggered(new ConnectPersistenceUpdateHandler.StartConnectPersistence(msg, false,
                    msg.getSessionExpiryInterval()));
        } else {
            final boolean existent = clientSessionPersistence.isExistent(msg.getClientIdentifier());

            final long sessionExpiryInterval =
                    msg.getSessionExpiryInterval() > configuredSessionExpiryInterval ?
                            configuredSessionExpiryInterval : msg.getSessionExpiryInterval();
            ctx.fireUserEventTriggered(
                    new ConnectPersistenceUpdateHandler.StartConnectPersistence(msg, existent,
                            sessionExpiryInterval));
        }
    }

    private void afterPersistSession(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT msg, final boolean sessionPresent) {

        // In case the clients session expired while it was disconnected, the cache will be invalidated before the client connects.
        // This is sufficient since messages for shared subscriptions are not queued for specific clients.
        sharedSubscriptionService.invalidateSharedSubscriptionCache(msg.getClientIdentifier());

        addKeepAliveHandler(ctx, msg);

        sendConnackSuccess(ctx, msg, sessionPresent);

        eventLog.clientConnected(ctx.channel());

        //We're removing ourselves
        try {
            ctx.pipeline().remove(this);
        } catch (final NoSuchElementException e) {
            //noop since handler has already been removed
        }
        ctx.fireChannelRead(msg);
    }

    private void sendConnackSuccess(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT msg, final boolean sessionPresent) {

        final ChannelFuture connackSent;

        ctx.channel().attr(ChannelAttributes.CONNECT_MESSAGE).set(msg);

        if (ProtocolVersion.MQTTv5 == msg.getProtocolVersion()) {

            final CONNACK connack = buildMqtt5Connack(ctx.channel(), msg, sessionPresent);
            connackSent = ctx.writeAndFlush(connack);

        } else {
            ctx.channel().attr(ChannelAttributes.CLIENT_SESSION_EXPIRY_INTERVAL).set(msg.getSessionExpiryInterval());
            if (sessionPresent) {
                connackSent = ctx.writeAndFlush(ConnackMessages.ACCEPTED_MSG_SESS_PRESENT);
            } else {
                connackSent = ctx.writeAndFlush(ConnackMessages.ACCEPTED_MSG_NO_SESS);
            }
        }

        connackSent.addListener(connackSentListener);
        //send out queued messages (from inflight and client-session queue) for client after connack is sent
        connackSent.addListener(new PollInflightMessageListener(publishPollService, ctx.channel().attr(ChannelAttributes.CLIENT_ID).get()));
    }

    private @NotNull CONNACK buildMqtt5Connack(final @NotNull Channel channel, final @NotNull CONNECT msg, final boolean sessionPresent) {
        final CONNACK.Mqtt5Builder builder = new CONNACK.Mqtt5Builder()
                .withSessionPresent(sessionPresent)
                .withReasonCode(Mqtt5ConnAckReasonCode.SUCCESS)
                .withReceiveMaximum(configurationService.mqttConfiguration().serverReceiveMaximum())
                .withSubscriptionIdentifierAvailable(configurationService.mqttConfiguration().subscriptionIdentifierEnabled())
                .withMaximumPacketSize(configurationService.mqttConfiguration().maxPacketSize())
                .withWildcardSubscriptionAvailable(configurationService.mqttConfiguration().wildcardSubscriptionsEnabled())
                .withSharedSubscriptionAvailable(configurationService.mqttConfiguration().sharedSubscriptionsEnabled())
                .withMaximumQoS(configurationService.mqttConfiguration().maximumQos())
                .withRetainAvailable(configurationService.mqttConfiguration().retainedMessagesEnabled());

        final boolean overridden = msg.getSessionExpiryInterval() > configuredSessionExpiryInterval;
        final long sessionExpiryInterval = overridden ? configuredSessionExpiryInterval : msg.getSessionExpiryInterval();

        if (overridden) {
            builder.withSessionExpiryInterval(sessionExpiryInterval);
        }

        //when client identifier assigned, send it in CONNACK
        final Boolean clientIdAssigned = channel.attr(ChannelAttributes.CLIENT_ID_ASSIGNED).get();
        if (clientIdAssigned != null && clientIdAssigned) {
            builder.withAssignedClientIdentifier(msg.getClientIdentifier());
        }

        //Set max packet size to send to channel
        if (msg.getMaximumPacketSize() <= DEFAULT_MAXIMUM_PACKET_SIZE_NO_LIMIT) {
            channel.attr(ChannelAttributes.MAX_PACKET_SIZE_SEND).set(msg.getMaximumPacketSize());
        }

        //send server keep alive max when connect keep alive is zero and zero is not allowed or keep alive > server keep alive maximum
        if ((msg.getKeepAlive() == 0 && !allowZeroKeepAlive) || (msg.getKeepAlive() > serverKeepAliveMaximum)) {
            builder.withServerKeepAlive(serverKeepAliveMaximum);
            channel.attr(ChannelAttributes.CONNECT_KEEP_ALIVE).set(serverKeepAliveMaximum);
        } else {
            builder.withServerKeepAlive(KEEP_ALIVE_NOT_SET);
            channel.attr(ChannelAttributes.CONNECT_KEEP_ALIVE).set(msg.getKeepAlive());
        }

        //init Topic Alias Mapping if maximum is greater than zero and aliases are available
        if (topicAliasMaximum > 0 && topicAliasLimiter.aliasesAvailable()) {
            channel.attr(ChannelAttributes.TOPIC_ALIAS_MAPPING).set(new String[topicAliasMaximum]);
            builder.withTopicAliasMaximum(topicAliasMaximum);
            topicAliasLimiter.initUsage(topicAliasMaximum);
        }

        //Set session expiry interval to channel for DISCONNECT
        channel.attr(ChannelAttributes.CLIENT_SESSION_EXPIRY_INTERVAL).set(sessionExpiryInterval);

        //set userproperties from auth to connack
        final Mqtt5UserProperties userPropertiesFromAuth =
                channel.attr(ChannelAttributes.AUTH_USER_PROPERTIES).getAndSet(null);
        if (userPropertiesFromAuth != null) {
            builder.withUserProperties(userPropertiesFromAuth);
        }

        return builder.build();
    }

    @NotNull
    private ListenableFuture<Void> disconnectClientWithSameClientId(final @NotNull CONNECT msg, final @NotNull ChannelHandlerContext ctx, final int retry) {

        final Lock lock = stripedLock.get(msg.getClientIdentifier());
        lock.lock();

        try {
            final Channel oldClient = channelPersistence.get(msg.getClientIdentifier());

            if (oldClient != null) {
                final Boolean takeOver = oldClient.attr(ChannelAttributes.TAKEN_OVER).get();
                final SettableFuture<Void> disconnectFuture = oldClient.attr(ChannelAttributes.DISCONNECT_FUTURE).get();
                // We have to check if the old client is currently taken over
                // Otherwise we could takeover the same client twice
                if (takeOver != null && takeOver && retry < MAX_TAKEOVER_RETRIES) {
                    final int nextRetry = retry + 1;
                    // The client is currently taken over
                    if (disconnectFuture != null) {
                        // Retry until the previous takeover is done
                        final SettableFuture<Void> resultFuture = SettableFuture.create();
                        Futures.addCallback(disconnectFuture, new FutureCallback<>() {
                            @Override
                            public void onSuccess(final Void result) {
                                resultFuture.setFuture(disconnectClientWithSameClientId(msg, ctx, nextRetry));
                            }

                            @Override
                            public void onFailure(final @NotNull Throwable t) {
                                resultFuture.setException(t);
                            }
                        }, ctx.executor());
                        return resultFuture;
                    }
                }

                log.debug("Disconnecting already connected client with id {} because another client connects with that id",
                        msg.getClientIdentifier());

                oldClient.attr(ChannelAttributes.TAKEN_OVER).set(true);
                eventLog.clientWasDisconnected(oldClient, "Another client connected with the same client id");
                if (disconnectFuture != null) {
                    // The disconnect future is not set in case the client is not fully connected yet
                    oldClient.close();
                    Checkpoints.checkpoint("ClientTakeOverDisconnected");
                    return disconnectFuture;
                } else {
                    final SettableFuture<Void> resultFuture = SettableFuture.create();
                    final ChannelFuture channelFuture = oldClient.close();
                    channelFuture.addListener(future -> resultFuture.set(null));
                    Checkpoints.checkpoint("ClientTakeOverDisconnected");
                    return resultFuture;
                }
            }
            return Futures.immediateFuture(null);
        } finally {
            lock.unlock();
        }
    }

    private void removeNoConnectIdleHandler(final @NotNull ChannelHandlerContext ctx) {
        try {
            ctx.pipeline().remove(NEW_CONNECTION_IDLE_HANDLER);
            ctx.pipeline().remove(NO_CONNECT_IDLE_EVENT_HANDLER);
        } catch (final NoSuchElementException ex) {
            //no problem, because if these handlers are not in the pipeline anyway, we still get the expected result here
            log.trace("Not able to remove no connect idle handler");
        }
    }

    private void addKeepAliveHandler(final @NotNull ChannelHandlerContext ctx, final @NotNull CONNECT msg) {

        if (msg.getKeepAlive() > 0) {

            // The MQTT spec defines a 1.5 grace period
            final Double keepAliveValue = msg.getKeepAlive() * getGracePeriod();
            log.trace("Client specified a keepAlive value of {}s. The maximum timeout before disconnecting is {}s", msg.getKeepAlive(), keepAliveValue);
            ctx.pipeline().addFirst(MQTT_KEEPALIVE_IDLE_NOTIFIER_HANDLER, new IdleStateHandler(keepAliveValue.intValue(), 0, 0, TimeUnit.SECONDS));
            ctx.pipeline().addAfter(MQTT_KEEPALIVE_IDLE_NOTIFIER_HANDLER, MQTT_KEEPALIVE_IDLE_HANDLER, new KeepAliveIdleHandler(eventLog));
        }
    }

    private double getGracePeriod() {
        return InternalConfigurations.MQTT_CONNECTION_KEEP_ALIVE_FACTOR;
    }

}
