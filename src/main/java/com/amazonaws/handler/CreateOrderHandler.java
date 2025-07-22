/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazonaws.handler;

import com.amazonaws.config.DaggerOrderComponent;
import com.amazonaws.config.OrderComponent;
import com.amazonaws.dao.OrderDao;
import com.amazonaws.exception.CouldNotCreateOrderException;
import com.amazonaws.model.Order;
import com.amazonaws.model.request.CreateOrderRequest;
import com.amazonaws.model.response.ErrorMessage;
import com.amazonaws.model.response.GatewayResponse;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.inject.Inject;

public class CreateOrderHandler implements OrderRequestStreamHandler {

        // Predefined error messages with HTTP 400 status code
        private static final ErrorMessage REQUIRE_CUSTOMER_ID_ERROR = new ErrorMessage(
                        "Require customerId to create an order", SC_BAD_REQUEST);
        private static final ErrorMessage REQUIRE_PRETAX_AMOUNT_ERROR = new ErrorMessage(
                        "Require preTaxAmount to create an order", SC_BAD_REQUEST);
        private static final ErrorMessage REQUIRE_POST_TAX_AMOUNT_ERROR = new ErrorMessage(
                        "Require postTaxAmount to create an order", SC_BAD_REQUEST);

        @Inject
        ObjectMapper objectMapper; // Jackson JSON parser (injected by Dagger)
        @Inject
        OrderDao orderDao; // DAO to create and persist orders (injected)
        private final OrderComponent orderComponent; // Dagger DI component

        // This class is invoked when lambda starts because of it's definition in the
        // template.yml file
        public CreateOrderHandler() {
                // Initialize Dagger dependency injection
                orderComponent = DaggerOrderComponent.builder().build();
                orderComponent.inject(this); // injects objectMapper and orderDao
        }

        @Override
        public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
                final JsonNode event;
                try {
                        // Read raw JSON from input stream
                        event = objectMapper.readTree(input);
                } catch (JsonMappingException e) {
                        // Malformed input JSON
                        writeInvalidJsonInStreamResponse(objectMapper, output, e.getMessage());
                        return;
                }

                if (event == null) {
                        writeInvalidJsonInStreamResponse(objectMapper, output, "event was null");
                        return;
                }

                // Extract the "body" node (which contains the actual payload from the client)
                JsonNode createOrderRequestBody = event.findValue("body"); // This represents the lambda event object
                if (createOrderRequestBody == null) {
                        objectMapper.writeValue(output,
                                        new GatewayResponse<>(
                                                        objectMapper.writeValueAsString(new ErrorMessage(
                                                                        "Body was null", SC_BAD_REQUEST)),
                                                        APPLICATION_JSON, SC_BAD_REQUEST));
                        return;
                }

                final CreateOrderRequest request;
                try {
                        // Parse the raw JSON string into a CreateOrderRequest object
                        request = objectMapper.treeToValue(
                                        objectMapper.readTree(createOrderRequestBody.asText()),
                                        CreateOrderRequest.class);
                } catch (JsonParseException | JsonMappingException e) {
                        objectMapper.writeValue(output,
                                        new GatewayResponse<>(
                                                        objectMapper.writeValueAsString(
                                                                        new ErrorMessage(
                                                                                        "Invalid JSON in body: " + e
                                                                                                        .getMessage(),
                                                                                        SC_BAD_REQUEST)),
                                                        APPLICATION_JSON, SC_BAD_REQUEST));
                        return;
                }

                if (request == null) {
                        objectMapper.writeValue(output,
                                        new GatewayResponse<>(objectMapper.writeValueAsString(REQUEST_WAS_NULL_ERROR),
                                                        APPLICATION_JSON, SC_BAD_REQUEST));
                        return;
                }

                // Field validations (basic null checks)
                if (isNullOrEmpty(request.getCustomerId())) {
                        objectMapper.writeValue(output,
                                        new GatewayResponse<>(
                                                        objectMapper.writeValueAsString(REQUIRE_CUSTOMER_ID_ERROR),
                                                        APPLICATION_JSON, SC_BAD_REQUEST));
                        return;
                }
                if (request.getPreTaxAmount() == null) {
                        objectMapper.writeValue(output,
                                        new GatewayResponse<>(
                                                        objectMapper.writeValueAsString(REQUIRE_PRETAX_AMOUNT_ERROR),
                                                        APPLICATION_JSON, SC_BAD_REQUEST));
                        return;
                }
                if (request.getPostTaxAmount() == null) {
                        objectMapper.writeValue(output,
                                        new GatewayResponse<>(
                                                        objectMapper.writeValueAsString(REQUIRE_POST_TAX_AMOUNT_ERROR),
                                                        APPLICATION_JSON, SC_BAD_REQUEST));
                        return;
                }

                try {
                        // Pass the validated request object to DAO to create the order
                        final Order order = orderDao.createOrder(request);

                        // Return 201 response with the created order as JSON
                        objectMapper.writeValue(output,
                                        new GatewayResponse<>(
                                                        objectMapper.writeValueAsString(order), APPLICATION_JSON,
                                                        SC_CREATED));
                } catch (CouldNotCreateOrderException e) {
                        // Failed to persist the order
                        objectMapper.writeValue(output,
                                        new GatewayResponse<>(
                                                        objectMapper.writeValueAsString(
                                                                        new ErrorMessage(e.getMessage(),
                                                                                        SC_INTERNAL_SERVER_ERROR)),
                                                        APPLICATION_JSON, SC_INTERNAL_SERVER_ERROR));
                }
        }
}
