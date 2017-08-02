package com.smockin.mockserver.engine;

import com.smockin.admin.persistence.dao.RestfulMockDAO;
import com.smockin.admin.persistence.entity.RestfulMock;
import com.smockin.admin.persistence.entity.RestfulMockDefinitionOrder;
import com.smockin.mockserver.dto.MockServerState;
import com.smockin.mockserver.dto.MockedServerConfigDTO;
import com.smockin.mockserver.exception.MockServerException;
import com.smockin.mockserver.service.MockOrderingCounterService;
import com.smockin.mockserver.service.RuleEngine;
import com.smockin.mockserver.service.dto.RestfulResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.util.List;
import java.util.Map;

/**
 * Created by mgallina.
 */
@Service
public class MockedRestServerEngine implements MockServerEngine<MockedServerConfigDTO, List<RestfulMock>> {

    private final Logger logger = LoggerFactory.getLogger(MockedRestServerEngine.class);

    @Autowired
    private RestfulMockDAO restfulMockDAO;

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private MockOrderingCounterService mockOrderingCounterService;

    private final Object monitor = new Object();
    private MockServerState serverState = new MockServerState(false, 0);

    public void start(final MockedServerConfigDTO config, final List<RestfulMock> mocks) throws MockServerException {
        logger.debug("start called");

        initServerConfig(config);

        buildIndex(mocks);

        buildEndpoints(mocks);

        initServer(config.getPort());

    }

    public MockServerState getCurrentState() throws MockServerException {
        synchronized (monitor) {
            return serverState;
        }
    }

    public void shutdown() throws MockServerException {

        try {

            Spark.stop();

            // Having dug around the source code, 'Spark.stop()' runs off a different thread when stopping the server and removing it's state such as routes, etc.
            // This means that calling 'Spark.port()' immediately after stop, results in an IllegalStateException, as the
            // 'initialized' flag is checked in the current thread and is still marked as true.
            // (The error thrown: java.lang.IllegalStateException: This must be done before route mapping has begun)
            // Short of editing the Spark source to fix this, I have therefore had to add this hack to buy the 'stop' thread time to complete.
            Thread.sleep(3000);

            synchronized (monitor) {
                serverState.setRunning(false);
            }

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    void initServer(final int port) throws MockServerException {
        logger.debug("initServer called");

        try {

            Spark.init();

            // Blocks the current thread (using a CountDownLatch under the hood) until the server is fully initialised.
            Spark.awaitInitialization();

            synchronized (monitor) {
                serverState.setRunning(true);
                serverState.setPort(port);
            }

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    void initServerConfig(final MockedServerConfigDTO config) {
        logger.debug("initServerConfig called");

        if (logger.isDebugEnabled())
            logger.debug(config.toString());

        Spark.port(config.getPort());
        Spark.threadPool(config.getMaxThreads(), config.getMinThreads(), config.getTimeOutMillis());
    }

    @Transactional
    void buildIndex(final List<RestfulMock> mocks) {
        logger.debug("buildIndex called");

        final StringBuilder sb = new StringBuilder("<h2>Smockin REST Service Index</h2>");
        sb.append("<br /><br />");

        // NOTE JPA entity beans are still attached at this stage (See buildEndpoints() below).
        for (RestfulMock m : mocks) {
            sb.append(m.getMethod());
            sb.append(" ");
            sb.append(m.getPath());
            sb.append("<br /><br />");
        }

        Spark.get("/", (req, res) -> {
            res.type("text/html");
            return sb.toString();
        });

    }

    @Transactional
    void buildEndpoints(final List<RestfulMock> mocks) throws MockServerException {
        logger.debug("buildEndpoints called");

        for (RestfulMock mock : mocks) {

            // Invoke lazily Loaded rules and definitions whilst in this active transaction before
            // the entity is detached below.
            mock.getRules().size();
            mock.getDefinitions().size();

            // Important!
            // Detach all JPA entity beans from EntityManager Context, so they can be
            // continually accessed again here as a simple data bean
            // within each request to the mocked REST endpoint.
            restfulMockDAO.detach(mock);

            switch (mock.getMethod()) {
                case GET:

                    Spark.get(mock.getPath(), (req, res) -> {
                        return processRequest(mock, req, res);
                    });

                    break;
                case POST:

                    Spark.post(mock.getPath(), (req, res) -> {
                        return processRequest(mock, req, res);
                    });

                    break;
                case PUT:

                    Spark.put(mock.getPath(), (req, res) -> {
                        return processRequest(mock, req, res);
                    });

                    break;
                case DELETE:

                    Spark.delete(mock.getPath(), (req, res) -> {
                        return processRequest(mock, req, res);
                    });

                   break;

                case PATCH:

                    Spark.patch(mock.getPath(), (req, res) -> {
                        return processRequest(mock, req, res);
                    });

                    break;
                default:
                    throw new MockServerException("Unsupported mock definition method type : " + mock.getMethod());
            }

        }

    }

    String processRequest(final RestfulMock mock, final Request req, final Response res) {

        RestfulResponse outcome;

        switch (mock.getMockType()) {
            case RULE:
                outcome = ruleEngine.process(req, mock.getRules());
                break;
            case SEQ:
            default:
                outcome = mockOrderingCounterService.getNextInSequence(mock);
                break;
        }

        if (outcome == null) {
            // Load in default values
            outcome = getDefault(mock);
        }

        res.status(outcome.getHttpStatusCode());
        res.type(outcome.getResponseContentType());

        // Apply any response headers
        for (Map.Entry<String, String> e : outcome.getHeaders().entrySet()) {
            res.header(e.getKey(), e.getValue());
        }

        return (outcome.getResponseBody() != null)?outcome.getResponseBody():"";
    }

    RestfulResponse getDefault(final RestfulMock restfulMock) {
        final RestfulMockDefinitionOrder mockDefOrder = restfulMock.getDefinitions().get(0);
        return new RestfulResponse(mockDefOrder.getHttpStatusCode(), mockDefOrder.getResponseContentType(), mockDefOrder.getResponseBody(), mockDefOrder.getResponseHeaders().entrySet());
    }

}