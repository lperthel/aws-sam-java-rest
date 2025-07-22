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

import com.amazonaws.handler.CreateOrderHandler;
import com.amazonaws.handler.DeleteOrderHandler;
import com.amazonaws.handler.GetOrderHandler;
import com.amazonaws.handler.GetOrdersHandler;
import com.amazonaws.handler.UpdateOrderHandler;
import dagger.Component;

import javax.inject.Singleton;

/**
 * OrderComponent is a Dagger Component interface that wires together all the
 * dependencies defined in OrderModule and injects them into your Lambda
 * handlers.
 *
 * Dagger will generate an implementation of this interface at compile time,
 * called DaggerOrderComponent, which you can instantiate and use to inject
 * dependencies.
 */
@Singleton // Ensures that all @Provides methods in OrderModule are treated as singletons
@Component(modules = { OrderModule.class }) // Declares that this component uses OrderModule to provide dependencies
public interface OrderComponent {

    // Each method below tells Dagger that it should inject dependencies into
    // the corresponding handler class.

    void inject(CreateOrderHandler requestHandler);

    void inject(DeleteOrderHandler requestHandler);

    void inject(GetOrderHandler requestHandler);

    void inject(GetOrdersHandler requestHandler);

    void inject(UpdateOrderHandler requestHandler);
}
