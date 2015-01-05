/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.model.internal.inspect;

import com.google.common.collect.ImmutableList;
import net.jcip.annotations.ThreadSafe;
import org.gradle.api.specs.Spec;
import org.gradle.internal.Cast;
import org.gradle.model.InvalidModelRuleDeclarationException;
import org.gradle.model.Path;
import org.gradle.model.internal.core.ModelPath;
import org.gradle.model.internal.core.ModelReference;
import org.gradle.model.internal.core.rule.describe.MethodModelRuleDescriptor;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.gradle.util.CollectionUtils.findFirst;

@ThreadSafe
public class DefaultMethodRuleDefinition<T, R> implements MethodRuleDefinition<R> {

    private final WeaklyTypeReferencingMethod<T, R> method;

    private DefaultMethodRuleDefinition(Method method, ModelType<T> instanceType, ModelType<R> returnType) {
        this.method = new WeaklyTypeReferencingMethod<T, R>(instanceType, returnType, method);
    }

    public static <T, R> MethodRuleDefinition<R> create(Class<T> source, Method method) {
        ModelType<R> returnType = ModelType.returnType(method);
        return new DefaultMethodRuleDefinition<T, R>(method, ModelType.of(source), returnType);
    }

    public String getMethodName() {
        return method.getName();
    }

    public ModelType<R> getReturnType() {
        return method.getReturnType();
    }

    public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotationType.isAssignableFrom(annotation.getClass())) {
                return Cast.uncheckedCast(annotation);
            }
        }
        return null;
    }

    public ModelRuleDescriptor getDescriptor() {
        return new MethodModelRuleDescriptor<T, R>(method);
    }

    public ModelRuleInvoker<R> getRuleInvoker() {
        return new DefaultModelRuleInvoker<T, R>(method);
    }

    public List<ModelReference<?>> getReferences() {
        ImmutableList.Builder<ModelReference<?>> inputBindingBuilder = ImmutableList.builder();
        for (int i = 0; i < method.getGenericParameterTypes().length; i++) {
            Annotation[] paramAnnotations = method.getParameterAnnotations()[i];
            inputBindingBuilder.add(reference(paramAnnotations, i));
        }
        return inputBindingBuilder.build();
    }

    private ModelReference<?> reference(Annotation[] annotations, int i) {
        Path pathAnnotation = (Path) findFirst(annotations, new Spec<Annotation>() {
            public boolean isSatisfiedBy(Annotation element) {
                return element.annotationType().equals(Path.class);
            }
        });
        String path = pathAnnotation == null ? null : pathAnnotation.value();
        ModelType<?> cast = ModelType.of(method.getGenericParameterTypes()[i]);
        return ModelReference.of(path == null ? null : validPath(path), cast, String.format("parameter %s", i + 1));
    }

    private ModelPath validPath(String path) {
        try {
            return ModelPath.validatedPath(path);
        } catch (ModelPath.InvalidPathException e) {
            throw new InvalidModelRuleDeclarationException(getDescriptor(), e);
        } catch (ModelPath.InvalidNameException e) {
            throw new InvalidModelRuleDeclarationException(getDescriptor(), e);
        }
    }

}
