package com.smockin.admin.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.smockin.admin.dto.HttpClientCallDTO;
import com.smockin.admin.dto.response.HttpClientResponseDTO;
import com.smockin.admin.exception.ValidationException;
import com.smockin.mockserver.dto.MockServerState;
import com.smockin.mockserver.exception.MockServerException;
import com.smockin.utils.HttpClientUtils;

/**
 * Created by mgallina.
 */
@Service
public class HttpClientServiceImpl implements HttpClientService {

    private final Logger logger = LoggerFactory.getLogger(HttpClientServiceImpl.class);

    @Value("${smockin.redirect.url}")
    private String redirectUrl;
    
    @Autowired
    private MockedServerEngineService mockedServerEngineService;

    @Override
    public HttpClientResponseDTO handleCall(final HttpClientCallDTO dto, boolean justRedirect) throws ValidationException {
      logger.debug("handleCall called");

      debugDTO(dto);

      validateRequest(dto);

      try {

          final MockServerState state = mockedServerEngineService.getRestServerState();
          HttpClientResponseDTO httpClientResponseDTO;
          
          if (!state.isRunning()) {
              return new HttpClientResponseDTO(HttpStatus.NOT_FOUND.value());
          }
          String url = dto.getUrl();
          dto.setUrl((redirectUrl  + url).replaceAll("(?<!(http:|https:))//{2,}", "/"));
          switch (dto.getMethod()) {
              case GET:
                  httpClientResponseDTO = get(dto);
                  if(!justRedirect && httpClientResponseDTO.getStatus() == 404) {  
                    dto.setUrl("http://localhost:" + state.getPort() + url);
                    httpClientResponseDTO = get(dto);
                  }
                  return httpClientResponseDTO;
              case POST:
                httpClientResponseDTO = post(dto);
                if(!justRedirect && httpClientResponseDTO.getStatus() == 404) {  
                  dto.setUrl("http://localhost:" + state.getPort() + url);
                  httpClientResponseDTO = post(dto);
                }
                return httpClientResponseDTO;
              case PUT:
                httpClientResponseDTO = put(dto);
                if(!justRedirect && httpClientResponseDTO.getStatus() == 404) {  
                  dto.setUrl("http://localhost:" + state.getPort() + url);
                  httpClientResponseDTO = put(dto);
                }
                return httpClientResponseDTO;
              case DELETE:
                httpClientResponseDTO = delete(dto);
                if(!justRedirect && httpClientResponseDTO.getStatus() == 404) {  
                  dto.setUrl("http://localhost:" + state.getPort() + url);
                  httpClientResponseDTO = delete(dto);
                }
                return httpClientResponseDTO;
              case PATCH:
                httpClientResponseDTO = patch(dto);
                if(!justRedirect && httpClientResponseDTO.getStatus() == 404) {  
                  dto.setUrl("http://localhost:" + state.getPort() + url);
                  httpClientResponseDTO = patch(dto);
                }
                return httpClientResponseDTO;
              default:
                  throw new ValidationException("Invalid / Unsupported method: " + dto.getMethod());
          }

      } catch (IOException | MockServerException ex) {
          return new HttpClientResponseDTO(HttpStatus.NOT_FOUND.value());
      }  
    }
    
    @Override
    public HttpClientResponseDTO handleCall(final HttpClientCallDTO dto) throws ValidationException {
      return handleCall(dto, false);
    }

    HttpClientResponseDTO get(final HttpClientCallDTO reqDto) throws IOException {

        final Request request = Request.Get(reqDto.getUrl());

        return executeRequest(request, reqDto.getHeaders());
    }

    HttpClientResponseDTO post(final HttpClientCallDTO reqDto) throws IOException {

        final Request request = Request.Post(reqDto.getUrl());

        HttpClientUtils.handleRequestData(request, reqDto.getHeaders(), reqDto);

        return executeRequest(request, reqDto.getHeaders());
    }

    HttpClientResponseDTO put(final HttpClientCallDTO reqDto) throws IOException {

        final Request request = Request.Put(reqDto.getUrl());

        HttpClientUtils.handleRequestData(request, reqDto.getHeaders(), reqDto);

        return executeRequest(request, reqDto.getHeaders());
    }

    HttpClientResponseDTO delete(final HttpClientCallDTO reqDto) throws IOException {

        final Request request = Request.Delete(reqDto.getUrl());

        return executeRequest(request, reqDto.getHeaders());
    }

    HttpClientResponseDTO patch(final HttpClientCallDTO reqDto) throws IOException {

        final Request request = Request.Patch(reqDto.getUrl())
                .bodyByteArray((reqDto.getBody() != null)?reqDto.getBody().getBytes():null);

        return executeRequest(request, reqDto.getHeaders());
    }

    /**
     *
     * Assumes the request body is not mandatory.
     *
     * @param httpClientCallDTO
     * @throws ValidationException
     *
     */
    void validateRequest(final HttpClientCallDTO httpClientCallDTO) throws ValidationException {

        if (StringUtils.isBlank(httpClientCallDTO.getUrl())) {
            throw new ValidationException("url is required");
        }

        if (httpClientCallDTO.getMethod() == null) {
            throw new ValidationException("method is required");
        }

    }

    void applyRequestHeaders(final Request request, final Map<String, String> requestHeaders) {

        if (requestHeaders == null)
            return;

        requestHeaders.entrySet().forEach(h ->
            request.addHeader(h.getKey(), h.getValue()));

    }

    Map<String, String> extractResponseHeaders(final HttpResponse httpResponse) {

        return new HashMap<String, String>() {
            {
                for (Header h : httpResponse.getAllHeaders()) {
                    put(h.getName(), h.getValue());
                }
            }
        };
    }

    String extractResponseBody(final HttpResponse httpResponse) throws IOException {

        return IOUtils.toString(httpResponse.getEntity().getContent(), StandardCharsets.UTF_8.name());
    }

    HttpClientResponseDTO executeRequest(final Request request, final Map<String, String> requestHeaders) throws IOException {

        applyRequestHeaders(request, requestHeaders);

        final HttpResponse httpResponse = request.execute().returnResponse();

        return new HttpClientResponseDTO(
                httpResponse.getStatusLine().getStatusCode(),
                httpResponse.getEntity().getContentType().getValue(),
                extractResponseHeaders(httpResponse),
                extractResponseBody(httpResponse)
        );
    }

    private void debugDTO(final HttpClientCallDTO dto) {

        if (logger.isDebugEnabled()) {
            logger.debug( "URL : " + dto.getUrl() );
            logger.debug( "METHOD : " + dto.getMethod().name() );
            logger.debug( "BODY : " + dto.getBody() );
            logger.debug( "HEADERS : " );

            dto.getHeaders().entrySet().forEach(h ->
                    logger.debug( h.getKey() +  " : " + h.getValue() ));
        }

    }

}
