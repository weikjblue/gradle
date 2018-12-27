/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.instantiation;

import org.gradle.api.reflect.ObjectInstantiationException;
import org.gradle.internal.logging.text.TreeFormatter;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.reflect.JavaReflectionUtil;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;

/**
 * An {@link Instantiator} that applies dependency injection, delegating to a {@link ConstructorSelector} to decide which constructor to use to create instances.
 */
class DependencyInjectingInstantiator implements Instantiator {
    private static final DefaultServiceRegistry NO_SERVICES = new DefaultServiceRegistry();
    private final ServiceRegistry services;
    private final ConstructorSelector constructorSelector;

    public DependencyInjectingInstantiator(ConstructorSelector constructorSelector, ServiceRegistry services) {
        this.services = services;
        this.constructorSelector = constructorSelector;
    }

    public <T> T newInstance(Class<? extends T> type, Object... parameters) {
        try {
            ClassGenerator.GeneratedConstructor<? extends T> constructor = constructorSelector.forParams(type, parameters);
            Object[] resolvedParameters = convertParameters(type, constructor, services, parameters);
            try {
                return constructor.newInstance(services, this, resolvedParameters);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        } catch (Throwable t) {
            throw new ObjectInstantiationException(type, t);
        }
    }

    public <T> InstanceFactory<T> factoryFor(final Class<T> type) {
        final ClassGenerator.GeneratedConstructor<? extends T> constructor = constructorSelector.forType(type);
        return new InstanceFactory<T>() {
            @Override
            public boolean requiresService(Class<?> serviceType) {
                return constructor.requiresService(serviceType);
            }

            @Override
            public T newInstance(ServiceRegistry services, Object... parameters) {
                try {
                    Object[] resolvedParameters = convertParameters(type, constructor, services, parameters);
                    try {
                        return constructor.newInstance(services, DependencyInjectingInstantiator.this, resolvedParameters);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                } catch (Throwable t) {
                    throw new ObjectInstantiationException(type, t);
                }
            }

            @Override
            public T newInstance(Object... params) {
                return newInstance(NO_SERVICES, params);
            }
        };
    }

    private Object[] convertParameters(Class<?> type, ClassGenerator.GeneratedConstructor<?> constructor, ServiceRegistry services, Object[] parameters) {
        constructorSelector.vetoParameters(constructor, parameters);
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length < parameters.length) {
            TreeFormatter formatter = new TreeFormatter();
            formatter.node("Too many parameters provided for constructor for ");
            formatter.appendType(type);
            formatter.append(String.format(". Expected %s, received %s.", parameterTypes.length, parameters.length));
            throw new IllegalArgumentException(formatter.toString());
        }
        if (parameterTypes.length == parameters.length) {
            // No services to be mixed in
            return verifyParameters(constructor, parameters);
        } else {
            return addServicesToParameters(type, constructor, services, parameters);
        }
    }

    private Object[] verifyParameters(ClassGenerator.GeneratedConstructor<?> constructor, Object[] parameters) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> targetType = parameterTypes[i];
            Object currentParameter = parameters[i];
            if (targetType.isPrimitive()) {
                if (currentParameter == null) {
                    nullPrimitiveType(i, targetType);
                }
                targetType = JavaReflectionUtil.getWrapperTypeForPrimitiveType(targetType);
            } else if (currentParameter == null) {
                // Null is ok if the ConstructorSelector says it's ok
                continue;
            }
            if (!targetType.isInstance(currentParameter)) {
                TreeFormatter formatter = new TreeFormatter();
                formatter.node("Unable to determine constructor argument #" + (i + 1) + ": value ");
                formatter.appendValue(currentParameter);
                formatter.append(" not assignable to ");
                formatter.appendType(parameterTypes[i]);
                throw new IllegalArgumentException(formatter.toString());
            }
        }
        return parameters;
    }

    private void nullPrimitiveType(int index, Class<?> paramType) {
        TreeFormatter formatter = new TreeFormatter();
        formatter.node("Unable to determine constructor argument #" + (index + 1) + ": null value is not assignable to ");
        formatter.appendType(paramType);
        throw new IllegalArgumentException(formatter.toString());
    }

    private Object[] addServicesToParameters(Class<?> type, ClassGenerator.GeneratedConstructor<?> constructor, ServiceRegistry services, Object[] parameters) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Type[] genericTypes = constructor.getGenericParameterTypes();
        Object[] resolvedParameters = new Object[parameterTypes.length];
        int pos = 0;
        for (int i = 0; i < resolvedParameters.length; i++) {
            Class<?> targetType = parameterTypes[i];
            Type serviceType = genericTypes[i];
            if (pos < parameters.length) {
                Object parameter = parameters[pos];
                if (targetType.isPrimitive()) {
                    if (parameter == null) {
                        nullPrimitiveType(i, targetType);
                    }
                    targetType = JavaReflectionUtil.getWrapperTypeForPrimitiveType(targetType);
                }
                if (parameter == null || targetType.isInstance(parameter)) {
                    resolvedParameters[i] = parameter;
                    pos++;
                    continue;
                }
            }
            Object service = services.find(serviceType);
            if (service != null) {
                resolvedParameters[i] = service;
                continue;
            }
            TreeFormatter formatter = new TreeFormatter();
            formatter.node("Unable to determine constructor argument #" + (i + 1) + ": ");
            if (pos < parameters.length) {
                formatter.append("value ");
                formatter.appendValue(parameters[pos]);
                formatter.append(" is not assignable to ");
                formatter.appendType(parameterTypes[i]);
            } else {
                formatter.append("missing parameter of ");
                formatter.appendType(parameterTypes[i]);
            }
            formatter.append(", or no service of type ");
            formatter.append(serviceType.toString());
            throw new IllegalArgumentException(formatter.toString());
        }

        if (pos != parameters.length) {
            TreeFormatter formatter = new TreeFormatter();
            formatter.node("Unexpected parameter provided for constructor for ");
            formatter.appendType(type);
            throw new IllegalArgumentException(formatter.toString());
        }

        return resolvedParameters;
    }
}
