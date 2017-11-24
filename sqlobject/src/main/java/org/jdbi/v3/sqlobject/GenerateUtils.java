/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jdbi.v3.sqlobject;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

public class GenerateUtils {
    public static RuntimeException throw0(Throwable t) {
        if (t instanceof Error) { throw (Error) t; }
        if (t instanceof RuntimeException) { throw (RuntimeException) t; }
        throw new RuntimeException(t);
    }

    public static Method findMethod(Class<?> sqlObjectType, String name, Class<?>[] paramTypes) {
        final Queue<Class<?>> types = new LinkedList<>();
        types.add(sqlObjectType);
        while (!types.isEmpty()) {
            final Class<?> type = types.poll();
            try {
                return type.getDeclaredMethod(name, paramTypes);
            } catch (ReflectiveOperationException e) {
                // ignore
            }
            final Class<?> spr = type.getSuperclass();
            if (spr != null) {
                types.add(type.getSuperclass());
            }
            types.addAll(Arrays.asList(type.getInterfaces()));
        }
        throw new UnableToCreateSqlObjectException(String.format("No method '%s'(%s) on '%s' or supertypes", name, Arrays.asList(paramTypes), sqlObjectType));
    }
}
