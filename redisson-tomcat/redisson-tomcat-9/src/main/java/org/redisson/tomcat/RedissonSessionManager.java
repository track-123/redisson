/**
 * Copyright (c) 2013-2019 Nikita Koksharov
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
package org.redisson.tomcat;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import javax.servlet.http.HttpSession;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;

/**
 * Redisson Session Manager for Apache Tomcat
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonSessionManager extends ManagerBase {

    public enum ReadMode {REDIS, MEMORY}
    public enum UpdateMode {DEFAULT, AFTER_REQUEST}
    
    private final Log log = LogFactory.getLog(RedissonSessionManager.class);
    
    private RedissonClient redisson;
    private String configPath;
    
    private ReadMode readMode = ReadMode.REDIS;
    private UpdateMode updateMode = UpdateMode.DEFAULT;

    private String keyPrefix = "";

    private final String nodeId = UUID.randomUUID().toString();

    public String getNodeId() { return nodeId; }

    public String getUpdateMode() {
        return updateMode.toString();
    }

    public void setUpdateMode(String updateMode) {
        this.updateMode = UpdateMode.valueOf(updateMode);
    }

    public String getReadMode() {
        return readMode.toString();
    }

    public void setReadMode(String readMode) {
        this.readMode = ReadMode.valueOf(readMode);
    }
    
    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }
    
    public String getConfigPath() {
        return configPath;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    @Override
    public String getName() {
        return RedissonSessionManager.class.getSimpleName();
    }
    
    @Override
    public void load() throws ClassNotFoundException, IOException {
    }

    @Override
    public void unload() throws IOException {
    }

    @Override
    public Session createSession(String sessionId) {
        RedissonSession session = (RedissonSession) createEmptySession();
        
        session.setNew(true);
        session.setValid(true);
        session.setCreationTime(System.currentTimeMillis());
        session.setMaxInactiveInterval(getContext().getSessionTimeout() * 60);

        if (sessionId == null) {
            sessionId = generateSessionId();
        }
        
        session.setManager(this);
        session.setId(sessionId);
        
        return session;
    }

    public RMap<String, Object> getMap(String sessionId) {
        String separator = keyPrefix == null || keyPrefix.isEmpty() ? "" : ":";
        String name = keyPrefix + separator + "redisson:tomcat_session:" + sessionId;
        return redisson.getMap(name, new CompositeCodec(StringCodec.INSTANCE, redisson.getConfig().getCodec()));
    }

    public RTopic getTopic() {
        String separator = keyPrefix == null || keyPrefix.isEmpty() ? "" : ":";
        final String name = keyPrefix + separator + "redisson:tomcat_session_updates:" + getContext().getName();
        return redisson.getTopic(name);
    }
    
    @Override
    public Session findSession(String id) throws IOException {
        Session result = super.findSession(id);
        if (result == null) {
            if (id != null) {
                Map<String, Object> attrs = new HashMap<String, Object>();
                try {
                    attrs = getMap(id).getAll(RedissonSession.ATTRS);
                } catch (Exception e) {
                    log.error("Can't read session object by id: " + id, e);
                }
                
                RedissonSession session = (RedissonSession) createEmptySession();
                session.load(attrs);
                session.setId(id);
                session.setManager(this);
                
                if (session.isValid()) {
                    if (session.isNew()) {
                        session.tellNew();
                    }
                    super.add(session);
                }
                
                session.access();
                session.endAccess();
                return session;
            }
            return null;
        }

        result.access();
        result.endAccess();
        
        return result;
    }
    
    @Override
    public Session createEmptySession() {
        return new RedissonSession(this, readMode, updateMode);
    }
    
    @Override
    public void remove(Session session, boolean update) {
        super.remove(session, update);
        
        if (session.getIdInternal() != null) {
            ((RedissonSession)session).delete();
        }
    }
    
    @Override
    public void add(Session session) {
        super.add(session);
        ((RedissonSession)session).save();
    }
    
    public RedissonClient getRedisson() {
        return redisson;
    }
    
    @Override
    protected void startInternal() throws LifecycleException {
        super.startInternal();
        redisson = buildClient();
        
        final ClassLoader applicationClassLoader;
        if (Thread.currentThread().getContextClassLoader() != null) {
            applicationClassLoader = Thread.currentThread().getContextClassLoader();
        } else {
            applicationClassLoader = getClass().getClassLoader();
        }
        
        Codec codec = redisson.getConfig().getCodec();
        Codec codecToUse;
        try {
            codecToUse = codec.getClass()
                    .getConstructor(ClassLoader.class, codec.getClass())
                    .newInstance(applicationClassLoader, codec);
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
        
        if (updateMode == UpdateMode.AFTER_REQUEST) {
            getEngine().getPipeline().addValve(new UpdateValve(this));
        }
        
        if (readMode == ReadMode.MEMORY) {
            RTopic updatesTopic = getTopic();
            updatesTopic.addListener(AttributeMessage.class, new MessageListener<AttributeMessage>() {
                
                @Override
                public void onMessage(CharSequence channel, AttributeMessage msg) {
                    try {
                        // TODO make it thread-safe
                        RedissonSession session = (RedissonSession) RedissonSessionManager.super.findSession(msg.getSessionId());
                        if (session != null && !msg.getNodeId().equals(nodeId)) {
                            if (msg instanceof AttributeRemoveMessage) {
                                for (String name : ((AttributeRemoveMessage)msg).getNames()) {
                                    session.superRemoveAttributeInternal(name, true);
                                }
                            }

                            if (msg instanceof AttributesClearMessage) {
                                RedissonSessionManager.super.remove(session, false);
                            }
                            
                            if (msg instanceof AttributesPutAllMessage) {
                                AttributesPutAllMessage m = (AttributesPutAllMessage) msg;
                                for (Entry<String, Object> entry : m.getAttrs(codecToUse.getMapValueDecoder()).entrySet()) {
                                    session.superSetAttribute(entry.getKey(), entry.getValue(), true);
                                }
                            }
                            
                            if (msg instanceof AttributeUpdateMessage) {
                                AttributeUpdateMessage m = (AttributeUpdateMessage)msg;
                                session.superSetAttribute(m.getName(), m.getValue(codecToUse.getMapValueDecoder()), true);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Can't handle topic message", e);
                    }
                }
            });
        }
        
        setState(LifecycleState.STARTING);
    }

    protected RedissonClient buildClient() throws LifecycleException {
        Config config = null;
        try {
            config = Config.fromJSON(new File(configPath), getClass().getClassLoader());
        } catch (IOException e) {
            // trying next format
            try {
                config = Config.fromYAML(new File(configPath), getClass().getClassLoader());
            } catch (IOException e1) {
                log.error("Can't parse json config " + configPath, e);
                throw new LifecycleException("Can't parse yaml config " + configPath, e1);
            }
        }
        
        try {
            return Redisson.create(config);
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
    }

    @Override
    protected void stopInternal() throws LifecycleException {
        super.stopInternal();
        
        setState(LifecycleState.STOPPING);
        
        try {
            shutdownRedisson();
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
        
    }

    protected void shutdownRedisson() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    public void store(HttpSession session) throws IOException {
        if (session == null) {
            return;
        }
        
        RedissonSession sess = (RedissonSession) super.findSession(session.getId());
        if (sess != null) {
            sess.access();
            sess.endAccess();
            sess.save();
        }
    }
    
}
