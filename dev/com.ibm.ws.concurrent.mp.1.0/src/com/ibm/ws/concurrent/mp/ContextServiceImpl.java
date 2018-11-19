/*******************************************************************************
 * Copyright (c) 2018 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.concurrent.mp;

import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.enterprise.concurrent.ContextService;

import org.eclipse.microprofile.concurrent.ThreadContext;
import org.eclipse.microprofile.concurrent.spi.ConcurrencyProvider;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.websphere.ras.annotation.Trivial;
import com.ibm.ws.concurrent.service.AbstractContextService;
import com.ibm.ws.threading.PolicyExecutor;
import com.ibm.wsspi.application.lifecycle.ApplicationRecycleComponent;
import com.ibm.wsspi.resource.ResourceFactory;
import com.ibm.wsspi.threadcontext.ThreadContextDescriptor;
import com.ibm.wsspi.threadcontext.WSContextService;

/**
 * Subclass of ContextServiceImpl to be used with Java 8 and above.
 * This class provides implementation of the MicroProfile Concurrency methods.
 * These methods can be collapsed into ContextServiceImpl once there is
 * no longer a need for OpenLiberty to support Java 7.
 */
@Component(name = "com.ibm.ws.context.service",
           configurationPolicy = ConfigurationPolicy.REQUIRE,
           service = { ResourceFactory.class, ContextService.class, ThreadContext.class, WSContextService.class, ApplicationRecycleComponent.class },
           property = { "creates.objectClass=javax.enterprise.concurrent.ContextService",
                        "creates.objectClass=org.eclipse.microprofile.concurrent.ThreadContext" })
public class ContextServiceImpl extends AbstractContextService implements ThreadContext {
    private static final TraceComponent tc = Tr.register(ContextServiceImpl.class);

    /**
     * Lazily initialized reference to a cached managed executor instance, which is
     * backed by the Liberty global thread pool without concurrency constraints,
     * propagates the type of context configured for this thread context service, and
     * clears all other types of context.
     */
    private final AtomicReference<ManagedExecutorImpl> managedExecutorRef = new AtomicReference<ManagedExecutorImpl>();

    @Activate
    @Override
    @Trivial
    protected void activate(ComponentContext context) {
        super.activate(context);
    }

    @Override
    public Executor currentContextExecutor() {
        @SuppressWarnings("unchecked")
        ThreadContextDescriptor contextDescriptor = captureThreadContext(Collections.emptyMap());
        return new ContextualExecutor(contextDescriptor);
    }

    @Deactivate
    @Override
    @Trivial
    protected void deactivate(ComponentContext context) {
        super.deactivate(context);
    }

    /**
     * Obtain a ManagedExecutor backed by the Liberty global thread pool, without constraints,
     * and propagating the same types as this ThreadContext service, clearing those which are
     * configured to be cleared.
     * If possible, a cached instance is returned. If it doesn't exist yet, then an instance
     * is lazily created by this method.
     *
     * @return ManagedExecutor instance.
     */
    private ManagedExecutorImpl getManagedExecutor() {
        ManagedExecutorImpl executor = managedExecutorRef.get();

        if (executor == null) {
            String name = new StringBuilder("ManagedExecutor_-1_-1_").append(ManagedExecutorBuilderImpl.instanceCount.incrementAndGet()).toString();

            ConcurrencyProviderImpl concurrencyProvider = (ConcurrencyProviderImpl) ConcurrencyProvider.instance();
            PolicyExecutor policyExecutor = concurrencyProvider.policyExecutorProvider.create(name);
            policyExecutor.maxConcurrency(-1).maxQueueSize(-1);
            // TODO these policy executor instances, as well as those created via ManagedExecutorBuilder are never shut down
            // and removed from PolicyExecutorProvider's list. This is a memory leak and needs to be fixed.

            executor = new ManagedExecutorImpl(name, policyExecutor, this, concurrencyProvider.transactionContextProvider.transactionContextProviderRef);

            if (!managedExecutorRef.compareAndSet(null, executor)) {
                // Another thread updated the reference first. Discard the instance we created and use the other.
                policyExecutor.shutdown();
                executor = managedExecutorRef.get();
            }
        }

        return executor;
    }

    @Modified
    @Override
    @Trivial
    protected void modified(ComponentContext context) {
        super.modified(context);
    }

    @Override
    @Reference(name = "baseInstance",
               service = ContextService.class,
               cardinality = ReferenceCardinality.OPTIONAL,
               policy = ReferencePolicy.DYNAMIC,
               policyOption = ReferencePolicyOption.GREEDY,
               target = "(id=unbound)")
    @Trivial
    protected void setBaseInstance(ServiceReference<ContextService> ref) {
        super.setBaseInstance(ref);
    }

    @Override
    @Reference(name = "threadContextManager",
               service = WSContextService.class,
               cardinality = ReferenceCardinality.MANDATORY,
               policy = ReferencePolicy.STATIC,
               target = "(component.name=com.ibm.ws.context.manager)")
    @Trivial
    protected void setThreadContextManager(WSContextService svc) {
        super.setThreadContextManager(svc);
    }

    @Override
    @Trivial
    protected void unsetBaseInstance(ServiceReference<ContextService> ref) {
        super.unsetBaseInstance(ref);
    }

    @Override
    @Trivial
    protected void unsetThreadContextManager(WSContextService svc) {
        super.unsetThreadContextManager(svc);
    }

    @Override
    public <T> CompletableFuture<T> withContextCapture(CompletableFuture<T> stage) {
        CompletableFuture<T> newCompletableFuture;

        ManagedExecutorImpl executor = getManagedExecutor();
        if (ManagedCompletableFuture.JAVA8)
            newCompletableFuture = new ManagedCompletableFuture<T>(new CompletableFuture<T>(), executor, null);
        else
            newCompletableFuture = new ManagedCompletableFuture<T>(executor, null);

        stage.whenComplete((result, failure) -> {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled())
                Tr.debug(this, tc, "whenComplete", result, failure);
            if (failure == null)
                newCompletableFuture.complete(result);
            else
                newCompletableFuture.completeExceptionally(failure);
        });

        return newCompletableFuture;
    }

    @Override
    public <T> CompletionStage<T> withContextCapture(CompletionStage<T> stage) {
        ManagedCompletionStage<T> newStage;

        ManagedExecutorImpl executor = getManagedExecutor();
        if (ManagedCompletableFuture.JAVA8)
            newStage = new ManagedCompletionStage<T>(new CompletableFuture<T>(), executor, null);
        else
            newStage = new ManagedCompletionStage<T>(executor);

        stage.whenComplete((result, failure) -> {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled())
                Tr.debug(this, tc, "whenComplete", result, failure);
            if (failure == null)
                newStage.super_complete(result);
            else
                newStage.super_completeExceptionally(failure);
        });

        return newStage;
    }

    @Override
    public <T, U> BiConsumer<T, U> withCurrentContext(BiConsumer<T, U> consumer) {
        @SuppressWarnings("unchecked")
        ThreadContextDescriptor contextDescriptor = captureThreadContext(Collections.emptyMap());
        return new ContextualBiConsumer<T, U>(contextDescriptor, consumer);
    }

    @Override
    public <T, U, R> BiFunction<T, U, R> withCurrentContext(BiFunction<T, U, R> function) {
        @SuppressWarnings("unchecked")
        ThreadContextDescriptor contextDescriptor = captureThreadContext(Collections.emptyMap());
        return new ContextualBiFunction<T, U, R>(contextDescriptor, function);
    }

    @Override
    public <R> Callable<R> withCurrentContext(Callable<R> callable) {
        @SuppressWarnings("unchecked")
        ThreadContextDescriptor contextDescriptor = captureThreadContext(Collections.emptyMap());
        return new ContextualCallable<R>(contextDescriptor, callable);
    }

    @Override
    public <T> Consumer<T> withCurrentContext(Consumer<T> consumer) {
        @SuppressWarnings("unchecked")
        ThreadContextDescriptor contextDescriptor = captureThreadContext(Collections.emptyMap());
        return new ContextualConsumer<T>(contextDescriptor, consumer);
    }

    @Override
    public <T, R> Function<T, R> withCurrentContext(Function<T, R> function) {
        @SuppressWarnings("unchecked")
        ThreadContextDescriptor contextDescriptor = captureThreadContext(Collections.emptyMap());
        return new ContextualFunction<T, R>(contextDescriptor, function);
    }

    @Override
    public Runnable withCurrentContext(Runnable runnable) {
        @SuppressWarnings("unchecked")
        ThreadContextDescriptor contextDescriptor = captureThreadContext(Collections.emptyMap());
        return new ContextualRunnable(contextDescriptor, runnable);
    }

    @Override
    public <R> Supplier<R> withCurrentContext(Supplier<R> supplier) {
        @SuppressWarnings("unchecked")
        ThreadContextDescriptor contextDescriptor = captureThreadContext(Collections.emptyMap());
        return new ContextualSupplier<R>(contextDescriptor, supplier);
    }
}
