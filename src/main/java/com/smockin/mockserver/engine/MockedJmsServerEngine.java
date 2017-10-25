package com.smockin.mockserver.engine;

import com.smockin.admin.exception.RecordNotFoundException;
import com.smockin.admin.persistence.dao.JmsQueueMockDAO;
import com.smockin.admin.persistence.entity.JmsQueueMock;
import com.smockin.admin.persistence.entity.ServerConfig;
import com.smockin.admin.persistence.enums.ServerTypeEnum;
import com.smockin.mockserver.dto.MockServerState;
import com.smockin.mockserver.dto.MockedServerConfigDTO;
import com.smockin.mockserver.exception.MockServerException;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.jms.*;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Created by mgallina.
 */
@Service
public class MockedJmsServerEngine implements MockServerEngine<MockedServerConfigDTO, List<JmsQueueMock>> {

    private final Logger logger = LoggerFactory.getLogger(MockedJmsServerEngine.class);

    @Autowired
    private JmsQueueMockDAO jmsQueueMockDAO;

    private BrokerService broker = null;
    private ActiveMQConnectionFactory connectionFactory = null; // NOTE this is thread safe
    private final Object monitor = new Object();
    private MockServerState serverState = new MockServerState(false, 0);

    @Override
    public void start(final MockedServerConfigDTO config, final List<JmsQueueMock> data) throws MockServerException {

        // Invoke all lazily loaded data and detach entity.
        invokeAndDetachData(data);

        // Build JMS broker
        initServerConfig(config);

        // Define JMS queues
        buildQueues(data);

        // Start JMS Broker
        initServer(config.getPort());

    }

    public MockServerState getCurrentState() throws MockServerException {
        synchronized (monitor) {
            return serverState;
        }
    }

    @Override
    public void shutdown() throws MockServerException {

        try {

            synchronized (monitor) {

                connectionFactory = null;

                if (broker != null)
                    broker.stop();

                serverState.setRunning(false);
            }

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    public void clearQueue(final String queueName) {

        ActiveMQConnection connection = null;

        try {

            connection = (ActiveMQConnection) connectionFactory.createConnection();
            connection.destroyDestination(new ActiveMQQueue(queueName));

        } catch (JMSException ex) {
            logger.error("clearing all message on queue " + queueName, ex);
        } finally {

            if (connection != null) {
                try {
                    connection.close();
                } catch (JMSException ex) {
                    logger.error("Closing JMS connection", ex);
                }
            }

        }

    }

    public void sendTextMessage(final String queueName, final String textBody, final long timeToLive) {

        Connection connection = null;
        Session session = null;
        MessageProducer producer = null;

        try {

            if (!getCurrentState().isRunning()) {
                return;
            }

            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            producer = session.createProducer(session.createQueue(queueName));
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
            producer.send(session.createTextMessage(textBody));
            producer.setTimeToLive(timeToLive);

        } catch (MockServerException | JMSException ex) {
            logger.error("Pushing message to queue " + queueName, ex);
        } finally {

            if (producer != null) {
                try {
                    producer.close();
                } catch (JMSException ex) {
                    logger.error("Closing JMS producer", ex);
                }
            }

            if (session != null) {
                try {
                    session.close();
                } catch (JMSException ex) {
                    logger.error("Closing JMS session", ex);
                }
            }

            if (connection != null) {
                try {
                    connection.close();
                } catch (JMSException ex) {
                    logger.error("Closing JMS connection", ex);
                }
            }

        }

    }

    void initServerConfig(final MockedServerConfigDTO config) throws MockServerException {
        logger.debug("initServerConfig called");

        try {

            // Configure the MQ broker
            synchronized (monitor) {
                broker = new BrokerService();
                broker.setPersistent(false);
                broker.setUseJmx(false);
                broker.addConnector(config.getNativeProperties().get("BROKER_URL") + config.getPort());
            }

            connectionFactory = new ActiveMQConnectionFactory(config.getNativeProperties().get("BROKER_URL") + config.getPort());
            connectionFactory.setMaxThreadPoolSize(config.getMaxThreads());
            connectionFactory.setRejectedTaskHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    void initServer(final int port) throws MockServerException {
        logger.debug("initServer called");

        try {

            synchronized (monitor) {
                broker.start();

                serverState.setRunning(true);
                serverState.setPort(port);
            }

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    @Transactional
    void invokeAndDetachData(final List<JmsQueueMock> mocks) {

        for (JmsQueueMock mock : mocks) {

            // Invoke lazily Loaded rules and definitions whilst in this active transaction before
            // the entity is detached below.
            mock.getDefinitions().size();

            // Important!
            // Detach all JPA entity beans from EntityManager Context, so they can be
            // continually accessed again here as a simple data bean
            // within each request to the mocked JMS endpoint.
            jmsQueueMockDAO.detach(mock);
        }

    }

    // Expects JmsQueueMock to be detached
    void buildQueues(final List<JmsQueueMock> mocks) throws MockServerException {
        logger.debug("buildQueues called");

        synchronized (monitor) {
            mocks.forEach(mock ->
                    broker.setDestinations(new ActiveMQDestination[] { new ActiveMQQueue(mock.getName()) })
            );
        }

    }

}
