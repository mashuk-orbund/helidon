/*
 * Copyright (c) 2019, 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.integrations.graal.nativeimage.extension;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReaderFactory;
import javax.json.stream.JsonParsingException;

import io.helidon.common.HelidonFeatures;
import io.helidon.common.LogConfig;
import io.helidon.common.Reflected;
import io.helidon.config.mp.MpConfigProviderResolver;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.jdk.Resources;
import com.oracle.svm.core.jdk.proxy.DynamicProxyRegistry;
import com.oracle.svm.hosted.FeatureImpl;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Feature to add reflection configuration to the image for Helidon, CDI and Jersey.
 * Override the one in dependencies (native-image-extension from Helidon)
 */
@AutomaticFeature
public class HelidonReflectionFeature implements Feature {
    private static final boolean ENABLED = NativeConfig.option("reflection.enable-feature", true);
    private static final boolean TRACE_PARSING = NativeConfig.option("reflection.trace-parsing", false);
    private static final boolean TRACE = NativeConfig.option("reflection.trace", false);

    private static final String ENTITY_ANNOTATION_CLASS_NAME = "javax.persistence.Entity";

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return ENABLED;
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // need the application classloader
        Class<?> logConfigClass = access.findClassByName(LogConfig.class.getName());
        ClassLoader classLoader = logConfigClass.getClassLoader();

        // initialize logging (if on classpath)
        try {
            logConfigClass.getMethod("initClass")
                    .invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // make sure we print all the warnings for native image
        HelidonFeatures.nativeBuildTime(classLoader);
        // to add a startup hook:
        //RuntimeSupport.getRuntimeSupport().addStartupHook(() -> {});

        // load configuration
        HelidonReflectionConfiguration config = loadConfiguration(access);
        // create context (to know what was processed and what should be registered)
        BeforeAnalysisContext context = new BeforeAnalysisContext(access, config.excluded);

        // process each configured annotation
        config.annotations().forEach(it -> processAnnotated(context, it));
        // process each configured interface or class
        config.hierarchy().forEach(it -> processClassHierarchy(context, it));
        // process each configured class
        config.classes().forEach(it -> addSingleClass(context, it));

        // rest client registration (proxy support)
        processRegisterRestClient(context);

        // JPA Entity registration
        processEntity(context);

        // all classes, fields and methods annotated with @Reflected
        addAnnotatedWithReflected(context);

        // and finally register with native image
        registerForReflection(context);
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        MpConfigProviderResolver.buildTimeEnd();
    }

    @SuppressWarnings("unchecked")
    private void addAnnotatedWithReflected(BeforeAnalysisContext context) {
        FeatureImpl.BeforeAnalysisAccessImpl access = context.access();

        // want to make sure we use the correct classloader
        Class<? extends Annotation> annotation = (Class<? extends Annotation>) access.findClassByName(Reflected.class.getName());

        traceParsing(() -> "Looking up annotated by " + annotation.getName());

        // classes
        access.findAnnotatedClasses(annotation)
                .forEach(it -> {
                    traceParsing(() -> " class " + it.getName());
                    context.register(it).addAll();
                });

        // methods
        access.findAnnotatedMethods(annotation)
                .forEach(it -> {
                    if (context.register(it.getDeclaringClass()).add(it)) {
                        traceParsing(() -> " method " + it);
                    }
                });

        // fields
        access.findAnnotatedFields(annotation)
                .forEach(it -> {
                    if (context.register(it.getDeclaringClass()).add(it)) {
                        traceParsing(() -> " field " + it);
                    }
                });

        // attempt to add annotated constructors if any other field is registered
        context.toRegister()
                .forEach(register -> addAnnotatedConstructors(register, annotation));
    }

    private void addAnnotatedConstructors(Register register, Class<? extends Annotation> annotation) {
        // only do this if constructors is empty, as otherwise they are already all there
        if (register.constructors.isEmpty()) {
            try {
                Constructor<?>[] constructors = register.clazz.getConstructors();
                for (Constructor<?> constructor : constructors) {
                    if (constructor.getAnnotation(annotation) != null) {
                        if (register.add(constructor)) {
                            traceParsing(() -> " constructor " + constructor);
                        }
                    }
                }
                constructors = register.clazz.getDeclaredConstructors();
                for (Constructor<?> constructor : constructors) {
                    if (constructor.getAnnotation(annotation) != null) {
                        if (register.add(constructor)) {
                            traceParsing(() -> " constructor " + constructor);
                        }
                    }
                }
            } catch (NoClassDefFoundError e) {
                if (TRACE) {
                    System.out.println("Constructors of "
                                               + register.clazz.getName()
                                               + " not added to reflection, as a type is not on classpath: "
                                               + e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processEntity(BeforeAnalysisContext context) {
        final Class<? extends Annotation> annotation = (Class<? extends Annotation>) context.access()
                .findClassByName(ENTITY_ANNOTATION_CLASS_NAME);
        if (annotation == null) {
            return;
        }
        traceParsing(() -> "Looking up annotated by " + ENTITY_ANNOTATION_CLASS_NAME);
        final List<Class<?>> annotatedList = context.access().findAnnotatedClasses(annotation);
        annotatedList.forEach(aClass -> {
            String resourceName = aClass.getName().replace('.', '/') + ".class";
            InputStream resourceStream = aClass.getClassLoader().getResourceAsStream(resourceName);
            Resources.registerResource(resourceName, resourceStream);
            for (Field declaredField : aClass.getDeclaredFields()) {
                if (!Modifier.isPublic(declaredField.getModifiers()) && declaredField.getAnnotations().length == 0) {
                    RuntimeReflection.register(declaredField);
                    if (TRACE) {
                        System.out.println("    non annotated field " + declaredField);
                    }
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void processRegisterRestClient(BeforeAnalysisContext context) {
        String restClientAnnotationClassName = "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";
        Class<? extends Annotation> restClientAnnotation = (Class<? extends Annotation>) context.access()
                .findClassByName(restClientAnnotationClassName);

        if (null == restClientAnnotation) {
            return;
        }

        traceParsing(() -> "Looking up annotated by " + restClientAnnotationClassName);

        List<Class<?>> annotatedList = context.access().findAnnotatedClasses(restClientAnnotation);
        DynamicProxyRegistry proxyRegistry = ImageSingletons.lookup(DynamicProxyRegistry.class);
        Class<?> autoCloseable = context.access().findClassByName("java.lang.AutoCloseable");
        Class<?> closeable = context.access().findClassByName("java.io.Closeable");

        annotatedList.forEach(it -> {
            if (context.isExcluded(it)) {
                traceParsing(() -> "Class " + it.getName() + " annotated by " + restClientAnnotationClassName + " is excluded");
            } else {
                // we need to add it for reflection
                processClassHierarchy(context, it);
                // and we also need to create a proxy
                traceParsing(() -> "Registering a proxy for class " + it.getName());
                proxyRegistry.addProxyClass(it, autoCloseable, closeable);
            }
        });
    }

    private void registerForReflection(BeforeAnalysisContext context) {
        Collection<Register> toRegister = context.toRegister();

        if (TRACE) {
            System.out.println("***********************************");
            System.out.println("** Registering " + toRegister.size() + " classes for reflection");
            System.out.println("***********************************");
        }

        // register for reflection
        for (Register register : toRegister) {
            register(register.clazz);

            if (!register.clazz.isInterface()) {
                register.fields.forEach(this::register);
                register.constructors.forEach(this::register);
            }

            register.methods.forEach(this::register);
        }
    }

    private void register(Constructor<?> constructor) {
        if (TRACE) {
            System.out.println("    " + constructor.getDeclaringClass().getSimpleName()
                                       + "("
                                       + params(constructor.getParameterTypes())
                                       + ")");
        }
        RuntimeReflection.register(constructor);
    }

    private String params(Class<?>[] parameterTypes) {
        if (parameterTypes.length == 0) {
            return "";
        }
        return Arrays.stream(parameterTypes)
                .map(Class::getName)
                .collect(Collectors.joining(", "));
    }

    private void register(Field field) {
        if (TRACE) {
            System.out.println("    " + field.getType() + " " + field.getName());
        }

        RuntimeReflection.register(field);
    }

    private void register(Method method) {
        if (TRACE) {
            System.out.println("    " + method.getReturnType().getName() + " " + method
                    .getName() + "(" + params(method.getParameterTypes()) + ")");
        }
        RuntimeReflection.register(method);
    }

    private void register(Class<?> clazz) {
        if (TRACE) {
            System.out.println("Registering " + clazz.getName() + " for reflection");
        }

        RuntimeReflection.register(clazz);
    }

    private static boolean hasParams(Method method, Class<?>... params) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return Arrays.equals(params, parameterTypes);
    }

    private void addSingleClass(BeforeAnalysisContext context,
                                Class<?> theClass) {
        if (context.process(theClass)) {
            traceParsing(theClass::getName);
            traceParsing(() -> "  Added for registration");
            superclasses(context, theClass);
            context.register(theClass).addDefaults();
        }
    }

    private void processClassHierarchy(BeforeAnalysisContext context,
                                       Class<?> superclass) {

        // this class is always registered (interface or class)
        context.register(superclass).addDefaults();

        traceParsing(() -> "Looking up implementors of " + superclass.getName());

        findSubclasses(context, superclass);
        for (Class<?> anInterface : superclass.getInterfaces()) {
            // unless excluded
            if (context.isExcluded(anInterface)) {
                traceParsing(() -> "  Interface " + anInterface.getName() + " is explicitly excluded");
            } else {
                addSingleClass(context, anInterface);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processAnnotated(BeforeAnalysisContext context,
                                  Class<?> annotationClass) {

        Class<? extends Annotation> annotation;
        try {
            annotation = (Class<? extends Annotation>) annotationClass;
        } catch (ClassCastException e) {
            throw new IllegalStateException("Class configured as annotation is not an annotation: " + annotationClass.getName(),
                                            e);
        }

        traceParsing(() -> "Looking up annotated by " + annotationClass.getName());

        List<Class<?>> annotatedList = context.access().findAnnotatedClasses(annotation);

        annotatedList.forEach(it -> {
            if (context.isExcluded(it)) {
                traceParsing(() -> "Class " + it.getName() + " annotated by " + annotationClass.getName() + " is excluded");
            } else {
                processClassHierarchy(context, it);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void findSubclasses(BeforeAnalysisContext context, Class<?> aClass) {
        List<Class<?>> subclasses = context.access().findSubclasses((Class<Object>) aClass);

        processClasses(context, subclasses);
    }

    private void processClasses(BeforeAnalysisContext context, List<Class<?>> classes) {
        for (Class<?> aClass : classes) {
            if (context.process(aClass)) {
                if (context.isExcluded(aClass)) {
                    traceParsing(() -> "    Excluding " + aClass.getName() + " from registration");
                    continue;
                }

                traceParsing(() -> "    " + aClass.getName());

                int modifiers = aClass.getModifiers();

                traceParsing(() -> "        Added for registration");

                superclasses(context, aClass);
                context.register(aClass).addDefaults();

                if (!Modifier.isFinal(modifiers)) {
                    findSubclasses(context, aClass);
                }
            }
        }
    }

    private void superclasses(BeforeAnalysisContext context, Class<?> aClass) {
        Class<?> nextSuper = aClass.getSuperclass();
        while (null != nextSuper) {
            if (context.process(nextSuper)) {
                if (context.isExcluded(nextSuper)) {
                    Class<?> toLog = nextSuper;
                    traceParsing(() -> "  Class " + toLog.getName() + " is explicitly excluded");
                    nextSuper = null;
                } else {
                    traceParsing(nextSuper::getName);
                    traceParsing(() -> "  Added for registration");

                    context.register(nextSuper).addDefaults();
                    nextSuper = nextSuper.getSuperclass();
                }
            } else {
                nextSuper = nextSuper.getSuperclass();
            }
        }
    }

    private HelidonReflectionConfiguration loadConfiguration(BeforeAnalysisAccess access) {
        // load a known class (config is required by all components)
        Class<?> configClass = access.findClassByName("io.helidon.config.Config");
        // to get the classloader to retrieve our configuration
        ClassLoader cl = configClass.getClassLoader();
        try {
            Enumeration<URL> resources = cl.getResources("META-INF/helidon/native-image/reflection-config.json");
            HelidonReflectionConfiguration config = new HelidonReflectionConfiguration();
            JsonReaderFactory readerFactory = Json.createReaderFactory(Map.of());
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try {
                    JsonObject configurationJson = readerFactory.createReader(url.openStream()).readObject();
                    jsonArray(access, config.annotations, configurationJson.getJsonArray("annotated"), "Annotation");
                    jsonArray(access, config.hierarchy, configurationJson.getJsonArray("class-hierarchy"), "Class hierarchy");
                    jsonArray(access, config.classes, configurationJson.getJsonArray("classes"), "Single");
                    jsonArray(access, config.excluded, configurationJson.getJsonArray("exclude"), "Exclude");
                } catch (JsonParsingException e) {
                    System.err.println("Failed to process configuration file: " + url);
                    throw e;
                }
            }

            return config;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to process configuration from helidon-reflection-config.json files", e);
        }
    }

    private void jsonArray(BeforeAnalysisAccess access, Collection<Class<?>> classList, JsonArray classNames, String desc) {
        if (null == classNames) {
            return;
        }
        for (int i = 0; i < classNames.size(); i++) {
            String className = classNames.getString(i);
            boolean isArray = false;
            if (className.endsWith("[]")) {
                // an array
                isArray = true;
                className = className.substring(0, className.length() - 2);
            }
            Class<?> clazz = access.findClassByName(className);
            if (null == clazz) {
                final String logName = className;
                traceParsing(() -> desc + " class \"" + logName + "\" configured for reflection is not on classpath");
                continue;
            } else {
                classList.add(clazz);
            }

            if (isArray) {
                Object anArray = Array.newInstance(clazz, 0);
                classList.add(anArray.getClass());
            }
        }
    }

    private void traceParsing(Supplier<String> message) {
        if (TRACE_PARSING) {
            System.out.println(message.get());
        }
    }

    private static final class HelidonReflectionConfiguration {
        private final List<Class<?>> annotations = new LinkedList<>();
        private final List<Class<?>> hierarchy = new LinkedList<>();
        private final List<Class<?>> classes = new LinkedList<>();
        private final Set<Class<?>> excluded = new HashSet<>();

        private List<Class<?>> annotations() {
            return annotations;
        }

        private List<Class<?>> hierarchy() {
            return hierarchy;
        }

        private List<Class<?>> classes() {
            return classes;
        }
    }

    private static final class BeforeAnalysisContext {
        private final FeatureImpl.BeforeAnalysisAccessImpl access;
        private final Set<Class<?>> processed = new HashSet<>();
        private final Set<Class<?>> excluded = new HashSet<>();
        private final Map<Class<?>, Register> registers = new HashMap<>();

        private BeforeAnalysisContext(BeforeAnalysisAccess access, Set<Class<?>> excluded) {
            this.access = (FeatureImpl.BeforeAnalysisAccessImpl) access;
            this.excluded.addAll(excluded);
        }

        public FeatureImpl.BeforeAnalysisAccessImpl access() {
            return access;
        }

        public boolean process(Class<?> theClass) {
            return processed.add(theClass);
        }

        public Register register(Class<?> theClass) {
            return registers.computeIfAbsent(theClass, Register::new);
        }

        public Collection<Register> toRegister() {
            return registers.values();
        }

        boolean isExcluded(Class<?> theClass) {
            return excluded.contains(theClass);
        }
    }

    private static class Register {
        private final Set<Method> methods = new HashSet<>();
        private final Set<Field> fields = new HashSet<>();
        private final Set<Constructor<?>> constructors = new HashSet<>();

        private final Class<?> clazz;

        private Register(Class<?> clazz) {
            this.clazz = clazz;
        }

        boolean add(Method m) {
            return methods.add(m);
        }

        boolean add(Field f) {
            return fields.add(f);
        }

        boolean add(Constructor<?> c) {
            return constructors.add(c);
        }

        void addAll() {
            addMethods();
            if (clazz.isInterface()) {
                return;
            }
            addConstructors();
            addFields(true);
        }

        void addDefaults() {
            addMethods();
            if (clazz.isInterface()) {
                return;
            }
            addFields(false);
            addConstructors();
        }

        private void addConstructors() {
            try {
                Constructor<?>[] constructors = clazz.getConstructors();
                for (Constructor<?> constructor : constructors) {
                    add(constructor);
                }
            } catch (NoClassDefFoundError e) {
                if (TRACE) {
                    System.out.println("Public constructors of "
                                               + clazz.getName()
                                               + " not added to reflection, as a type is not on classpath: "
                                               + e.getMessage());
                }
            }
            try {
                // add all declared
                Constructor<?>[] constructors = clazz.getDeclaredConstructors();
                for (Constructor<?> constructor : constructors) {
                    add(constructor);
                }
            } catch (NoClassDefFoundError e) {
                if (TRACE) {
                    System.out.println("Constructors of "
                                               + clazz.getName()
                                               + " not added to reflection, as a type is not on classpath: "
                                               + e.getMessage());
                }
            }
        }

        void addFields(boolean all) {
            Field[] fields = clazz.getFields();

            try {
                // add all public fields
                for (Field field : fields) {
                    add(field);
                }
            } catch (NoClassDefFoundError e) {
                if (TRACE) {
                    System.out.println("Public fields of "
                                               + clazz.getName()
                                               + " not added to reflection, as a type is not on classpath: "
                                               + e.getMessage());
                }
            }
            try {
                for (Field declaredField : clazz.getDeclaredFields()) {
                    // there may be fields referencing classes not on the classpath
                    if (!Modifier.isPublic(declaredField.getModifiers())) {
                        // public already registered
                        if (all || declaredField.getAnnotations().length > 0) {
                            add(declaredField);
                        }
                    }
                }
            } catch (NoClassDefFoundError e) {
                if (TRACE) {
                    System.out.println("Fields of "
                                               + clazz.getName()
                                               + " not added to reflection, as a type is not on classpath: "
                                               + e.getMessage());
                }
            }
        }

        void addMethods() {
            try {
                Method[] methods = clazz.getMethods();
                for (Method method : methods) {
                    boolean register;

                    // we do not want wait, notify etc
                    register = (method.getDeclaringClass() != Object.class);

                    if (register) {
                        // we do not want toString(), hashCode(), equals(java.lang.Object)
                        switch (method.getName()) {
                        case "hashCode":
                        case "toString":
                            register = !hasParams(method);
                            break;
                        case "equals":
                            register = !hasParams(method, Object.class);
                            break;
                        default:
                            // do nothing
                        }
                    }

                    if (register) {
                        if (TRACE) {
                            System.out.println("  " + method.getName() + "(" + Arrays.toString(method.getParameterTypes()) + ")");
                        }
                        add(method);
                    }
                }
            } catch (Throwable e) {
                if (TRACE) {
                    System.out
                            .println("   Cannot register methods of " + clazz.getName() + ": " + e.getClass().getName() + ": " + e
                                    .getMessage());
                }
            }
        }
    }
}
