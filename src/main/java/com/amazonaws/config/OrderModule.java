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
package com.amazonaws.config;

import com.amazonaws.dao.OrderDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import javax.inject.Named;
import javax.inject.Singleton;
import java.net.URI;
import java.util.Optional;

@Module // Marks this class as a Dagger module that provides dependencies
public class OrderModule {

    // ✅ Provide the DynamoDB table name via environment variable
    // This will be injected using @Named("tableName") wherever needed
    @Singleton
    @Provides
    @Named("tableName")
    String tableName() {
        // Use "orders_table" as fallback if env var isn't defined (good for local dev)
        return Optional.ofNullable(System.getenv("TABLE_NAME")).orElse("orders_table");
    }

    // ✅ Provide the configured DynamoDbClient
    // This sets up the client with HTTP settings, region, endpoint override, and
    // static credentials
    @Singleton
    @Provides
    DynamoDbClient dynamoDb() {
        // Fetch credentials and endpoint override from environment
        String accessKeyId = Optional.ofNullable(System.getenv("AWS_ACCESS_KEY_ID"))
                .orElseThrow(() -> new IllegalStateException("AWS_ACCESS_KEY_ID env var not set"));
        String secretAccessKey = Optional.ofNullable(System.getenv("AWS_SECRET_ACCESS_KEY"))
                .orElseThrow(() -> new IllegalStateException("AWS_SECRET_ACCESS_KEY env var not set"));
        String endpoint = Optional.ofNullable(System.getenv("ENDPOINT_OVERRIDE"))
                .orElseThrow(() -> new IllegalStateException("ENDPOINT_OVERRIDE env var not set"));

        // Build and return the DynamoDB client
        return DynamoDbClient.builder()
                .httpClient(ApacheHttpClient.builder().build()) // Use Apache HTTP client
                .endpointOverride(URI.create(endpoint)) // Local or test endpoint
                .region(Region.US_EAST_1) // Region must be set even if unused in local mode
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();
    }

    // ✅ Provide a Jackson ObjectMapper for JSON serialization/deserialization
    @Singleton
    @Provides
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    // ✅ Provide an OrderDao, which depends on the DynamoDB client and table name
    // Dagger will automatically resolve these dependencies from the other providers
    @Singleton
    @Provides
    public OrderDao orderDao(DynamoDbClient dynamoDb, @Named("tableName") String tableName) {
        // '10' is a page size or limit parameter for queries (your design decision)
        return new OrderDao(dynamoDb, tableName, 10);
    }
}
