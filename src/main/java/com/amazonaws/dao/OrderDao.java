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

package com.amazonaws.dao;

import com.amazonaws.exception.CouldNotCreateOrderException;
import com.amazonaws.exception.OrderDoesNotExistException;
import com.amazonaws.exception.TableDoesNotExistException;
import com.amazonaws.exception.UnableToDeleteException;
import com.amazonaws.exception.UnableToUpdateException;
import com.amazonaws.model.Order;
import com.amazonaws.model.OrderPage;
import com.amazonaws.model.request.CreateOrderRequest;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class OrderDao {

    // Constants used across operations
    private static final String UPDATE_EXPRESSION = "SET customerId = :cid, preTaxAmount = :pre, postTaxAmount = :post ADD version :o";
    private static final String ORDER_ID = "orderId";
    private static final String PRE_TAX_AMOUNT_WAS_NULL = "preTaxAmount was null";
    private static final String POST_TAX_AMOUNT_WAS_NULL = "postTaxAmount was null";
    private static final String VERSION_WAS_NULL = "version was null";

    // Fields injected by Dagger via OrderModule
    private final String tableName; // DynamoDB table name (injected from env var)
    private final DynamoDbClient dynamoDb; // Low-level DynamoDB client
    private final int pageSize; // Used for paginated queries

    /**
     * Constructor used by Dagger to provide an OrderDao.
     * The values come from OrderModule's @Provides method.
     */
    public OrderDao(final DynamoDbClient dynamoDb, final String tableName, final int pageSize) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        this.pageSize = pageSize;
    }

    /**
     * Fetches a single order by ID, or throws if it doesn't exist.
     */
    public Order getOrder(final String orderId) {
        try {
            return Optional.ofNullable(
                    dynamoDb.getItem(GetItemRequest.builder()
                            .tableName(tableName)
                            .key(Collections.singletonMap(ORDER_ID, AttributeValue.builder().s(orderId).build()))
                            .build()))
                    .map(GetItemResponse::item)
                    .map(this::convert) // Converts raw DynamoDB item map into Order POJO
                    .orElseThrow(() -> new OrderDoesNotExistException("Order " + orderId + " does not exist"));
        } catch (ResourceNotFoundException e) {
            throw new TableDoesNotExistException("Order table " + tableName + " does not exist");
        }
    }

    /**
     * Returns a page of orders, optionally starting after a given ID.
     */
    public OrderPage getOrders(final String exclusiveStartOrderId) {
        final ScanResponse result;

        try {
            ScanRequest.Builder scanBuilder = ScanRequest.builder()
                    .tableName(tableName)
                    .limit(pageSize);
            if (!isNullOrEmpty(exclusiveStartOrderId)) {
                scanBuilder.exclusiveStartKey(Collections.singletonMap(ORDER_ID,
                        AttributeValue.builder().s(exclusiveStartOrderId).build()));
            }
            result = dynamoDb.scan(scanBuilder.build());
        } catch (ResourceNotFoundException e) {
            throw new TableDoesNotExistException("Order table " + tableName + " does not exist");
        }

        // Convert all raw DynamoDB items into POJOs
        final List<Order> orders = result.items().stream()
                .map(this::convert)
                .collect(Collectors.toList());

        // Prepare a response object with optional pagination key
        OrderPage.OrderPageBuilder builder = OrderPage.builder().orders(orders);
        if (result.lastEvaluatedKey() != null && !result.lastEvaluatedKey().isEmpty()) {
            if (!result.lastEvaluatedKey().containsKey(ORDER_ID)
                    || isNullOrEmpty(result.lastEvaluatedKey().get(ORDER_ID).s())) {
                throw new IllegalStateException("Missing or invalid orderId in pagination key");
            } else {
                builder.lastEvaluatedKey(result.lastEvaluatedKey().get(ORDER_ID).s());
            }
        }

        return builder.build();
    }

    /**
     * Updates an order with new values, performing optimistic locking via
     * `version`.
     */
    public Order updateOrder(final Order order) {
        if (order == null)
            throw new IllegalArgumentException("Order to update was null");

        String orderId = order.getOrderId();
        if (isNullOrEmpty(orderId))
            throw new IllegalArgumentException("orderId was null or empty");

        // Expression values for update statement
        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":cid",
                AttributeValue.builder().s(validateCustomerId(order.getCustomerId())).build());
        try {
            expressionAttributeValues.put(":pre",
                    AttributeValue.builder().n(order.getPreTaxAmount().toString()).build());
            expressionAttributeValues.put(":post",
                    AttributeValue.builder().n(order.getPostTaxAmount().toString()).build());
            expressionAttributeValues.put(":v", AttributeValue.builder().n(order.getVersion().toString()).build());
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("One of the required numeric fields was null");
        }
        expressionAttributeValues.put(":o", AttributeValue.builder().n("1").build()); // Increment version by 1

        final UpdateItemResponse result;
        try {
            result = dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Collections.singletonMap(ORDER_ID, AttributeValue.builder().s(order.getOrderId()).build()))
                    .returnValues(ReturnValue.ALL_NEW)
                    .updateExpression(UPDATE_EXPRESSION)
                    .conditionExpression("attribute_exists(orderId) AND version = :v")
                    .expressionAttributeValues(expressionAttributeValues)
                    .build());
        } catch (ConditionalCheckFailedException e) {
            throw new UnableToUpdateException("Order missing or version mismatch");
        } catch (ResourceNotFoundException e) {
            throw new TableDoesNotExistException("Order table was deleted");
        }
        return convert(result.attributes());
    }

    /**
     * Deletes an order by ID. Throws if it doesn’t exist.
     */
    public Order deleteOrder(final String orderId) {
        try {
            return Optional.ofNullable(
                    dynamoDb.deleteItem(DeleteItemRequest.builder()
                            .tableName(tableName)
                            .key(Collections.singletonMap(ORDER_ID, AttributeValue.builder().s(orderId).build()))
                            .conditionExpression("attribute_exists(orderId)")
                            .returnValues(ReturnValue.ALL_OLD)
                            .build()))
                    .map(DeleteItemResponse::attributes)
                    .map(this::convert)
                    .orElseThrow(() -> new IllegalStateException("Deleted item was unexpectedly null"));
        } catch (ConditionalCheckFailedException e) {
            throw new UnableToDeleteException("Competing update or order missing");
        } catch (ResourceNotFoundException e) {
            throw new TableDoesNotExistException("Order table was deleted");
        }
    }

    /**
     * Creates a new order, retrying up to 10 times to ensure unique UUID.
     */
    public Order createOrder(final CreateOrderRequest request) {
        if (request == null)
            throw new IllegalArgumentException("CreateOrderRequest was null");

        int tries = 0;
        while (tries < 10) {
            try {
                Map<String, AttributeValue> item = createOrderItem(request);
                dynamoDb.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(item)
                        .conditionExpression("attribute_not_exists(orderId)")
                        .build());

                // Build Order object to return
                return Order.builder()
                        .orderId(item.get(ORDER_ID).s())
                        .customerId(item.get("customerId").s())
                        .preTaxAmount(new BigDecimal(item.get("preTaxAmount").n()))
                        .postTaxAmount(new BigDecimal(item.get("postTaxAmount").n()))
                        .version(Long.valueOf(item.get("version").n()))
                        .build();
            } catch (ConditionalCheckFailedException e) {
                tries++; // Retry on ID collision
            } catch (ResourceNotFoundException e) {
                throw new TableDoesNotExistException("Order table was deleted");
            }
        }
        throw new CouldNotCreateOrderException("Too many ID collisions");
    }

    // Converts a raw DynamoDB item into an Order object
    private Order convert(final Map<String, AttributeValue> item) {
        if (item == null || item.isEmpty())
            return null;

        return Order.builder()
                .orderId(item.get(ORDER_ID).s())
                .customerId(item.get("customerId").s())
                .preTaxAmount(new BigDecimal(item.get("preTaxAmount").n()))
                .postTaxAmount(new BigDecimal(item.get("postTaxAmount").n()))
                .version(Long.valueOf(item.get("version").n()))
                .build();
    }

    // Creates the item map used to write a new order to DynamoDB
    private Map<String, AttributeValue> createOrderItem(final CreateOrderRequest order) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ORDER_ID, AttributeValue.builder().s(UUID.randomUUID().toString()).build());
        item.put("version", AttributeValue.builder().n("1").build());
        item.put("customerId", AttributeValue.builder().s(validateCustomerId(order.getCustomerId())).build());
        item.put("preTaxAmount", AttributeValue.builder().n(order.getPreTaxAmount().toString()).build());
        item.put("postTaxAmount", AttributeValue.builder().n(order.getPostTaxAmount().toString()).build());
        return item;
    }

    // Simple string validator
    private String validateCustomerId(final String customerId) {
        if (isNullOrEmpty(customerId)) {
            throw new IllegalArgumentException("customerId was null or empty");
        }
        return customerId;
    }

    // Null or empty string check
    private static boolean isNullOrEmpty(final String string) {
        return string == null || string.isEmpty();
    }
}
