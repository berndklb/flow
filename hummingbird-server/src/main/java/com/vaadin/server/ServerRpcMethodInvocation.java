/*
 * Copyright 2000-2014 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.server;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.vaadin.shared.communication.MethodInvocation;
import com.vaadin.shared.communication.ServerRpc;
import com.vaadin.ui.JavaScript.JavaScriptCallbackRpc;

public class ServerRpcMethodInvocation extends MethodInvocation {

    private static final Map<String, Method> invocationMethodCache = new ConcurrentHashMap<String, Method>(
            128, 0.75f, 1);

    private final Method method;

    private final Class<? extends ServerRpc> interfaceClass;

    public ServerRpcMethodInvocation(int parameterCount) {
        super();
        interfaceClass = JavaScriptCallbackRpc.class;

        method = findInvocationMethod(interfaceClass, "call", parameterCount);
    }

    public Class<? extends ServerRpc> getInterfaceClass() {
        return interfaceClass;
    }

    public Method getMethod() {
        return method;
    }

    /**
     * Tries to find the method from the cache or alternatively by invoking
     * {@link #doFindInvocationMethod(Class, String, int)} and updating the
     * cache.
     *
     * @param targetType
     * @param methodName
     * @param parameterCount
     * @return
     */
    private Method findInvocationMethod(Class<?> targetType, String methodName,
            int parameterCount) {
        // TODO currently only using method name and number of parameters as the
        // signature
        String signature = targetType.getName() + "." + methodName + "("
                + parameterCount;
        Method invocationMethod = invocationMethodCache.get(signature);

        if (invocationMethod == null) {
            invocationMethod = doFindInvocationMethod(targetType, methodName,
                    parameterCount);

            if (invocationMethod != null) {
                invocationMethodCache.put(signature, invocationMethod);
            }
        }

        if (invocationMethod == null) {
            throw new IllegalStateException("Can't find method " + methodName
                    + " with " + parameterCount + " parameters in "
                    + targetType.getName());
        }

        return invocationMethod;
    }

    /**
     * Tries to find the method from the class by looping through available
     * methods.
     *
     * @param targetType
     * @param methodName
     * @param parameterCount
     * @return
     */
    private Method doFindInvocationMethod(Class<?> targetType,
            String methodName, int parameterCount) {
        Method[] methods = targetType.getMethods();
        for (Method method : methods) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (method.getName().equals(methodName)
                    && parameterTypes.length == parameterCount) {
                return method;
            }
        }
        return null;
    }

}