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
package org.jdbi.v3.generator;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.MethodSpec.Builder;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.jdbi.v3.core.extension.HandleSupplier;
import org.jdbi.v3.core.generic.GenericType;
import org.jdbi.v3.sqlobject.GenerateUtils;
import org.jdbi.v3.sqlobject.Handler;
import org.jdbi.v3.sqlobject.SqlObject;

@SupportedAnnotationTypes("org.jdbi.v3.sqlobject.Generate")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class GenerateProcessor extends AbstractProcessor {
    private static final Set<ElementKind> ACCEPTABLE = EnumSet.of(ElementKind.CLASS, ElementKind.INTERFACE);

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }
        final TypeElement gens = annotations.iterator().next();
        final Set<? extends Element> annoTypes = roundEnv.getElementsAnnotatedWith(gens);
        annoTypes.forEach(this::generate);
        return true;
    }

    private void generate(Element e) {
        try {
            generate0(e);
        } catch (Exception ex) {
            processingEnv.getMessager().printMessage(Kind.ERROR, "Failure: " + ex, e);
            throw new RuntimeException(ex);
        }
    }

    private void generate0(Element e) throws IOException {
        processingEnv.getMessager().printMessage(Kind.NOTE, String.format("[jdbi] generating for %s", e));
        if (!ACCEPTABLE.contains(e.getKind())) {
            throw new IllegalStateException("Generate on non-class: " + e);
        }
        if (!e.getModifiers().contains(Modifier.ABSTRACT)) {
            throw new IllegalStateException("Generate on non-abstract class: " + e);
        }

        final TypeElement te = (TypeElement) e;
        final String implName = te.getSimpleName() + "Impl";
        final TypeSpec.Builder builder = TypeSpec.classBuilder(implName).addModifiers(Modifier.PUBLIC);
        final TypeName superName = TypeName.get(te.asType());
        if (te.getKind() == ElementKind.CLASS) {
            builder.superclass(superName);
        } else {
            builder.addSuperinterface(superName);
        }
        builder.addSuperinterface(SqlObject.class);
        builder.addField(HandleSupplier.class, "handle", Modifier.FINAL, Modifier.PRIVATE);

        final CodeBlock.Builder constructor = CodeBlock.builder()
                .add("this.handle = handle;\n");

        final CodeBlock.Builder staticInit = CodeBlock.builder();

        builder.addMethod(generateMethod(superName, builder, staticInit, constructor, getHandle()));

        te.getEnclosedElements().stream()
                .filter(ee -> ee.getKind() == ElementKind.METHOD)
                .filter(ee -> ee.getModifiers().contains(Modifier.ABSTRACT))
                .map(ee -> generateMethod(superName, builder, staticInit, constructor, ee))
                .forEach(builder::addMethod);

        builder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HandleSupplier.class, "handle")
                .addParameter(new GenericType<Map<Method, Handler>>() {}.getType(), "handlers")
                .addCode(constructor.build())
                .build());

        builder.addStaticBlock(staticInit.build());

        final JavaFileObject file = processingEnv.getFiler().createSourceFile(processingEnv.getElementUtils().getPackageOf(e) + "." + implName, e);
        try (Writer out = file.openWriter()) {
            JavaFile.builder(packageName(te), builder.build()).build().writeTo(out);
        }
    }

    private MethodSpec generateMethod(TypeName superName, TypeSpec.Builder typeBuilder, CodeBlock.Builder staticInit, CodeBlock.Builder constructor, Element e) {
        final ExecutableElement ee = (ExecutableElement) e;
        final Builder builder = MethodSpec.overriding(ee);
        final String paramNames = ee.getParameters().stream()
                .map(VariableElement::getSimpleName)
                .map(Object::toString)
                .collect(Collectors.joining(","));
        final String paramTypes = ee.getParameters().stream()
                .map(VariableElement::asType)
                .map(processingEnv.getTypeUtils()::erasure)
                .map(t -> t + ".class")
                .collect(Collectors.joining(","));
        final String methodField = "_" + e.getSimpleName() + "Method";
        typeBuilder.addField(Method.class, methodField, Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC);
        staticInit.add("$L = $T.findMethod($T.class, $S, new Class<?>[] {$L});\n", methodField, GenerateUtils.class, superName, e.getSimpleName(), paramTypes);

        final String handlerField = "_" + e.getSimpleName() + "Handler";
        typeBuilder.addField(Handler.class, handlerField, Modifier.PRIVATE, Modifier.FINAL);
        constructor.add("$L = handlers.get($L);\n", handlerField, methodField);

        CodeBlock body = CodeBlock.builder()
                .beginControlFlow("try")
                .add("$L $L.invoke(this, new Object[] {$L}, handle);\n",
                    ee.getReturnType().getKind() == TypeKind.VOID ? "" : ("return (" + ee.getReturnType().toString() + ")"),
                    handlerField, paramNames)
                .unindent().add("} catch (Throwable t) { throw $T.throw0(t); }\n", GenerateUtils.class)
                .build();

        return builder.addCode(body).build();
    }

    private Element getHandle() {
        return processingEnv.getElementUtils().getTypeElement(SqlObject.class.getName()).getEnclosedElements()
                .stream()
                .filter(e -> e.getSimpleName().toString().equals("getHandle"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no Handle.getHandle found"));
    }

    private String packageName(Element e) {
        return processingEnv.getElementUtils().getPackageOf(e).toString();
    }
}
