/*
 * Copyright 2015 - 2022 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.session;

import io.netty.channel.Channel;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.Context;
import org.traccar.Main;
import org.traccar.Protocol;
import org.traccar.config.Keys;
import org.traccar.handler.events.MotionEventHandler;
import org.traccar.handler.events.OverspeedEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ConnectionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionManager.class);

    private final long deviceTimeout;
    private final boolean updateDeviceState;

    private final Map<Long, DeviceSession> sessionsByDeviceId = new ConcurrentHashMap<>();
    private final Map<Endpoint, Map<String, DeviceSession>> sessionsByEndpoint = new ConcurrentHashMap<>();

    private final CacheManager cacheManager;

    private final Map<Long, Set<UpdateListener>> listeners = new ConcurrentHashMap<>();
    private final Map<Long, Timeout> timeouts = new ConcurrentHashMap<>();

    private final Timer timer;

    public ConnectionManager() {
        deviceTimeout = Context.getConfig().getLong(Keys.STATUS_TIMEOUT) * 1000;
        updateDeviceState = Context.getConfig().getBoolean(Keys.STATUS_UPDATE_DEVICE_STATE);
        timer = Main.getInjector().getInstance(Timer.class);
        cacheManager = Main.getInjector().getInstance(CacheManager.class);
    }

    public DeviceSession getDeviceSession(long deviceId) {
        return sessionsByDeviceId.get(deviceId);
    }

    public DeviceSession getDeviceSession(
            Protocol protocol, Channel channel, SocketAddress remoteAddress,
            String... uniqueIds) throws StorageException {

        Endpoint endpoint = new Endpoint(channel, remoteAddress);
        Map<String, DeviceSession> endpointSessions = sessionsByEndpoint.getOrDefault(
                endpoint, new ConcurrentHashMap<>());
        if (uniqueIds.length > 0) {
            for (String uniqueId : uniqueIds) {
                DeviceSession deviceSession = endpointSessions.get(uniqueId);
                if (deviceSession != null) {
                    return deviceSession;
                }
            }
        } else {
            return endpointSessions.values().stream().findAny().orElse(null);
        }

        Device device = null;
        try {
            for (String uniqueId : uniqueIds) {
                device = Context.getIdentityManager().getByUniqueId(uniqueId);
                if (device != null) {
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Find device error", e);
        }

        if (device == null && Context.getConfig().getBoolean(Keys.DATABASE_REGISTER_UNKNOWN)) {
            device = Context.getIdentityManager().addUnknownDevice(uniqueIds[0]);
        }

        if (device != null && !device.getDisabled()) {
            DeviceSession oldSession = sessionsByDeviceId.remove(device.getId());
            if (oldSession != null) {
                Endpoint oldEndpoint = new Endpoint(oldSession.getChannel(), oldSession.getRemoteAddress());
                Map<String, DeviceSession> oldEndpointSessions = sessionsByEndpoint.get(oldEndpoint);
                if (oldEndpointSessions.size() > 1) {
                    oldEndpointSessions.remove(device.getUniqueId());
                } else {
                    sessionsByEndpoint.remove(oldEndpoint);
                }
            }

            DeviceSession deviceSession = new DeviceSession(
                    device.getId(), device.getUniqueId(), protocol, channel, remoteAddress);
            endpointSessions.put(device.getUniqueId(), deviceSession);
            sessionsByEndpoint.put(endpoint, endpointSessions);
            sessionsByDeviceId.put(device.getId(), deviceSession);
            cacheManager.addDevice(device.getId());

            return deviceSession;
        } else {
            LOGGER.warn((device == null ? "Unknown" : "Disabled") + " device - " + String.join(" ", uniqueIds)
                    + " (" + ((InetSocketAddress) remoteAddress).getHostString() + ")");
            return null;
        }
    }

    public void deviceDisconnected(Channel channel) {
        Endpoint endpoint = new Endpoint(channel, channel.remoteAddress());
        Map<String, DeviceSession> endpointSessions = sessionsByEndpoint.remove(endpoint);
        if (endpointSessions != null) {
            for (DeviceSession deviceSession : endpointSessions.values()) {
                updateDevice(deviceSession.getDeviceId(), Device.STATUS_OFFLINE, null);
                sessionsByDeviceId.remove(deviceSession.getDeviceId());
                cacheManager.removeDevice(deviceSession.getDeviceId());
            }
        }
    }

    public void deviceUnknown(long deviceId) {
        updateDevice(deviceId, Device.STATUS_UNKNOWN, null);
        DeviceSession deviceSession = sessionsByDeviceId.remove(deviceId);
        cacheManager.removeDevice(deviceId);
        if (deviceSession != null) {
            Endpoint endpoint = new Endpoint(deviceSession.getChannel(), deviceSession.getRemoteAddress());
            sessionsByEndpoint.computeIfPresent(endpoint, (e, sessions) -> {
                sessions.remove(deviceSession.getUniqueId());
                return sessions.isEmpty() ? null : sessions;
            });
        }
    }

    public void updateDevice(final long deviceId, String status, Date time) {
        Device device = Context.getIdentityManager().getById(deviceId);
        if (device == null) {
            return;
        }

        String oldStatus = device.getStatus();
        device.setStatus(status);

        if (!status.equals(oldStatus)) {
            String eventType;
            Map<Event, Position> events = new HashMap<>();
            switch (status) {
                case Device.STATUS_ONLINE:
                    eventType = Event.TYPE_DEVICE_ONLINE;
                    break;
                case Device.STATUS_UNKNOWN:
                    eventType = Event.TYPE_DEVICE_UNKNOWN;
                    if (updateDeviceState) {
                        events.putAll(updateDeviceState(deviceId));
                    }
                    break;
                default:
                    eventType = Event.TYPE_DEVICE_OFFLINE;
                    if (updateDeviceState) {
                        events.putAll(updateDeviceState(deviceId));
                    }
                    break;
            }
            events.put(new Event(eventType, deviceId), null);
            Context.getNotificationManager().updateEvents(events);
        }

        Timeout timeout = timeouts.remove(deviceId);
        if (timeout != null) {
            timeout.cancel();
        }

        if (time != null) {
            device.setLastUpdate(time);
        }

        if (status.equals(Device.STATUS_ONLINE)) {
            timeouts.put(deviceId, timer.newTimeout(timeout1 -> {
                if (!timeout1.isCancelled()) {
                    deviceUnknown(deviceId);
                }
            }, deviceTimeout, TimeUnit.MILLISECONDS));
        }

        try {
            Context.getDeviceManager().updateDeviceStatus(device);
        } catch (StorageException e) {
            LOGGER.warn("Update device status error", e);
        }

        updateDevice(device);
    }

    public Map<Event, Position> updateDeviceState(long deviceId) {
        DeviceState deviceState = Context.getDeviceManager().getDeviceState(deviceId);
        Map<Event, Position> result = new HashMap<>();

        Map<Event, Position> event = Main.getInjector()
                .getInstance(MotionEventHandler.class).updateMotionState(deviceState);
        if (event != null) {
            result.putAll(event);
        }

        event = Main.getInjector().getInstance(OverspeedEventHandler.class)
                .updateOverspeedState(deviceState, Context.getDeviceManager().
                        lookupAttributeDouble(deviceId, OverspeedEventHandler.ATTRIBUTE_SPEED_LIMIT, 0, true, false));
        if (event != null) {
            result.putAll(event);
        }

        return result;
    }

    public synchronized void sendKeepalive() {
        for (Set<UpdateListener> userListeners : listeners.values()) {
            for (UpdateListener listener : userListeners) {
                listener.onKeepalive();
            }
        }
    }

    public synchronized void updateDevice(Device device) {
        for (long userId : Context.getPermissionsManager().getDeviceUsers(device.getId())) {
            if (listeners.containsKey(userId)) {
                for (UpdateListener listener : listeners.get(userId)) {
                    listener.onUpdateDevice(device);
                }
            }
        }
    }

    public synchronized void updatePosition(Position position) {
        long deviceId = position.getDeviceId();

        for (long userId : Context.getPermissionsManager().getDeviceUsers(deviceId)) {
            if (listeners.containsKey(userId)) {
                for (UpdateListener listener : listeners.get(userId)) {
                    listener.onUpdatePosition(position);
                }
            }
        }
    }

    public synchronized void updateEvent(long userId, Event event) {
        if (listeners.containsKey(userId)) {
            for (UpdateListener listener : listeners.get(userId)) {
                listener.onUpdateEvent(event);
            }
        }
    }

    public interface UpdateListener {
        void onKeepalive();
        void onUpdateDevice(Device device);
        void onUpdatePosition(Position position);
        void onUpdateEvent(Event event);
    }

    public synchronized void addListener(long userId, UpdateListener listener) {
        if (!listeners.containsKey(userId)) {
            listeners.put(userId, new HashSet<>());
        }
        listeners.get(userId).add(listener);
    }

    public synchronized void removeListener(long userId, UpdateListener listener) {
        if (!listeners.containsKey(userId)) {
            listeners.put(userId, new HashSet<>());
        }
        listeners.get(userId).remove(listener);
    }

}
