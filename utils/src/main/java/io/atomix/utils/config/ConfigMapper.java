/*
 * Copyright 2018-present Open Networking Foundation
 *
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
package io.atomix.utils.config;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigMemorySize;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import io.atomix.utils.Named;
import io.atomix.utils.memory.MemorySize;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility for applying Typesafe configurations to Atomix configuration objects.
 */
public class ConfigMapper {
  private final ClassLoader classLoader;

  public ConfigMapper(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  /**
   * Loads the given resources using the configuration mapper.
   *
   * @param type      the type to load
   * @param resources the resources to load
   * @param <T>       the resulting type
   * @return the loaded configuration
   */
  public <T> T loadResources(Class<T> type, String... resources) {
    Config config = null;
    for (String resource : resources) {
      if (config == null) {
        config = ConfigFactory.load(classLoader, resource);
      } else {
        config = config.withFallback(ConfigFactory.load(classLoader, resource));
      }
    }
    return map(config, type);
  }

  /**
   * Applies the given configuration to the given type.
   *
   * @param config the configuration to apply
   * @param clazz  the class to which to apply the configuration
   */
  protected <T> T map(Config config, Class<T> clazz) {
    return map(config, null, null, clazz);
  }

  protected <T> T newInstance(Config config, Class<T> clazz) {
    try {
      return clazz.newInstance();
    } catch (InstantiationException | IllegalAccessException e) {
      throw new ConfigurationException(clazz.getName() + " needs a public no-args constructor to be used as a bean", e);
    }
  }

  /**
   * Applies the given configuration to the given type.
   *
   * @param config     the configuration to apply
   * @param clazz      the class to which to apply the configuration
   */
  @SuppressWarnings("unchecked")
  protected <T> T map(Config config, String path, String name, Class<T> clazz) {
    T instance = newInstance(config, clazz);

    // Map config property names to bean properties.
    Map<String, String> propertyNames = new HashMap<>();
    for (Map.Entry<String, ConfigValue> configProp : config.root().entrySet()) {
      String originalName = configProp.getKey();
      String camelName = toCamelCase(originalName);
      // if a setting is in there both as some hyphen name and the camel name,
      // the camel one wins
      if (propertyNames.containsKey(camelName) && !originalName.equals(camelName)) {
        // if we aren't a camel name to start with, we lose.
        // if we are or we are the first matching key, we win.
      } else {
        propertyNames.put(camelName, originalName);
      }
    }

    // First use setters and then fall back to fields.
    mapSetters(instance, clazz, path, name, propertyNames, config);
    mapFields(instance, clazz, path, name, propertyNames, config);

    // If any properties present in the configuration were not found on config beans, throw an exception.
    if (path != null && !propertyNames.isEmpty()) {
      checkRemainingProperties(propertyNames.keySet(), toPath(path, name), clazz);
    }
    return instance;
  }

  protected void checkRemainingProperties(Set<String> propertyNames, String path, Class<?> clazz) {
    Set<String> cleanNames = propertyNames
        .stream()
        .map(propertyName -> toPath(path, propertyName))
        .collect(Collectors.toSet());
    if (!cleanNames.isEmpty()) {
      throw new ConfigurationException("Unknown properties present in configuration: " + Joiner.on(", ").join(cleanNames));
    }
  }

  private <T> void mapSetters(T instance, Class<T> clazz, String path, String name, Map<String, String> propertyNames, Config config) {
    try {
      for (SetterDescriptor descriptor : getSetterDescriptors(instance.getClass())) {
        Method setter = descriptor.setter;
        Type parameterType = setter.getGenericParameterTypes()[0];
        Class<?> parameterClass = setter.getParameterTypes()[0];

        String configPropName = propertyNames.remove(descriptor.name);
        if (configPropName == null) {
          if ((Named.class.isAssignableFrom(clazz) || NamedConfig.class.isAssignableFrom(clazz))
              && descriptor.setter.getParameterTypes()[0] == String.class && name != null && descriptor.name.equals("name")) {
            setter.invoke(instance, name);
          }
          continue;
        }

        Object value = getValue(instance.getClass(), parameterType, parameterClass, config, toPath(path, name), configPropName);
        if (value != null) {
          setter.invoke(instance, value);
        }
      }
    } catch (IllegalAccessException e) {
      throw new ConfigurationException(instance.getClass().getName() + " getters and setters are not accessible, they must be for use as a bean", e);
    } catch (InvocationTargetException e) {
      throw new ConfigurationException("Calling bean method on " + instance.getClass().getName() + " caused an exception", e);
    }
  }

  private <T> void mapFields(T instance, Class<T> clazz, String path, String name, Map<String, String> propertyNames, Config config) {
    try {
      for (FieldDescriptor descriptor : getFieldDescriptors(instance.getClass())) {
        Field field = descriptor.field;
        field.setAccessible(true);

        Type genericType = field.getGenericType();
        Class<?> fieldClass = field.getType();

        String configPropName = propertyNames.remove(descriptor.name);
        if (configPropName == null) {
          if (Named.class.isAssignableFrom(clazz) && field.getType() == String.class && name != null && descriptor.name.equals("name")) {
            field.set(instance, name);
          }
          continue;
        }

        Object value = getValue(instance.getClass(), genericType, fieldClass, config, toPath(path, name), configPropName);
        if (value != null) {
          field.set(instance, value);
        }
      }
    } catch (IllegalAccessException e) {
      throw new ConfigurationException(instance.getClass().getName() + " fields are not accessible, they must be for use as a bean", e);
    }
  }

  protected Object getValue(Class<?> beanClass, Type parameterType, Class<?> parameterClass, Config config, String configPath, String configPropName) {
    if (parameterClass == Boolean.class || parameterClass == boolean.class) {
      return config.getBoolean(configPropName);
    } else if (parameterClass == Integer.class || parameterClass == int.class) {
      return config.getInt(configPropName);
    } else if (parameterClass == Double.class || parameterClass == double.class) {
      return config.getDouble(configPropName);
    } else if (parameterClass == Long.class || parameterClass == long.class) {
      return config.getLong(configPropName);
    } else if (parameterClass == String.class) {
      return config.getString(configPropName);
    } else if (parameterClass == Duration.class) {
      return config.getDuration(configPropName);
    } else if (parameterClass == MemorySize.class) {
      ConfigMemorySize size = config.getMemorySize(configPropName);
      return new MemorySize(size.toBytes());
    } else if (parameterClass == Object.class) {
      return config.getAnyRef(configPropName);
    } else if (parameterClass == List.class) {
      return getListValue(beanClass, parameterType, parameterClass, config, configPath, configPropName);
    } else if (parameterClass == Set.class) {
      return getSetValue(beanClass, parameterType, parameterClass, config, configPath, configPropName);
    } else if (parameterClass == Map.class) {
      return getMapValue(beanClass, parameterType, parameterClass, config, configPath, configPropName);
    } else if (parameterClass == Config.class) {
      return config.getConfig(configPropName);
    } else if (parameterClass == ConfigObject.class) {
      return config.getObject(configPropName);
    } else if (parameterClass == ConfigValue.class) {
      return config.getValue(configPropName);
    } else if (parameterClass == ConfigList.class) {
      return config.getList(configPropName);
    } else if (parameterClass == Class.class) {
      String className = config.getString(configPropName);
      try {
        return classLoader.loadClass(className);
      } catch (ClassNotFoundException e) {
        throw new ConfigurationException("Failed to load class: " + className);
      }
    } else if (parameterClass.isEnum()) {
      @SuppressWarnings("unchecked")
      Enum enumValue = config.getEnum((Class<Enum>) parameterClass, configPropName);
      return enumValue;
    } else {
      return map(config.getConfig(configPropName), configPath, configPropName, parameterClass);
    }
  }

  protected Map getMapValue(Class<?> beanClass, Type parameterType, Class<?> parameterClass, Config config, String configPath, String configPropName) {
    Type[] typeArgs = ((ParameterizedType) parameterType).getActualTypeArguments();
    Type keyType = typeArgs[0];
    Type valueType = typeArgs[1];

    Map<Object, Object> map = new HashMap<>();
    Config childConfig = config.getConfig(configPropName);
    for (String key : config.getObject(configPropName).unwrapped().keySet()) {
      Object value = getValue(Map.class, valueType, (Class) valueType, childConfig, toPath(configPath, configPropName), key);
      map.put(getKeyValue(keyType, key), value);
    }
    return map;
  }

  protected Object getKeyValue(Type keyType, String key) {
    if (keyType == Boolean.class || keyType == boolean.class) {
      return Boolean.parseBoolean(key);
    } else if (keyType == Integer.class || keyType == int.class) {
      return Integer.parseInt(key);
    } else if (keyType == Double.class || keyType == double.class) {
      return Double.parseDouble(key);
    } else if (keyType == Long.class || keyType == long.class) {
      return Long.parseLong(key);
    } else if (keyType == String.class) {
      return key;
    } else {
      throw new ConfigurationException("Invalid map key type: " + keyType);
    }
  }

  protected Object getSetValue(Class<?> beanClass, Type parameterType, Class<?> parameterClass, Config config, String configPath, String configPropName) {
    return new HashSet((List) getListValue(beanClass, parameterType, parameterClass, config, configPath, configPropName));
  }

  protected Object getListValue(Class<?> beanClass, Type parameterType, Class<?> parameterClass, Config config, String configPath, String configPropName) {
    Type elementType = ((ParameterizedType) parameterType).getActualTypeArguments()[0];

    if (elementType == Boolean.class) {
      return config.getBooleanList(configPropName);
    } else if (elementType == Integer.class) {
      return config.getIntList(configPropName);
    } else if (elementType == Double.class) {
      return config.getDoubleList(configPropName);
    } else if (elementType == Long.class) {
      return config.getLongList(configPropName);
    } else if (elementType == String.class) {
      return config.getStringList(configPropName);
    } else if (elementType == Duration.class) {
      return config.getDurationList(configPropName);
    } else if (elementType == MemorySize.class) {
      List<ConfigMemorySize> sizes = config.getMemorySizeList(configPropName);
      return sizes.stream()
          .map(size -> new MemorySize(size.toBytes()))
          .collect(Collectors.toList());
    } else if (elementType == Object.class) {
      return config.getAnyRefList(configPropName);
    } else if (((Class<?>) elementType).isEnum()) {
      @SuppressWarnings("unchecked")
      List<Enum> enumValues = config.getEnumList((Class<Enum>) elementType, configPropName);
      return enumValues;
    } else {
      List<Object> beanList = new ArrayList<>();
      List<? extends Config> configList = config.getConfigList(configPropName);
      int i = 0;
      for (Config listMember : configList) {
        beanList.add(map(listMember, toPath(configPath, configPropName), String.valueOf(i), (Class<?>) elementType));
      }
      return beanList;
    }
  }

  protected String toPath(String path, String name) {
    return path != null ? String.format("%s.%s", path, name) : name;
  }

  protected static boolean isSimpleType(Class<?> parameterClass) {
    return parameterClass == Boolean.class || parameterClass == boolean.class
        || parameterClass == Integer.class || parameterClass == int.class
        || parameterClass == Double.class || parameterClass == double.class
        || parameterClass == Long.class || parameterClass == long.class
        || parameterClass == String.class
        || parameterClass == Duration.class
        || parameterClass == MemorySize.class
        || parameterClass == List.class
        || parameterClass == Map.class
        || parameterClass == Class.class;
  }

  protected static String toCamelCase(String originalName) {
    String[] words = originalName.split("-+");
    StringBuilder nameBuilder = new StringBuilder(originalName.length());
    for (String word : words) {
      if (nameBuilder.length() == 0) {
        nameBuilder.append(word);
      } else {
        nameBuilder.append(word.substring(0, 1).toUpperCase());
        nameBuilder.append(word.substring(1));
      }
    }
    return nameBuilder.toString();
  }

  protected static String toSetterName(String name) {
    return "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
  }

  protected static Collection<SetterDescriptor> getSetterDescriptors(Class<?> clazz) {
    Map<String, SetterDescriptor> descriptors = Maps.newHashMap();
    for (Method method : clazz.getMethods()) {
      String name = method.getName();
      if (method.getParameterTypes().length == 1
          && name.length() > 3
          && name.substring(0, 3).equals("set")
          && name.charAt(3) >= 'A'
          && name.charAt(3) <= 'Z') {

        // Strip the "set" prefix from the property name.
        name = method.getName().substring(3);
        name = name.length() > 1
            ? name.substring(0, 1).toLowerCase() + name.substring(1)
            : name.toLowerCase();

        // Strip the "Config" suffix from the property name.
        if (name.endsWith("Config")) {
          name = name.substring(0, name.length() - "Config".length());
        }

        // If a setter with this property name has already been registered, determine whether to override it.
        // We favor simpler types over more complex types (i.e. beans).
        SetterDescriptor descriptor = descriptors.get(name);
        if (descriptor != null) {
          Class<?> type = method.getParameterTypes()[0];
          if (isSimpleType(type)) {
            descriptors.put(name, new SetterDescriptor(name, method));
          }
        } else {
          descriptors.put(name, new SetterDescriptor(name, method));
        }
      }
    }
    return descriptors.values();
  }

  protected static Collection<FieldDescriptor> getFieldDescriptors(Class<?> type) {
    Class<?> clazz = type;
    Map<String, FieldDescriptor> descriptors = Maps.newHashMap();
    while (clazz != Object.class) {
      for (Field field : clazz.getDeclaredFields()) {
        // If the field is static or transient, ignore it.
        if (Modifier.isTransient(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) {
          continue;
        }

        // If the field has a setter, ignore it and use the setter.
        Method method = Stream.of(clazz.getMethods())
            .filter(m -> m.getName().equals(toSetterName(field.getName())))
            .findFirst()
            .orElse(null);
        if (method != null) {
          continue;
        }

        // Strip the "Config" suffix from the field.
        String name = field.getName();
        if (name.endsWith("Config")) {
          name = name.substring(0, name.length() - "Config".length());
        }
        descriptors.putIfAbsent(name, new FieldDescriptor(name, field));
      }
      clazz = clazz.getSuperclass();
    }
    return Lists.newArrayList(descriptors.values());
  }

  protected static class SetterDescriptor {
    private final String name;
    private final Method setter;

    SetterDescriptor(String name, Method setter) {
      this.name = name;
      this.setter = setter;
    }
  }

  protected static class FieldDescriptor {
    private final String name;
    private final Field field;

    FieldDescriptor(String name, Field field) {
      this.name = name;
      this.field = field;
    }
  }
}
