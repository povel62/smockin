package com.smockin.mockserver.engine;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smockin.admin.dto.HttpClientCallDTO;
import com.smockin.admin.dto.response.HttpClientResponseDTO;
import com.smockin.admin.exception.ValidationException;
import com.smockin.admin.persistence.dao.RestfulMockDAO;
import com.smockin.admin.persistence.entity.RestfulMock;
import com.smockin.admin.persistence.entity.RestfulMockDefinitionOrder;
import com.smockin.admin.persistence.entity.RestfulMockDefinitionRule;
import com.smockin.admin.persistence.enums.RestMethodEnum;
import com.smockin.admin.persistence.enums.RestMockTypeEnum;
import com.smockin.admin.persistence.enums.SmockinUserRoleEnum;
import com.smockin.admin.service.HttpClientService;
import com.smockin.mockserver.exception.InboundParamMatchException;
import com.smockin.mockserver.service.HttpProxyService;
import com.smockin.mockserver.service.InboundParamMatchService;
import com.smockin.mockserver.service.JavaScriptResponseHandler;
import com.smockin.mockserver.service.MockOrderingCounterService;
import com.smockin.mockserver.service.RuleEngine;
import com.smockin.mockserver.service.ServerSideEventService;
import com.smockin.mockserver.service.StatefulService;
import com.smockin.mockserver.service.dto.RestfulResponseDTO;

import spark.Request;
import spark.Response;

import javax.servlet.http.HttpServletRequest;

/**
 * Created by mgallina.
 */
@Service
@Transactional(readOnly = true)
public class MockedRestServerEngineUtils {

    private final Logger logger = LoggerFactory.getLogger(MockedRestServerEngineUtils.class);

    @Autowired
    private RestfulMockDAO restfulMockDAO;

    @Autowired
    private MockOrderingCounterService mockOrderingCounterService;

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private HttpProxyService proxyService;

    @Autowired
    private JavaScriptResponseHandler javaScriptResponseHandler;

    @Autowired
    private InboundParamMatchService inboundParamMatchService;

    @Autowired
    private ServerSideEventService serverSideEventService;

    @Autowired
    private StatefulService statefulService;

    @Autowired
    private HttpClientService httpClientService;

    private Map<String, String> getHeadersInfo(HttpServletRequest request) {
        Map<String, String> map = new HashMap<String, String>();

        Enumeration headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {

            String key = (String) headerNames.nextElement();
            String value = request.getHeader(key);
            System.out.println(key + ": " + value);

            if(!(key.equalsIgnoreCase("Content-Length") || key.equalsIgnoreCase("Host")))
                map.put(key, value);
        }

        return map;
    }

    public Optional<String> loadMockedResponse(final Request request,
                                               final Response response,
                                               final boolean isMultiUserMode) {
        logger.debug("loadMockedResponse called");

        debugInboundRequest(request);

        HttpClientCallDTO http = new HttpClientCallDTO();
        http.setUrl(request.pathInfo());
        http.setMethod(RestMethodEnum.findByName(request.requestMethod()));
        http.setHeaders(getHeadersInfo(request.raw()));
//        http.setHeaders(request.params());

        http.setBody(request.body());
        HttpClientResponseDTO responseDto = null;
        try
        {
          responseDto = httpClientService.handleCall(http, true);
        } catch (ValidationException e)
        {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        
        if(responseDto != null && responseDto.getStatus() != 404) {
            response.status(responseDto.getStatus());
          return  Optional.of(new ResponseEntity<HttpClientResponseDTO>(responseDto, HttpStatus.resolve(responseDto.getStatus())).getBody().getBody());
        }
        
        try {

            final RestfulMock mock = (isMultiUserMode)
                    ? restfulMockDAO.findActiveByMethodAndPathPatternAndTypesForMultiUser(
                            RestMethodEnum.findByName(request.requestMethod()),
                            request.pathInfo(),
                            Arrays.asList(RestMockTypeEnum.PROXY_SSE,
                                    RestMockTypeEnum.PROXY_HTTP,
                                    RestMockTypeEnum.SEQ,
                                    RestMockTypeEnum.RULE,
                                    RestMockTypeEnum.STATEFUL,
                                    RestMockTypeEnum.CUSTOM_JS))
                    : restfulMockDAO.findActiveByMethodAndPathPatternAndTypesForSingleUser(
                            RestMethodEnum.findByName(request.requestMethod()),
                            request.pathInfo(),
                            Arrays.asList(RestMockTypeEnum.PROXY_SSE,
                                          RestMockTypeEnum.PROXY_HTTP,
                                          RestMockTypeEnum.SEQ,
                                          RestMockTypeEnum.RULE,
                                          RestMockTypeEnum.STATEFUL,
                                          RestMockTypeEnum.CUSTOM_JS));

            if (mock == null) {
                logger.debug("no mock was found");
                return Optional.empty();
            }

            debugLoadedMock(mock);

            if (RestMockTypeEnum.PROXY_SSE.equals(mock.getMockType())) {
                return Optional.of(processSSERequest(mock, request, response));
            }

            removeSuspendedResponses(mock);

            return Optional.of(processRequest(mock, request, response));

        } catch (Exception ex) {
            logger.error("Error processing mock request", ex);

            response.status(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.body((ex instanceof IllegalArgumentException) ? ex.getMessage() : "Oops, looks like something went wrong with this mock!");

            return Optional.of("Oops"); // this message does not come through to caller when it is a 500 for some reason, so setting in body above
        }

    }

    String processRequest(final RestfulMock mock, final Request req, final Response res) {
        logger.debug("processRequest called");

        RestfulResponseDTO outcome;

        switch (mock.getMockType()) {
            case RULE:
                outcome = ruleEngine.process(req, mock.getRules());
                break;
            case PROXY_HTTP:
                outcome = proxyService.waitForResponse(req.pathInfo(), mock);
                break;
            case CUSTOM_JS:
                outcome = javaScriptResponseHandler.executeUserResponse(req, mock);
                break;
            case STATEFUL:
                outcome = statefulService.process(req, mock);
                break;
            case SEQ:
            default:
                outcome = mockOrderingCounterService.process(mock);
                break;
        }

        if (outcome == null) {
            // Load in default values
            outcome = getDefault(mock);
        }

        debugOutcome(outcome);

        res.status(outcome.getHttpStatusCode());
        res.type(outcome.getResponseContentType());

        // Apply any response headers
        outcome.getHeaders()
                .entrySet()
                .forEach(e ->
                            res.header(e.getKey(), e.getValue()));

        String response;

        try {
            response = inboundParamMatchService.enrichWithInboundParamMatches(req, mock.getPath(), outcome.getResponseBody(), mock.getCreatedBy().getCtxPath(), mock.getCreatedBy().getId());
            handleLatency(mock);
        } catch (InboundParamMatchException e) {
            logger.error(e.getMessage());
            res.status(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response = e.getMessage();
        }

        if (logger.isDebugEnabled()) {
            logger.debug("final response " + response);
        }

        return StringUtils.defaultIfBlank(response,"");
    }

    RestfulResponseDTO getDefault(final RestfulMock restfulMock) {
        logger.debug("getDefault called");

        if (RestMockTypeEnum.PROXY_HTTP.equals(restfulMock.getMockType())) {
            return new RestfulResponseDTO(HttpStatus.NOT_FOUND.value());
        }

        final RestfulMockDefinitionOrder mockDefOrder = restfulMock.getDefinitions().get(0);
        return new RestfulResponseDTO(mockDefOrder.getHttpStatusCode(), mockDefOrder.getResponseContentType(), mockDefOrder.getResponseBody(), mockDefOrder.getResponseHeaders().entrySet());
    }

    void removeSuspendedResponses(final RestfulMock mock) {
        logger.debug("removeSuspendedResponses called");

        final Iterator<RestfulMockDefinitionOrder> definitionsIter = mock.getDefinitions().iterator();

        while (definitionsIter.hasNext()) {
            final RestfulMockDefinitionOrder d = definitionsIter.next();

            if (d.isSuspend()) {
                definitionsIter.remove();
            }
        }

        final Iterator<RestfulMockDefinitionRule> rulesIter =  mock.getRules().iterator();

        while (rulesIter.hasNext()) {
            final RestfulMockDefinitionRule r = rulesIter.next();

            if (r.isSuspend()) {
                rulesIter.remove();
            }
        }

    }

    String processSSERequest(final RestfulMock mock, final Request req, final Response res) {

        try {
            serverSideEventService.register(buildUserPath(mock), mock.getSseHeartBeatInMillis(), mock.isProxyPushIdOnConnect(), req, res);
        } catch (IOException e) {
            logger.error("Error registering SEE client", e);
        }

        return "";
    }

    private void handleLatency(final RestfulMock mock) {

        if (!mock.isRandomiseLatency()) {
            return;
        }

        long min = (mock.getRandomiseLatencyRangeMinMillis() > 0) ? mock.getRandomiseLatencyRangeMinMillis() : 1000;
        long max = (mock.getRandomiseLatencyRangeMaxMillis() > 0) ? mock.getRandomiseLatencyRangeMaxMillis() : 5000;

        try {
            Thread.sleep(RandomUtils.nextLong(min, (max + 1)));
        } catch (InterruptedException ex) {
            logger.error("Failed to apply randomised latency and prolong current thread execution", ex);
        }

    }

    public String buildUserPath(final RestfulMock mock) {

        if (!SmockinUserRoleEnum.SYS_ADMIN.equals(mock.getCreatedBy().getRole())) {
            return File.separator + mock.getCreatedBy().getCtxPath() + mock.getPath();
        }

        return mock.getPath();
    }

    private void debugInboundRequest(final Request request) {

        if (logger.isDebugEnabled()) {

            logger.debug("inbound request method: " + request.requestMethod());
            logger.debug("inbound request path: " + request.pathInfo());
            logger.debug("inbound request body: " + request.body());

        }

    }

    private void debugLoadedMock(final RestfulMock mock) {

        if (logger.isDebugEnabled()) {

            logger.debug("mock ext id: " + mock.getExtId());
            logger.debug("mock method: " + mock.getMethod());
            logger.debug("mock path: " + mock.getPath());
            logger.debug("mock type: " + mock.getMockType());

        }

    }

    private void debugOutcome(final RestfulResponseDTO outcome) {

        if (logger.isDebugEnabled()) {

            logger.debug("status " + outcome.getHttpStatusCode());
            logger.debug("content type " + outcome.getResponseContentType());
            logger.debug("status " + outcome.getHttpStatusCode());
            logger.debug("response body " + outcome.getResponseBody());

        }

    }

}
